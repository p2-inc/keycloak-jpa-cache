/*
 * Copyright 2023 IT-Systemhaus der Bundesagentur fuer Arbeit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.phasetwo.keycloak.redis.testsuite.session;

import static io.phasetwo.keycloak.redis.testsuite.session.SessionTestUtils.createClients;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.phasetwo.keycloak.redis.connection.RedisMode;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import io.phasetwo.keycloak.redis.userSession.RedisUserSessionProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import redis.clients.jedis.UnifiedJedis;

/**
 * Cluster-mode <b>self-reconciling index</b> acceptance tests (issue&nbsp;#78, Tier&nbsp;A).
 *
 * <p>Secondary-index Sets ({@code user-session:user-index:*}, etc.) accumulate <em>dangling</em>
 * members whenever a session hash TTL-expires without going through the explicit delete path: the
 * member string stays in the Set even though the entity it points to is gone. Reads already filter
 * these out of their results, but they never remove them from the Set, so it grows unbounded in
 * cluster mode (where the delete path cannot use a MULTI/EXEC across slots).
 *
 * <p>The feature makes reads <em>self-reconciling</em>: when a by-index lookup encounters a member
 * that resolves to no entity, it registers that member to be {@code SREM}'d from the Set it was
 * read from when the transaction commits (never on the read path itself). Reconciliation is a
 * {@code ClusterRedisChangelogTransaction} concern: standalone/sentinel keep {@code MULTI}/{@code
 * EXEC} index maintenance and carry none of it (the mode-neutral read-path behaviour lives in
 * {@code RedisIndexReadPathTest}). These tests verify the cluster wiring end-to-end, where the reap
 * is issued per-key because a cross-slot MULTI is impossible.
 *
 * <p>Runs against a real cluster-mode Jedis client via {@link ClusterTestSupport}.
 */
public class RedisClusterIndexReconciliationTest extends KeycloakModelTest {

  private static ClusterTestSupport.Handle cluster;
  private static UnifiedJedis clusterJedis;

  private String realmId;

  @BeforeClass
  public static void startCluster() throws Exception {
    cluster = ClusterTestSupport.start();
    clusterJedis = cluster.jedis;
  }

