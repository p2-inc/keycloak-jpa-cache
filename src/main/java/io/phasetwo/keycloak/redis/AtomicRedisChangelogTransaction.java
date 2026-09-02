package io.phasetwo.keycloak.redis;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.UnifiedJedis;

/**
 * STANDALONE and SENTINEL: a single keyspace, so every block of an operation rides one {@code
 * MULTI}/{@code EXEC}. An entity {@code DEL} and its index {@code SREM}s — or a {@code CAS} write's
 * index {@code SADD}s — commit or fail together, and Redis runs the batch without interleaving
 * other clients. That atomicity is why this topology carries none of the index-reconciliation
 * machinery the cluster subclass needs: the base class's reconciliation hooks stay no-ops here.
 */
@JBossLog
public class AtomicRedisChangelogTransaction<K extends Key, A extends MapEntity<K>>
    extends RedisChangelogTransaction<K, A> {

  AtomicRedisChangelogTransaction(
      String cacheName, UnifiedJedis jedis, AdapterSupplier<K, A> adapterSupplier) {
    super(cacheName, jedis, adapterSupplier);
  }

  @Override
  protected void commitImpl() {
    if (cache.isEmpty() && toDelete.isEmpty()) {
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
  }

  @Override
  protected void deleteEntity(A model) {
    String key = model.getKey().key();
    Map<String, String> indexes = model.getSecondaryIndexes();

    if (!indexes.isEmpty()) {
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
    }
  }

  @Override
  protected void addSecondaryIndexes(A model) {
    Map<String, String> indexes = model.getSecondaryIndexes();
    List<Map.Entry<String, String>> validIndexes =
        indexes.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null)
            .collect(Collectors.toList());

    if (validIndexes.isEmpty()) return;

    try (AbstractTransaction txn = jedis.multi()) {
      for (Map.Entry<String, String> index : validIndexes) {
        log.tracef("[redis] SADD %s %s", index.getKey(), index.getValue());
        txn.sadd(index.getKey(), index.getValue());
        countOperation(SADD);
      }
      txn.exec();
    }
  }
}
