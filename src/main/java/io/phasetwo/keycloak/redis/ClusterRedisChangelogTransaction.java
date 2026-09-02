package io.phasetwo.keycloak.redis;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.phasetwo.keycloak.common.ExpirableEntity;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.args.ExpiryOption;

/**
 * CLUSTER: an entity hash and its index Sets hash to different slots, so a {@code MULTI} spanning
 * them is rejected. Each key's block is issued on its own, one round-trip per command. That is the
 * only place atomicity is lost — and it is what makes everything else in this class necessary: a
 * crash between an entity {@code DEL} and its {@code SREM} leaves a dangling member, and a session
 * whose hash TTL-expires without an explicit delete leaves one too. So this topology alone carries
 * (issue #78):
 *
 * <ul>
 *   <li>a grow-only TTL backstop on every index Set, stamped on the write path from the referencing
 *       entity's expiration, so dangling members cannot accumulate unbounded;
 *   <li>read-path self-healing: a by-index read registers members that no longer resolve for {@code
 *       SREM} at commit, and pushes the Set's TTL up to cover the longest-lived live member it did
 *       resolve — no Redis write ever leaves the read path;
 *   <li>a commit-time {@code EXISTS} re-check so a member re-created by a concurrent transaction
 *       between read and commit is never reaped.
 * </ul>
 *
 * Reconciliation is best-effort and caught <em>per key</em>: a transient single-shard failure
 * ({@code MOVED} during resharding, a connection drop) must not fail the commit nor abort the
 * remaining keys' work.
 */
