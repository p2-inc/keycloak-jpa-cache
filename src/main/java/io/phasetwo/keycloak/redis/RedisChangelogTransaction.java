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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.AbstractKeycloakTransaction;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;

/**
 * Changelog transaction over Redis hashes with secondary-index Sets. The entity lifecycle — read
 * cache, lazy expiry, CAS write with rebase-and-retry, delete — is topology-independent and lives
 * here. What a topology decides is how an entity's index Sets are written and deleted, and whether
 * it needs the index-reconciliation machinery at all:
 *
 * <ul>
 *   <li>{@link AtomicRedisChangelogTransaction} (STANDALONE, SENTINEL) — a single keyspace: entity
 *       and index changes ride one {@code MULTI}/{@code EXEC}. Nothing else.
 *   <li>{@link ClusterRedisChangelogTransaction} (CLUSTER) — an entity key and its index Sets hash
 *       to different slots, so a {@code MULTI} spanning them is rejected and every command is
 *       issued on its own. Because that leaves gaps in which an index Set can hold a member whose
 *       entity is gone, this subclass also carries the TTL backstops and read-path reconciliation
 *       (issue #78).
 * </ul>
 *
 * Instances come from {@link #create}, keyed on {@link RedisMode}.
 */
@JBossLog
public abstract class RedisChangelogTransaction<K extends Key, A extends MapEntity<K>>
    extends AbstractKeycloakTransaction {

  protected final Map<K, A> cache = Maps.newHashMap();
  protected final Map<K, A> toDelete = Maps.newHashMap();
  private final AdapterSupplier<K, A> adapterSupplier;
  protected final UnifiedJedis jedis;
  private final String cacheName;
  private final Meter.MeterProvider<Counter> counterProvider;
  private static final int MAX_CAS_RETRIES = 3;

  protected RedisChangelogTransaction(
      String cacheName, UnifiedJedis jedis, AdapterSupplier<K, A> adapterSupplier) {
    this.cacheName = cacheName;
    this.jedis = jedis;
    this.adapterSupplier = adapterSupplier;
    this.counterProvider = getCacheCounterProvider();
  }

  // ---------------------------------------------------------------------------------------------
  // Topology registry
  // ---------------------------------------------------------------------------------------------

  @FunctionalInterface
  @SuppressWarnings("rawtypes")
  private interface Factory {
    RedisChangelogTransaction create(
        String cacheName, UnifiedJedis jedis, AdapterSupplier adapterSupplier);
  }

  /**
   * One transaction subclass per {@link RedisMode}, keyed explicitly and checked exhaustive at
   * class-load, so adding a mode is a decision made here rather than silently inheriting a default
   * branch. STANDALONE and SENTINEL share a subclass: both are a single keyspace.
   */
  private static final Map<RedisMode, Factory> FACTORIES = new EnumMap<>(RedisMode.class);

  static {
    FACTORIES.put(RedisMode.STANDALONE, AtomicRedisChangelogTransaction::new);
    FACTORIES.put(RedisMode.SENTINEL, AtomicRedisChangelogTransaction::new);
    FACTORIES.put(RedisMode.CLUSTER, ClusterRedisChangelogTransaction::new);
    for (RedisMode mode : RedisMode.values()) {
      if (!FACTORIES.containsKey(mode)) {
        throw new IllegalStateException("No RedisChangelogTransaction registered for " + mode);
      }
    }
  }

  /** Selects the transaction implementation for the configured topology. */
  @SuppressWarnings("unchecked")
  public static <K extends Key, A extends MapEntity<K>> RedisChangelogTransaction<K, A> create(
      String cacheName,
      UnifiedJedis jedis,
      RedisMode redisMode,
      AdapterSupplier<K, A> adapterSupplier) {
    Objects.requireNonNull(redisMode, "redisMode");
    return FACTORIES.get(redisMode).create(cacheName, jedis, adapterSupplier);
  }

  // ---------------------------------------------------------------------------------------------
  // Template hooks
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code DEL} the entity hash and {@code SREM} it from each of its index Sets — grouped however
   * the topology allows.
   */
  protected abstract void deleteEntity(A model);

  /**
   * {@code SADD} a freshly written entity into each of its index Sets — grouped however the
   * topology allows, plus whatever the topology needs to keep those Sets bounded.
   */
  protected abstract void addSecondaryIndexes(A model);

  /**
   * The CAS write for one entity. The default is the Lua {@code HSETEX} script, which every Redis
   * topology supports; a subclass for a store that blocks scripting can substitute its own.
   */
  protected RedisHashCas.CasInvocation performCasWrite(A model) {
    Long expireAtMs = null;
    if (model instanceof ExpirableEntity) {
      ExpirableEntity e = (ExpirableEntity) model;
      expireAtMs = e.getExpiration();
    }
    return new RedisHashCas(jedis)
        .hsetex(
            model.getKey().key(),
            model.getVersion(),
            expireAtMs,
            model.getDirtyFields(),
            model.getDeletedFields());
  }

  // Read-path reconciliation hooks. By-index reads call these unconditionally; only a topology that
  // needs index reconciliation records anything (see ClusterRedisChangelogTransaction). No-ops
  // here.

  /** Register a dangling index member to be removed at commit. */
  public void reapIndexMemberOnCommit(String indexKey, String member) {}

  /**
   * Register that an index Set must stay alive until {@code coverUntilMs} ({@code null}: forever).
   */
  public void extendIndexTtlOnCommit(String indexKey, Long coverUntilMs) {}

  /** Fold the live members' expirations a by-index read resolved into one TTL extension. */
  public void extendIndexTtlToCoverLiveMembers(String indexKey, Collection<Long> liveExpirations) {}

  // ---------------------------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------------------

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
    Map<K, Response<Map<String, String>>> responses = Maps.newLinkedHashMap();
    Map<K, A> result = Maps.newLinkedHashMap();

    // Closed on exit so the pooled connection the pipeline borrowed is always returned — even on
    // the all-cached path where sync() is never reached.
    try (AbstractPipeline pipeline = jedis.pipelined()) {
      // Queue all HGETALLs
      for (K key : keys) {
        if (toDelete.containsKey(key)) continue;
        A model = cache.get(key);
        if (model != null) {
          // Match getIfPresent: a cached entry that has since expired must not be returned as live
          // (expired() schedules its lazy delete).
          if (!expired(key, model)) {
            result.put(key, model);
          }
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
    }
    return result;
  }

  public void addForSave(A model) {
    cache.put(model.getKey(), model);
  }

  public void addForDelete(A model) {
    toDelete.put(model.getKey(), model);
  }

  public void cachedToDelete() {
    for (A model : cache.values()) {
      addForDelete(model);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Commit building blocks. Each topology owns its commitImpl and drives these in the order it
  // needs.
  // ---------------------------------------------------------------------------------------------

  /** CAS-write a dirty entity (rebase and retry on version conflict), then index it. */
  protected void writeEntityWithRetries(A originalModel) {
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
    RedisHashCas.CasInvocation invocation = performCasWrite(model);
    countOperation(HSETEX);
    if (!model.getDeletedFields().isEmpty()) {
      countOperation(HDEL);
    }
    return invocation;
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
    // No action needed on rollback for this use case
  }
}
