package io.phasetwo.keycloak.redis;

import static io.phasetwo.keycloak.redis.RedisMetrics.*;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.common.ExpirationUtils;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.AbstractKeycloakTransaction;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisChangelogTransaction<K extends Key, A extends MapEntity<K>>
    extends AbstractKeycloakTransaction {

  private final Map<K, A> cache = Maps.newHashMap();
  private final Map<K, A> toDelete = Maps.newHashMap();
  // Stale index members discovered during reads, reaped at commit — reads
  // must never write (keeps ALL Redis mutations inside commitImpl).
  private final Map<String, java.util.Set<String>> indexReaps = Maps.newLinkedHashMap();
  private final AdapterSupplier<K, A> adapterSupplier;
  private final UnifiedJedis jedis;
  private final RedisMode redisMode;
  private final String cacheName;
  private final Meter.MeterProvider<Counter> counterProvider;
  private static final int MAX_CAS_RETRIES = 3;

  public RedisChangelogTransaction(
      String cacheName,
      UnifiedJedis jedis,
      RedisMode redisMode,
      AdapterSupplier<K, A> adapterSupplier) {
    this.cacheName = cacheName;
    this.jedis = jedis;
    this.redisMode = redisMode;
    this.adapterSupplier = adapterSupplier;
    this.counterProvider = getCacheCounterProvider();
  }

  public RedisChangelogTransaction(
      String cacheName, UnifiedJedis jedis, AdapterSupplier<K, A> adapterSupplier) {
    this(cacheName, jedis, RedisMode.STANDALONE, adapterSupplier);
  }

  /** Count an operation in metrics */
  void countOperation(String op) {
    List<Tag> tags = Lists.newArrayList();
    tags.add(Tag.of(CACHE_TAG, cacheName));
    tags.add(Tag.of(OPERATION_TAG, op));
    counterProvider.withTags(tags).increment();
  }

  public static final String HGETALL = "HGETALL";
  public static final String HSETEX = "HSETEX";
  public static final String HSET = "HSET";
  public static final String SADD = "SADD";
  public static final String HDEL = "HDEL";
  public static final String SREM = "SREM";
  public static final String DEL = "DEL";
  public static final String WATCH = "WATCH";

  /**
   * Gets the value if present at the key. Creates a new instance and registers it for saving using
   * the adapter supplier if none is present at the key.
   */
  public A get(K k) {
    A model = getIfPresent(k);
    if (model == null) {
      model = adapterSupplier.newInstance(k);
      cache.put(k, model);
    }
    return model;
  }

  /** Gets the value only if present at the key. Returns null otherwise. */
  public A getIfPresent(K k) {
    if (k == null) return null;
    if (toDelete.containsKey(k)) return null; // this is wrong
    A model = cache.get(k);
    if (model != null && !expired(k, model)) return model;
    String key = k.key();
    log.tracef("[redis] HGETALL %s", key);
    Map<String, String> data = jedis.hgetAll(key);
    countOperation(HGETALL);
    if (data != null && !data.isEmpty()) {
      log.tracef("found data for %s %s", key, data);
      model = adapterSupplier.newInstance(k, data);
      if (!expired(k, model)) {
        cache.put(k, model);
        return model;
      }
    }
    return null;
  }

  /** Lazy removal. Check to see if an entity is expired. return true if it was. add it toDelete. */
  private boolean expired(K k, A a) {
    if (a instanceof ExpirableEntity) {
      ExpirableEntity e = (ExpirableEntity) a;
      if (ExpirationUtils.isExpired(e, true)) {
        log.tracef("Entity at %s expired %s. Lazy removing.", k, ExpirationUtils.fromNow(e));
        addForDelete(a);
        return true;
      } else {
        log.tracef("Entity at %s active. Expires in %s.", k, ExpirationUtils.fromNow(e));
        return false;
      }
    }
    log.tracef("Entity at %s is not an expirable entity.", k);
    return false;
  }

  /**
   * Gets the a map of value if present at the given keys. Return value is a map of the key to the
   * value. May be fewer results if some keys don't have values.
   */
  public Map<K, A> getAll(Collection<K> keys) {
    if (keys == null || keys.isEmpty()) return Maps.newLinkedHashMap();
    // try-with-resources: sync() flushes responses but only close() returns a
    // pooled connection — without it, every call leaks one connection when the
    // client is a JedisPooled (upstream-PR candidate; harmless for the
    // single-connection UnifiedJedis case).
    try (AbstractPipeline pipeline = jedis.pipelined()) {
      return getAllPipelined(pipeline, keys);
    }
  }

  private Map<K, A> getAllPipelined(AbstractPipeline pipeline, Collection<K> keys) {
    Map<K, Response<Map<String, String>>> responses = Maps.newLinkedHashMap();
    Map<K, A> result = Maps.newLinkedHashMap();

    // Queue all HGETALLs
    for (K key : keys) {
      if (toDelete.containsKey(key)) continue;
      A model = cache.get(key);
      if (model != null) {
        result.put(key, model);
      } else {
        log.tracef("[redis] HGETALL %s", key.key());
        responses.put(key, pipeline.hgetAll(key.key()));
      }
    }
    if (!responses.isEmpty()) { // only execute if some were not cached
      pipeline.sync(); // flush and read all in one round-trip
      // Build result map
      for (Map.Entry<K, Response<Map<String, String>>> entry : responses.entrySet()) {
        K key = entry.getKey();
        Map<String, String> data = entry.getValue().get();
        if (data != null && !data.isEmpty()) {
          A model = adapterSupplier.newInstance(key, data);
          if (!expired(key, model)) {
            result.put(key, model);
            cache.put(key, model);
          }
        }
      }
    }
    return result;
  }

  public void addForSave(A model) {
    cache.put(model.getKey(), model);
  }

  public void addForDelete(A model) {
    toDelete.put(model.getKey(), model);
  }

  /**
   * Registers a stale secondary-index member (its value key expired or
   * vanished) for removal at commit. Read paths that discover staleness call
   * this instead of issuing SREM inline: the read skips the member
   * immediately for correctness, and the reap defers with everything else —
   * no Redis writes outside {@link #commitImpl()}.
   */
  public void reapIndexMemberOnCommit(String indexKey, String member) {
    if (indexKey == null || member == null) return;
    indexReaps.computeIfAbsent(indexKey, k -> new java.util.LinkedHashSet<>()).add(member);
  }

  public void cachedToDelete() {
    for (A model : cache.values()) {
      addForDelete(model);
    }
  }

  @Override
  protected void commitImpl() {
    if (cache.isEmpty() && toDelete.isEmpty() && indexReaps.isEmpty()) {
      log.trace("nothing to commit. skipping transaction...");
      return;
    }

    for (A model : Lists.newArrayList(cache.values())) {
      if (model.isMarkedForDelete() || toDelete.containsKey(model.getKey())) {
        deleteEntity(model);
        toDelete.remove(model.getKey());
      } else if (model.isDirty()) {
        writeEntityWithRetries(model);
      }
    }

    for (A model : Lists.newArrayList(toDelete.values())) {
      deleteEntity(model);
      toDelete.remove(model.getKey());
    }

    reapStaleIndexMembers();
  }

  /** Deferred audit-§4.7 hygiene: batch-SREM stale index members at commit. */
  private void reapStaleIndexMembers() {
    if (indexReaps.isEmpty()) return;
    try (AbstractPipeline pipeline = jedis.pipelined()) {
      reapPipelined(pipeline);
    }
    indexReaps.clear();
  }

  private void reapPipelined(AbstractPipeline pipeline) {
    for (Map.Entry<String, java.util.Set<String>> entry : indexReaps.entrySet()) {
      log.tracef("[redis] SREM %s %s (stale-index reap)", entry.getKey(), entry.getValue());
      pipeline.srem(entry.getKey(), entry.getValue().toArray(new String[0]));
      countOperation(SREM);
    }
    pipeline.sync();
  }

  private void deleteEntity(A model) {
    String key = model.getKey().key();
    Map<String, String> indexes = model.getSecondaryIndexes();

    if (redisMode != RedisMode.CLUSTER && !indexes.isEmpty()) {
      try (AbstractTransaction txn = jedis.multi()) {
        log.tracef("[redis] DEL %s", key);
        txn.del(key);
        countOperation(DEL);
        for (Map.Entry<String, String> index : indexes.entrySet()) {
          log.tracef("[redis] SREM %s %s", index.getKey(), index.getValue());
          txn.srem(index.getKey(), index.getValue());
          countOperation(SREM);
        }
        txn.exec();
      }
    } else {
      log.tracef("[redis] DEL %s", key);
      jedis.del(key);
      countOperation(DEL);
      for (Map.Entry<String, String> index : indexes.entrySet()) {
        log.tracef("[redis] SREM %s %s", index.getKey(), index.getValue());
        jedis.srem(index.getKey(), index.getValue());
        countOperation(SREM);
      }
    }
  }

  private void writeEntityWithRetries(A originalModel) {
    A attemptModel = originalModel;

    for (int attempt = 0; attempt <= MAX_CAS_RETRIES; attempt++) {
      RedisHashCas.CasInvocation invocation = writeEntityOnce(attemptModel);
      long code = invocation.getResponseCode();
      if (code == 1L) {
        log.tracef("[redis] CAS hsetex returned success code %s. %s", code, invocation);
        addSecondaryIndexes(attemptModel);
        return;
      }

      log.warnf("[redis] CAS hsetex returned non-success code %s. %s", code, invocation);
      if ((code != 0L && code != -1L) || attempt == MAX_CAS_RETRIES) {
        throw new IllegalStateException(
            String.format(
                "Redis CAS failed for key %s after %d attempts with code %d",
                attemptModel.getKey().key(), attempt + 1, code));
      }

      attemptModel = rebaseModel(originalModel);
    }
  }

  private RedisHashCas.CasInvocation writeEntityOnce(A model) {
    Long expireAtMs = null;
    if (model instanceof ExpirableEntity) {
      ExpirableEntity e = (ExpirableEntity) model;
      expireAtMs = e.getExpiration();
    }

    RedisHashCas cas = new RedisHashCas(jedis);
    RedisHashCas.CasInvocation invocation =
        cas.hsetex(
            model.getKey().key(),
            model.getVersion(),
            expireAtMs,
            model.getDirtyFields(),
            model.getDeletedFields());
    countOperation(HSETEX);
    if (!model.getDeletedFields().isEmpty()) {
      countOperation(HDEL);
    }
    return invocation;
  }

  private void addSecondaryIndexes(A model) {
    Map<String, String> indexes = model.getSecondaryIndexes();
    List<Map.Entry<String, String>> validIndexes =
        indexes.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null)
            .collect(Collectors.toList());

    if (validIndexes.isEmpty()) return;

    if (redisMode != RedisMode.CLUSTER) {
      try (AbstractTransaction txn = jedis.multi()) {
        for (Map.Entry<String, String> index : validIndexes) {
          log.tracef("[redis] SADD %s %s", index.getKey(), index.getValue());
          txn.sadd(index.getKey(), index.getValue());
          countOperation(SADD);
        }
        txn.exec();
      }
    } else {
      for (Map.Entry<String, String> index : validIndexes) {
        log.tracef("[redis] SADD %s %s", index.getKey(), index.getValue());
        jedis.sadd(index.getKey(), index.getValue());
        countOperation(SADD);
      }
    }
  }

  private A rebaseModel(A originalModel) {
    K key = originalModel.getKey();
    String redisKey = key.key();
    log.tracef("[redis] HGETALL %s (rebase)", redisKey);
    Map<String, String> latestData = jedis.hgetAll(redisKey);
    countOperation(HGETALL);

    A rebasedModel =
        latestData == null || latestData.isEmpty()
            ? adapterSupplier.newInstance(key)
            : adapterSupplier.newInstance(key, latestData);
    originalModel.replayPendingChangesOnto(rebasedModel);
    return rebasedModel;
  }

  @Override
  protected void rollbackImpl() {
    // Buffered state is simply dropped; pending index reaps too (they are an
    // optimization — the next reader re-discovers and re-registers them).
    indexReaps.clear();
  }
}