@JBossLog
public class ClusterRedisChangelogTransaction<K extends Key, A extends MapEntity<K>>
    extends RedisChangelogTransaction<K, A> {

  private final Map<String, Set<String>> pendingIndexReaps = Maps.newLinkedHashMap();
  private final Map<String, Long> pendingIndexTtlExtensions = Maps.newLinkedHashMap();

  /**
   * {@link #extendIndexTtlOnCommit} horizon meaning "a live member never expires": no finite TTL
   * can cover it, so the index Set is {@code PERSIST}ed instead. Real horizons are epoch millis
   * ({@code > 0}), so {@code -1} is unambiguous.
   */
  private static final long PERSIST_INDEX_TTL = -1L;

  ClusterRedisChangelogTransaction(
      String cacheName, UnifiedJedis jedis, AdapterSupplier<K, A> adapterSupplier) {
    super(cacheName, jedis, adapterSupplier);
  }

  // ---------------------------------------------------------------------------------------------
  // Write path: per-key DEL/SREM and SADD + TTL backstop
  // ---------------------------------------------------------------------------------------------

  /**
   * The entity key and its index Sets live in different slots, so the {@code DEL} and each {@code
   * SREM} are separate commands. A crash between them leaves a dangling member — which the
   * read-path reap exists to clean up.
   */
  @Override
  protected void deleteEntity(A model) {
    String key = model.getKey().key();
    log.tracef("[redis] DEL %s", key);
    jedis.del(key);
    countOperation(DEL);
    for (Map.Entry<String, String> index : model.getSecondaryIndexes().entrySet()) {
      log.tracef("[redis] SREM %s %s", index.getKey(), index.getValue());
      jedis.srem(index.getKey(), index.getValue());
      countOperation(SREM);
    }
  }

  /**
   * Each {@code SADD} is issued on its own, followed by a TTL backstop on the Set derived from the
   * referencing entity's expiration, so dangling members cannot accumulate unbounded when a hash
   * TTL-expires without going through the explicit delete path.
   */
  @Override
  protected void addSecondaryIndexes(A model) {
    List<Map.Entry<String, String>> validIndexes =
        model.getSecondaryIndexes().entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null)
            .collect(Collectors.toList());
    if (validIndexes.isEmpty()) return;

    Long expireAtMs = null;
    if (model instanceof ExpirableEntity) {
      expireAtMs = ((ExpirableEntity) model).getExpiration();
    }

    // Read whether each index Set already exists BEFORE the SADD creates it, so the backstop can
    // tell a fresh Set (establish the initial TTL, NX) from a pre-existing one (grow-only, GT;
    // never re-finite-ize a Set a read PERSISTed for a never-expiring member). One pipelined
    // round-trip for all of them rather than N serial EXISTS on the hot write path.
    Map<String, Boolean> alreadyExisted = Maps.newHashMapWithExpectedSize(validIndexes.size());
    try (AbstractPipeline pipeline = jedis.pipelined()) {
      Map<String, Response<Boolean>> responses = Maps.newLinkedHashMap();
      for (Map.Entry<String, String> index : validIndexes) {
        responses.computeIfAbsent(index.getKey(), pipeline::exists);
      }
      pipeline.sync();
      for (Map.Entry<String, Response<Boolean>> e : responses.entrySet()) {
        alreadyExisted.put(e.getKey(), Boolean.TRUE.equals(e.getValue().get()));
      }
    }

    long now = Time.currentTimeMillis();
    for (Map.Entry<String, String> index : validIndexes) {
      log.tracef("[redis] SADD %s %s", index.getKey(), index.getValue());
      jedis.sadd(index.getKey(), index.getValue());
      countOperation(SADD);
      applyTtlBackstop(index.getKey(), expireAtMs, !alreadyExisted.get(index.getKey()), now);
    }
  }

  /**
   * Establish a TTL only on a <em>fresh</em> Set ({@code NX} — Redis treats a key with no TTL as
   * +infinity, so {@code GT} alone would never set the initial TTL); on any pre-existing Set use
   * {@code GT} only, so a later finite write can neither shrink a longer-lived member's TTL nor
   * re-finite-ize a Set a read deliberately {@code PERSIST}ed.
   */
  private void applyTtlBackstop(String indexKey, Long expireAtMs, boolean fresh, long now) {
    // Skip a null, non-positive, OR already-elapsed horizon. PEXPIREAT with a past epoch under NX
    // sets an already-expired TTL on a Set that has none, which Redis executes as an immediate
    // delete of the whole Set — evicting every live co-member. Never shrink; leave the existing
    // TTL.
    if (expireAtMs == null || expireAtMs <= now) return;
    log.tracef("[redis] PEXPIREAT %s %s %s/GT", indexKey, expireAtMs, fresh ? "NX" : "");
    if (fresh) {
      jedis.pexpireAt(indexKey, expireAtMs, ExpiryOption.NX);
    }
    jedis.pexpireAt(indexKey, expireAtMs, ExpiryOption.GT);
  }

  // ---------------------------------------------------------------------------------------------
  // Read path: register reconciliation, flushed at commit
  // ---------------------------------------------------------------------------------------------

  /**
   * Register a secondary-index member to be {@code SREM}'d from its Set when this transaction
   * commits. Reads use this to self-heal <em>dangling</em> index members — those whose referenced
   * entity has expired/vanished — without issuing any Redis write on the read path.
   */
  @Override
  public void reapIndexMemberOnCommit(String indexKey, String member) {
    if (indexKey == null || member == null) return;
    pendingIndexReaps.computeIfAbsent(indexKey, k -> Sets.newHashSet()).add(member);
  }

  /**
   * Register that an index Set must remain live long enough to cover a <em>live</em> member found
   * on a by-index read, applied grow-only at commit. The write-path backstop is stamped from
   * whichever entity's write last touched the Set, so a longer-lived member that is never
   * re-written can be left under a TTL that expires before it does — dropping a live entity from
   * the index (reads only remove dangling members, they cannot restore a live one). A {@code null}
   * {@code coverUntilMs} means that member never expires, so the Set is {@code PERSIST}ed.
   */
  @Override
  public void extendIndexTtlOnCommit(String indexKey, Long coverUntilMs) {
    if (indexKey == null) return;
    long horizon = (coverUntilMs == null) ? PERSIST_INDEX_TTL : coverUntilMs;
    pendingIndexTtlExtensions.merge(
        indexKey,
        horizon,
        (a, b) ->
            (a == PERSIST_INDEX_TTL || b == PERSIST_INDEX_TTL)
                ? PERSIST_INDEX_TTL
                : Math.max(a, b));
  }

  /**
   * Fold the expirations of the live members a by-index read resolved into a single grow-only TTL
   * extension on the index Set: {@code PERSIST} when any live member never expires (a {@code null}
   * expiration), otherwise cover the longest-lived.
   */
  @Override
  public void extendIndexTtlToCoverLiveMembers(String indexKey, Collection<Long> liveExpirations) {
    if (indexKey == null || liveExpirations == null) return;
    boolean neverExpires = false;
    Long maxExpiration = null;
    for (Long expiration : liveExpirations) {
      if (expiration == null) {
        neverExpires = true;
      } else if (maxExpiration == null || expiration > maxExpiration) {
        maxExpiration = expiration;
      }
    }
    if (neverExpires) {
      extendIndexTtlOnCommit(indexKey, null);
    } else if (maxExpiration != null) {
      extendIndexTtlOnCommit(indexKey, maxExpiration);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Commit: entity pass, then the deferred reconciliation
  // ---------------------------------------------------------------------------------------------

  @Override
  protected void commitImpl() {
    if (cache.isEmpty()
        && toDelete.isEmpty()
        && pendingIndexReaps.isEmpty()
        && pendingIndexTtlExtensions.isEmpty()) {
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

    // Only now: a member (re)written above is live and must not be reaped, and the entity pass may
    // itself have scheduled reaps through lazy expiry.
    reapStaleIndexMembers();
    extendIndexTtls();
  }

  /**
   * Flush the members registered via {@link #reapIndexMemberOnCommit}. A member (re)written live in
   * this same transaction is skipped; each remaining candidate is re-checked against Redis and
   * dropped if its entity is present again — a member registered as dangling on read may have been
   * re-created by a concurrent transaction before this one commits (client-session keys are
   * deterministic, so the same member key can legitimately return), and reaping it then would
   * orphan a live entity from its index. Best-effort/fail-open — never fails the commit.
   */
  private void reapStaleIndexMembers() {
    if (pendingIndexReaps.isEmpty()) return;

    Set<String> liveMembers =
        cache.values().stream()
            .filter(m -> !m.isMarkedForDelete() && !toDelete.containsKey(m.getKey()))
            .map(m -> m.getKey().key())
            .collect(Collectors.toSet());

    Map<String, String[]> reaps = Maps.newLinkedHashMap();
    for (Map.Entry<String, Set<String>> e : pendingIndexReaps.entrySet()) {
      String[] members =
          e.getValue().stream().filter(m -> !liveMembers.contains(m)).toArray(String[]::new);
      if (members.length > 0) reaps.put(e.getKey(), members);
    }
    pendingIndexReaps.clear();
    if (reaps.isEmpty()) return;

    reaps = dropReappearedMembers(reaps);
    if (reaps.isEmpty()) return;

    // Per key, caught per key: a transient single-shard failure (MOVED during resharding, a
    // connection drop) must neither fail the commit nor abort the remaining keys' reaps.
    for (Map.Entry<String, String[]> e : reaps.entrySet()) {
      try {
        log.tracef("[redis] SREM %s (stale index reconciliation)", e.getKey());
        jedis.srem(e.getKey(), e.getValue());
        countOperation(SREM);
      } catch (Exception ex) {
        log.warnf(ex, "Failed to reap stale members from index %s", e.getKey());
      }
    }
  }

  /**
   * Re-check each reap candidate against Redis in one pipelined {@code EXISTS} and drop any whose
   * entity is present again. Fails open: if the check cannot run, nothing is reaped this commit
   * (the TTL backstop still bounds the Sets).
   */
  private Map<String, String[]> dropReappearedMembers(Map<String, String[]> reaps) {
    Set<String> candidates =
        reaps.values().stream().flatMap(Arrays::stream).collect(Collectors.toSet());
    Set<String> reappeared = Sets.newHashSet();
    try (AbstractPipeline pipeline = jedis.pipelined()) {
      Map<String, Response<Boolean>> exists = Maps.newLinkedHashMap();
      for (String member : candidates) {
        exists.put(member, pipeline.exists(member));
      }
      pipeline.sync();
      for (Map.Entry<String, Response<Boolean>> e : exists.entrySet()) {
        if (Boolean.TRUE.equals(e.getValue().get())) reappeared.add(e.getKey());
      }
    } catch (Exception ex) {
      log.warnf(ex, "Failed to re-verify stale index members; skipping reap this commit");
      return Maps.newLinkedHashMap();
    }
    if (reappeared.isEmpty()) return reaps;

    Map<String, String[]> verified = Maps.newLinkedHashMap();
    for (Map.Entry<String, String[]> e : reaps.entrySet()) {
      String[] survivors =
          Arrays.stream(e.getValue()).filter(m -> !reappeared.contains(m)).toArray(String[]::new);
      if (survivors.length > 0) verified.put(e.getKey(), survivors);
    }
    return verified;
  }

  /**
   * Flush the extensions registered via {@link #extendIndexTtlOnCommit}: grow each Set's TTL
   * ({@code GT}) to cover the longest-lived live member a read resolved from it, or {@code PERSIST}
   * the Set when a live member never expires. Best-effort/fail-open — never fails the commit.
   */
  private void extendIndexTtls() {
    if (pendingIndexTtlExtensions.isEmpty()) return;
    Map<String, Long> extensions = Maps.newLinkedHashMap(pendingIndexTtlExtensions);
    pendingIndexTtlExtensions.clear();

    long now = Time.currentTimeMillis();
    for (Map.Entry<String, Long> e : extensions.entrySet()) {
      try {
        applyTtlExtension(e.getKey(), e.getValue(), now);
      } catch (Exception ex) {
        log.warnf(ex, "Failed to extend TTL on index %s", e.getKey());
      }
    }
  }

  /**
   * {@code PERSIST} for a never-expiring member; otherwise {@code GT}-only — a read never creates
   * the Set, so it must only grow an existing TTL, never establish one (establishing with {@code
   * NX} on a TTL-less Set would re-finite-ize a persisted one, and {@code GT} is a no-op against
   * +inf).
   */
  private void applyTtlExtension(String indexKey, long coverUntilMs, long now) {
    if (coverUntilMs == PERSIST_INDEX_TTL) {
      log.tracef("[redis] PERSIST %s (live member never expires)", indexKey);
      jedis.persist(indexKey);
    } else if (coverUntilMs > now) {
      log.tracef("[redis] PEXPIREAT %s %s GT (cover live member)", indexKey, coverUntilMs);
      jedis.pexpireAt(indexKey, coverUntilMs, ExpiryOption.GT);
    } else {
      // Live at read time, elapsed by commit: it is expiring anyway and the reap path handles it.
      log.tracef("[redis] skip PEXPIREAT %s: horizon %s already elapsed", indexKey, coverUntilMs);
    }
  }
}