  @AfterClass
  public static void stopCluster() {
    ClusterTestSupport.stop(cluster);
    cluster = null;
    clusterJedis = null;
  }

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "test-cluster-reconcile");
    s.getContext().setRealm(realm);

    realm.setOfflineSessionIdleTimeout(Constants.DEFAULT_OFFLINE_SESSION_IDLE_TIMEOUT);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realm.setSsoSessionIdleTimeout(1800);
    realm.setSsoSessionMaxLifespan(36000);
    realm.setClientSessionIdleTimeout(500);
    this.realmId = realm.getId();

    s.users().addUser(realm, "user1").setEmail("user1@localhost");

    createClients(s, realm);
  }

  @Override
  public void cleanEnvironment(KeycloakSession s) {
    RealmModel realm = s.realms().getRealm(realmId);
    s.getContext().setRealm(realm);
    s.realms().removeRealm(realmId);
    // Flush the shared single-node cluster between methods so index Sets and session hashes don't
    // accumulate across tests: removeRealm drives the factory provider's own Valkey, not this
    // clusterJedis, so without this the container's state bleeds across methods and only freshly
    // generated ids keep the tests order-independent. Mirrors the standalone suites.
    clusterJedis.flushAll();
  }

  /**
   * A stale user-index member (its session hash has TTL-expired) is removed from the index Set the
   * first time the index is read by user, and the lookup returns nothing.
   *
   * <p>RED (before the fix): the read correctly returns empty, but the dead member is left in the
   * Set, so the final {@code SMEMBERS} assertion fails. GREEN: the read {@code SREM}s the dead
   * member and the Set is empty.
   */
  /**
   * Creates one user session for {@code user1} through a real cluster-mode provider (SADDs into the
   * user-index on transaction commit) and returns that user's id.
   */
  private String createUserSessionReturningUserId() {
    return withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
          provider.createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null);
          return user.getId();
        });
  }

  @Test
  public void testStaleUserIndexMemberReconciledOnRead() {
    String userId = createUserSessionReturningUserId();

    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // Precondition: exactly one live member in the index.
    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), hasSize(1));

    // Simulate TTL-expiry of the session hash, leaving the member dangling in the index Set.
    int expired = ClusterTestSupport.expireIndexedEntities(clusterJedis, indexKey);
    assertThat(expired, is(1));
    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), hasSize(1));

    // Read by user: returns nothing (member resolves to no entity) AND schedules the self-heal.
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
          List<String> found =
              provider
                  .getUserSessionsStream(realm, user)
                  .map(UserSessionModel::getId)
                  .collect(Collectors.toList());
          assertThat(found, is(empty()));
          return null;
        });

    // The dead member must have been reconciled (SREM'd) out of the index Set at transaction
    // commit.
    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), is(empty()));
  }

  /**
   * Every index Set written in cluster mode carries a positive TTL backstop, so members can never
   * outlive the sessions that reference them even if a read never revisits the Set.
   *
   * <p>RED (before the fix): the write path issues only {@code SADD}, so {@code PTTL} is {@code -1}
   * (no expiry) and this fails. GREEN: {@code PEXPIREAT ... GT} gives the Set a positive TTL.
   */
  @Test
  public void testUserIndexHasTtlBackstopInCluster() {
    String userId = createUserSessionReturningUserId();
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), hasSize(1));

    long pttl = ClusterTestSupport.pttl(clusterJedis, indexKey);
    assertThat(
        "index Set must carry a positive TTL backstop in cluster mode (was " + pttl + ")",
        pttl > 0L,
        is(true));
  }

  /**
   * The TTL backstop is <b>grow-only</b>: because one index Set aggregates many sessions with
   * different expiries, a later write must never shorten a TTL that already covers a longer-lived
   * member. This guards the {@code GT} flag — a naive unconditional {@code PEXPIREAT} would shrink
   * the TTL to the newest session's (shorter) expiration and fail here.
   */
  @Test
  public void testIndexTtlBackstopIsGrowOnlyInCluster() {
    String userId = createUserSessionReturningUserId();
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // Force a far-future TTL (+30d), larger than any session lifespan in this realm.
    long farFutureMs = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
    clusterJedis.pexpireAt(indexKey, farFutureMs);
    assertThat(ClusterTestSupport.pttl(clusterJedis, indexKey) > 0L, is(true));

    // A second session for the same user: its (much shorter) expiration must NOT shrink the TTL.
    createUserSessionReturningUserId();

    long after = ClusterTestSupport.pttl(clusterJedis, indexKey);
    long twentyFiveDaysMs = 25L * 24 * 60 * 60 * 1000;
    assertThat(
        "index TTL must not shrink below a longer-lived member (GT); was " + after,
        after > twentyFiveDaysMs,
        is(true));
  }

  /**
   * In cluster mode too, a member registered as dangling on read but re-created before the
   * transaction commits must NOT be reaped (the commit-time re-verify runs over a ClusterPipeline).
   */
  @Test
  public void doesNotReapMemberThatReappearedBeforeCommitInCluster() {
    String userId = createUserSessionReturningUserId();
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    String member = ClusterTestSupport.members(clusterJedis, indexKey).iterator().next();

    // Plant a dangling member: session hash gone, index member left behind.
    assertThat(ClusterTestSupport.expireIndexedEntities(clusterJedis, indexKey), is(1));

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(0L));
          // Concurrent recreate before commit: the same member key returns.
          clusterJedis.hset(member, "id", "resurrected");
          return null;
        });

    assertThat(
        "a reaped member that reappeared before commit must survive in cluster mode",
        ClusterTestSupport.members(clusterJedis, indexKey),
        hasSize(1));
  }

  /** A syntactically-valid client-session index member that resolves to no entity (dangling). */
  private static String danglingClientSessionMember() {
    return "authenticated-client:" + java.util.UUID.randomUUID();
  }

  /**
   * A stale <b>client-index</b> member (whose client-session hash is gone) is reconciled out of the
   * Set when sessions are read by client via {@code getUserSessionsStream(realm, client)}.
   *
   * <p>RED (before the fix): the read returns nothing but leaves the dead member in the
   * client-index Set. GREEN: the read schedules it for reap and it is {@code SREM}'d at commit.
   */
  @Test
  public void testStaleClientIndexMemberReconciledOnRead() {
    String clientUuid =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());
    String indexKey = ClusterTestSupport.clientIndexKey(clientUuid);

    // Seed a dangling client-index member pointing at a non-existent client-session hash.
    clusterJedis.sadd(indexKey, danglingClientSessionMember());
    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), hasSize(1));

    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
          assertThat(provider.getUserSessionsStream(realm, client).count(), is(0L));
          return null;
        });

    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), is(empty()));
  }

  /**
   * A stale <b>parent-index</b> member (whose client-session hash is gone) is reconciled out of the
   * Set when a user session enumerates its client sessions via {@code
   * UserSessionModel.getAuthenticatedClientSessions()} (in the adapter).
   *
   * <p>RED (before the fix): enumeration returns nothing but leaves the dead member in the
   * parent-index Set. GREEN: enumeration schedules it for reap and it is {@code SREM}'d at commit.
   */
  @Test
  public void testStaleParentIndexMemberReconciledOnRead() {
    // A real user session to enumerate client sessions on.
    String userSessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
              return provider
                  .createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null)
                  .getId();
            });
    String indexKey = ClusterTestSupport.parentIndexKey(userSessionId);

    // Seed a dangling parent-index member pointing at a non-existent client-session hash.
    clusterJedis.sadd(indexKey, danglingClientSessionMember());
    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), hasSize(1));

    withRealm(
        realmId,
        (s, realm) -> {
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, clusterJedis, RedisMode.CLUSTER);
          UserSessionModel userSession = provider.getUserSession(realm, userSessionId);
          assertThat(userSession, notNullValue());
          assertThat(userSession.getAuthenticatedClientSessions().size(), is(0));
          return null;
        });

    assertThat(ClusterTestSupport.members(clusterJedis, indexKey), is(empty()));
  }
}
