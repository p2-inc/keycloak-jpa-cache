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

import io.phasetwo.keycloak.redis.RedisHashCas;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import io.phasetwo.keycloak.redis.userSession.RedisUserSessionProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

/**
 * Eventual-consistency backstop tests for {@link RedisMode#CLUSTER} (issue&nbsp;#78).
 *
 * <p>In cluster mode an entity key and its index Sets hash to different slots, so a {@code MULTI}
 * spanning them is impossible and every command is issued on its own. That leaves gaps in which an
 * index Set can hold a member whose entity is gone — a crash between {@code DEL} and {@code SREM},
 * or a session hash that TTL-expires without ever going through the explicit delete path. {@code
 * ClusterRedisChangelogTransaction} therefore carries a TTL backstop on every index Set plus
 * read-driven reconciliation (reap of dangling members and TTL extension for live ones, both
 * deferred to commit). Standalone/sentinel keep {@code MULTI}/{@code EXEC} index maintenance and
 * none of this machinery.
 *
 * <p>The mode enum alone selects the transaction, so a single standalone Valkey node is enough to
 * exercise the cluster transaction's behaviour here; the real cluster-client wiring (a {@code
 * ClusterPipeline}, per-slot routing) is covered by {@code RedisClusterIndexReconciliationTest}.
 */
public class RedisClusterIndexBackstopTest extends KeycloakModelTest {

  private static GenericContainer<?> container;
  private static UnifiedJedis jedis;

  private String realmId;

  @BeforeClass
  public static void startStandalone() {
    container =
        new GenericContainer<>("valkey/valkey:8.1.5")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    container.start();

    JedisClientConfig clientConfig =
        DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(5000)
            .socketTimeoutMillis(5000)
            .build();
    jedis =
        RedisClient.builder()
            .hostAndPort(new HostAndPort(container.getHost(), container.getMappedPort(6379)))
            .clientConfig(clientConfig)
            .build();

    RedisHashCas.initialize(jedis);
  }

  @AfterClass
  public static void stopStandalone() {
    if (jedis != null) {
      jedis.close();
      jedis = null;
    }
    if (container != null) {
      container.stop();
      container = null;
    }
  }

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "test-cluster-backstop");
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
    // Flush the shared container between methods so index Sets don't accumulate across tests (each
    // method seeds its own state); keeps the class hermetic and fast.
    jedis.flushAll();
  }

  @Test
  public void writesTtlBackstopOnIndexSet() {
    assertTtlBackstopWritten(RedisMode.CLUSTER);
  }

  /**
   * The index Set written by the provider carries a positive TTL backstop derived from the
   * referencing session's expiration, so dangling members cannot accumulate unbounded even for Sets
   * that reads never revisit.
   */
  private void assertTtlBackstopWritten(RedisMode mode) {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider = new RedisUserSessionProvider(s, jedis, mode);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    long pttl = ClusterTestSupport.pttl(jedis, indexKey);
    assertThat(
        "index Set must carry a positive TTL backstop (" + mode + ", was " + pttl + ")",
        pttl > 0L,
        is(true));
  }

  @Test
  public void reapsDanglingMemberAtCommitNotMidRead() {
    assertReapsAtCommit(RedisMode.CLUSTER);
  }

  /**
   * A by-index read that encounters a dangling member self-reconciles — but the reap is deferred to
   * transaction commit, never issued on the read path (the "no Redis writes outside {@code
   * commitImpl}" discipline). Proven by inspecting the Set both <em>during</em> the read
   * transaction (member still present) and <em>after</em> it commits (member gone).
   */
  private void assertReapsAtCommit(RedisMode mode) {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider = new RedisUserSessionProvider(s, jedis, mode);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    // Plant a dangling member: session hash gone, index member left behind.
    assertThat(ClusterTestSupport.expireIndexedEntities(jedis, indexKey), is(1));
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider = new RedisUserSessionProvider(s, jedis, mode);
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(0L));

          // Still inside the transaction: reconciliation must be deferred, not written on read.
          assertThat(
              "reconciliation must not mutate the index on the read path (" + mode + ")",
              ClusterTestSupport.members(jedis, indexKey),
              hasSize(1));
          return null;
        });

    // The transaction has committed: the dangling member is reaped.
    assertThat(
        "dangling member must be reaped at commit (" + mode + ")",
        ClusterTestSupport.members(jedis, indexKey),
        is(empty()));
  }

  @Test
  public void doesNotReapMemberThatReappearedBeforeCommit() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    String member = ClusterTestSupport.members(jedis, indexKey).iterator().next();

    // Plant a dangling member: session hash gone, index member left behind.
    assertThat(ClusterTestSupport.expireIndexedEntities(jedis, indexKey), is(1));

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          // The read registers a reap for the dangling member ...
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(0L));
          // ... but before this transaction commits, a concurrent write re-creates the entity
          // (client-session keys are deterministic, so the same member key can legitimately
          // return).
          jedis.hset(member, "id", "resurrected");
          return null;
        });

    // The reap must be skipped because the entity reappeared: the member stays in the index,
    // otherwise a live session would be orphaned from all by-index reads.
    assertThat(
        "a reaped member that reappeared before commit must not be SREM'd",
        ClusterTestSupport.members(jedis, indexKey),
        hasSize(1));
  }

  @Test
  public void readReturnsLiveAndReapsOnlyDeadMembers() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    java.util.Set<String> initialMembers = ClusterTestSupport.members(jedis, indexKey);
    assertThat(initialMembers, hasSize(2));

    // Dangle exactly ONE of the two members; the other stays live.
    String dead = initialMembers.iterator().next();
    String live = initialMembers.stream().filter(m -> !m.equals(dead)).findFirst().orElseThrow();
    assertThat(jedis.del(dead), is(1L));

    List<String> found =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              return provider
                  .getUserSessionsStream(realm, user)
                  .map(UserSessionModel::getId)
                  .collect(Collectors.toList());
            });

    // The read returns only the live session, never the dangling one.
    assertThat(found, hasSize(1));
    assertThat(
        "read must not return the dangling member",
        ("user-session:" + found.get(0)).equals(dead),
        is(false));

    // After commit exactly the dead member was reaped; the live member survives.
    java.util.Set<String> after = ClusterTestSupport.members(jedis, indexKey);
    assertThat("only the dead member is reaped, the live one is kept", after, hasSize(1));
    assertThat("the dead member must be reaped from the index", after.contains(dead), is(false));
    assertThat("the live member must remain in the index", after.contains(live), is(true));
  }

  @Test
  public void indexTtlBackstopIsGrowOnly() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // Force a far-future TTL (+30d), larger than any session lifespan in this realm.
    long farFutureMs = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
    jedis.pexpireAt(indexKey, farFutureMs);
    assertThat(ClusterTestSupport.pttl(jedis, indexKey) > 0L, is(true));

    // A second (much shorter-lived) session for the same user must NOT shrink the TTL (GT).
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          provider.createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null);
          return null;
        });

    long after = ClusterTestSupport.pttl(jedis, indexKey);
    long twentyFiveDaysMs = 25L * 24 * 60 * 60 * 1000;
    assertThat(
        "index TTL must not shrink below a longer-lived member (GT); was " + after,
        after > twentyFiveDaysMs,
        is(true));
  }

  @Test
  public void doesNotReapMemberWrittenLiveInSameTransaction() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    String member = ClusterTestSupport.members(jedis, indexKey).iterator().next();
    String sessionId = member.substring("user-session:".length());

    // Dangle the member.
    assertThat(jedis.del(member), is(1L));

    // One transaction: a read schedules the reap, then the SAME session id is re-created live.
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          provider.getUserSessionsStream(realm, user).count(); // registers reap for the member
          provider.createUserSession(
              sessionId,
              realm,
              user,
              "user1",
              "127.0.0.1",
              "form",
              true,
              null,
              null,
              UserSessionModel.SessionPersistenceState.PERSISTENT);
          return null;
        });

    // The member must survive: a session written live in the same transaction is never reaped.
    assertThat(
        "a member re-created live in the same transaction must not be reaped",
        ClusterTestSupport.members(jedis, indexKey),
        hasSize(1));
  }

  @Test
  public void clientIndexMemberReapedAtCommit() {
    String clientUuid =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());
    String indexKey = ClusterTestSupport.clientIndexKey(clientUuid);

    jedis.sadd(indexKey, "authenticated-client:" + UUID.randomUUID());
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          assertThat(provider.getUserSessionsStream(realm, client).count(), is(0L));
          return null;
        });

    assertThat(ClusterTestSupport.members(jedis, indexKey), is(empty()));
  }

  @Test
  public void parentIndexMemberReapedAtCommit() {
    String userSessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              return provider
                  .createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null)
                  .getId();
            });
    String indexKey = ClusterTestSupport.parentIndexKey(userSessionId);

    jedis.sadd(indexKey, "authenticated-client:" + UUID.randomUUID());
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    withRealm(
        realmId,
        (s, realm) -> {
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          UserSessionModel userSession = provider.getUserSession(realm, userSessionId);
          assertThat(userSession.getAuthenticatedClientSessions().size(), is(0));
          return null;
        });

    assertThat(ClusterTestSupport.members(jedis, indexKey), is(empty()));
  }

  @Test
  public void offlineSessionIndexCarriesLongerTtlBackstop() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              UserSessionModel us =
                  provider.createUserSession(
                      realm, user, "user1", "127.0.0.1", "form", true, null, null);
              provider.createOfflineUserSession(us);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // The offline session's far horizon (offline idle timeout, ~30d) must have grown the index TTL
    // well past the online max lifespan (36000s), proving the backstop derives from the offline
    // entity's expiration too.
    long onlineMaxLifespanMs = 36000L * 1000;
    long pttl = ClusterTestSupport.pttl(jedis, indexKey);
    assertThat(
        "offline session must extend the index TTL past the online horizon; was " + pttl,
        pttl > onlineMaxLifespanMs,
        is(true));
  }

  @Test
  public void getOfflineSessionsCountSkipsClientSessionWithDanglingParent() {
    String clientId =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());

    // Seed a LIVE client session (resolves fine) whose parent user session does NOT exist, so
    // getUserSession() returns null. Building it through the provider is impossible in this harness
    // (the client adapter's getUserSession delegates to the factory provider, not this jedis), so
    // the hash is written directly.
    String danglingUserSessionId = UUID.randomUUID().toString();
    String csId = danglingUserSessionId + "::" + clientId;
    String csKey = "authenticated-client:" + csId;
    Map<String, String> data = new HashMap<>();
    data.put("id", csId);
    data.put("parentId", danglingUserSessionId);
    data.put("realmId", realmId);
    data.put("clientUuid", clientId);
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    data.put("expiration", String.valueOf(System.currentTimeMillis() + 3_600_000L));
    data.put("version", "0");
    data.put("offline", "true");
    jedis.hset(csKey, data);
    jedis.sadd(ClusterTestSupport.clientIndexKey(clientId), csKey);

    long count =
        withRealm(
            realmId,
            (s, realm) -> {
              ClientModel client = realm.getClientByClientId("test-app");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              // Must not NPE when a resolved client session's parent user session is null.
              return provider.getOfflineSessionsCount(realm, client);
            });
    assertThat(count, is(0L));
  }

  /**
   * The multi-index {@code getUserSessionsStreamByIndexKey(String[], ...)} path has no public
   * caller today (every caller passes a single key), so it is exercised here directly (via
   * reflection): reading two index Sets that share the same members returns the live members
   * de-duplicated, and a dangling member is reaped from the index it was read from.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void multiIndexReadDeduplicatesLiveMembersAndReapsDead() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String userIndex = ClusterTestSupport.userIndexKey(userId);
    java.util.List<String> live =
        new java.util.ArrayList<>(ClusterTestSupport.members(jedis, userIndex));
    assertThat(live, hasSize(2));

    // A second index Set that shares one live member with the user-index, so the two Sets overlap.
    String indexB = "user-session:user-index:multi-" + userId;
    jedis.sadd(indexB, live.get(0));
    // A dangling member only in the user-index.
    String dead = "user-session:" + UUID.randomUUID();
    jedis.sadd(userIndex, dead);

    List<String> ids =
        withRealm(
            realmId,
            (s, realm) -> {
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              try {
                java.lang.reflect.Method m =
                    RedisUserSessionProvider.class.getDeclaredMethod(
                        "getUserSessionsStreamByIndexKey",
                        String[].class,
                        RealmModel.class,
                        boolean.class);
                m.setAccessible(true);
                java.util.stream.Stream<UserSessionModel> stream =
                    (java.util.stream.Stream<UserSessionModel>)
                        m.invoke(provider, (Object) new String[] {userIndex, indexB}, realm, false);
                return stream.map(UserSessionModel::getId).collect(Collectors.toList());
              } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
              }
            });

    // Both live sessions are returned once each, even though one appears in both index Sets.
    assertThat(ids, hasSize(2));
    assertThat(
        "live members are de-duplicated across indexes", ids.stream().distinct().count(), is(2L));

    // The dangling member is reaped from the index it came from; live members are untouched.
    assertThat(ClusterTestSupport.members(jedis, userIndex), hasSize(2));
    assertThat(ClusterTestSupport.members(jedis, userIndex).contains(dead), is(false));
    assertThat(ClusterTestSupport.members(jedis, indexB), hasSize(1));
  }

  /**
   * A shared index Set's TTL backstop is derived from whichever session's write last stamped it, so
   * a longer-lived member that is never re-written can be left under a TTL that expires before it
   * does — dropping a live session from the index. A by-index read sees the live member and must
   * grow the Set TTL ({@code GT}) to cover its expiration (issue&nbsp;#78, review
   * finding A).
   */
  @Test
  public void readGrowsUserIndexTtlToCoverLongerLivedLiveMember() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });
    String indexKey = ClusterTestSupport.userIndexKey(userId);
    String member = ClusterTestSupport.members(jedis, indexKey).iterator().next();

    // Under-cover the Set: force its TTL to HALF the live member's remaining horizon.
    long memberExpMs = Long.parseLong(jedis.hget(member, "expiration"));
    long horizonMs = memberExpMs - System.currentTimeMillis();
    jedis.pexpireAt(indexKey, System.currentTimeMillis() + horizonMs / 2);

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(1L));
          return null;
        });

    long after = ClusterTestSupport.pttl(jedis, indexKey);
    assertThat(
        "read must grow the index TTL back to the live member's horizon; was " + after,
        after > (horizonMs * 3) / 4,
        is(true));
  }

  /**
   * A live member whose entity never expires ({@code getExpiration() == null}) cannot be protected
   * by any finite TTL. When a by-index read finds such a member co-tenant in a Set carrying a
   * finite TTL (stamped by a shorter-lived session), the Set must be persisted (TTL removed) so it
   * never expires out from under the never-expiring member (issue&nbsp;#78, review finding A).
   */
  @Test
  public void readPersistsUserIndexTtlWhenLiveMemberNeverExpires() {
    String userId =
        withRealm(realmId, (s, realm) -> s.users().getUserByUsername(realm, "user1").getId());
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // Plant a LIVE, never-expiring member: no "expiration" field -> getExpiration() == null.
    String member = "user-session:" + UUID.randomUUID();
    Map<String, String> data = new HashMap<>();
    data.put("id", member.substring("user-session:".length()));
    data.put("realmId", realmId);
    data.put("userId", userId);
    data.put("offline", "false");
    data.put("version", "0");
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    jedis.hset(member, data);
    jedis.sadd(indexKey, member);
    // A shorter-lived co-tenant would have stamped a finite TTL on the shared Set.
    jedis.pexpireAt(indexKey, System.currentTimeMillis() + 5L * 60 * 1000);
    assertThat(ClusterTestSupport.pttl(jedis, indexKey) > 0L, is(true));

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(1L));
          return null;
        });

    long after = ClusterTestSupport.pttl(jedis, indexKey);
    assertThat(
        "index Set must be persisted (no TTL) when a live member never expires; pttl was " + after,
        after,
        is(-1L));
  }

  /**
   * The by-client read path self-heals the same way: a live member found in the
   * authenticated-client:client-index Set must grow that Set's TTL ({@code GT}) to cover the
   * member's expiration (issue&nbsp;#78, review finding A).
   */
  @Test
  public void readGrowsClientIndexTtlToCoverLiveMember() {
    String clientUuid =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());
    String indexKey = ClusterTestSupport.clientIndexKey(clientUuid);

    // Plant a LIVE authenticated-client member with a far-future (~1h) expiration.
    long memberExpMs = System.currentTimeMillis() + 60L * 60 * 1000;
    String csId = UUID.randomUUID() + "::" + clientUuid;
    String csKey = "authenticated-client:" + csId;
    Map<String, String> data = new HashMap<>();
    data.put("id", csId);
    data.put("parentId", UUID.randomUUID().toString());
    data.put("realmId", realmId);
    data.put("clientUuid", clientUuid);
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    data.put("expiration", String.valueOf(memberExpMs));
    data.put("version", "0");
    data.put("offline", "false");
    jedis.hset(csKey, data);
    jedis.sadd(indexKey, csKey);

    // Under-cover the Set with a short (5 min) TTL.
    jedis.pexpireAt(indexKey, System.currentTimeMillis() + 5L * 60 * 1000);

    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          provider.getUserSessionsStream(realm, client).count(); // drives client-index read
          return null;
        });

    long after = ClusterTestSupport.pttl(jedis, indexKey);
    long thirtyMinutesMs = 30L * 60 * 1000;
    assertThat(
        "read must grow the client-index TTL to cover the live member; was " + after,
        after > thirtyMinutesMs,
        is(true));
  }

  /**
   * The parent-index read path ({@code RedisUserSessionAdapter.getAuthenticatedClientSessions})
   * self-heals the TTL exactly like the provider's user-index/client-index reads: a live
   * authenticated-client member found in the {@code authenticated-client:parent-index} Set must
   * grow that Set's TTL ({@code GT}) to cover the member's expiration, so a longer-lived client
   * session that is never re-written cannot be stranded under a shorter co-tenant's TTL and dropped
   * from the parent-index (issue&nbsp;#78, review finding&nbsp;A).
   *
   * <p>The adapter resolves each child's parent via {@code session.sessions()} — the
   * factory-registered provider on its <em>own</em> Valkey (see {@code RedisParameters}), not this
   * test's {@code jedis} — inside {@code matchingOfflineFlag}. A member whose parent cannot resolve
   * there NPEs before the filters, so the planted member's {@code parentId} points at a real user
   * session created through {@code session.sessions()}; the member hash itself lives in this test's
   * {@code jedis} so {@code getIfPresent} resolves it as a live member.
   */
  @Test
  public void readGrowsParentIndexTtlToCoverLiveMember() {
    String clientUuid =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());

    // A parent user session the child's getUserSession() can resolve. It must live in the
    // factory-registered provider's Redis, so create it through session.sessions() (online, so
    // matchingOfflineFlag matches the online enclosing session).
    String resolvableParentId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              return s.sessions()
                  .createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null)
                  .getId();
            });

    // The user session whose parent-index we exercise, written to THIS test's jedis via the manual
    // provider so we can inspect and manipulate the Set directly.
    String userSessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              return provider
                  .createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null)
                  .getId();
            });
    String indexKey = ClusterTestSupport.parentIndexKey(userSessionId);

    // Plant a LIVE authenticated-client member (~1h horizon) whose parent and client both resolve,
    // so it survives resolution and the filters instead of being reaped.
    long memberExpMs = System.currentTimeMillis() + 60L * 60 * 1000;
    String csId = resolvableParentId + "::" + clientUuid;
    String csKey = "authenticated-client:" + csId;
    Map<String, String> data = new HashMap<>();
    data.put("id", csId);
    data.put("parentId", resolvableParentId);
    data.put("realmId", realmId);
    data.put("clientUuid", clientUuid);
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    data.put("expiration", String.valueOf(memberExpMs));
    data.put("version", "0");
    data.put("offline", "false");
    jedis.hset(csKey, data);
    jedis.sadd(indexKey, csKey);

    // Under-cover the Set with a short (5 min) TTL, as a shorter-lived co-tenant would have
    // stamped.
    jedis.pexpireAt(indexKey, System.currentTimeMillis() + 5L * 60 * 1000);

    withRealm(
        realmId,
        (s, realm) -> {
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          UserSessionModel userSession = provider.getUserSession(realm, userSessionId);
          userSession.getAuthenticatedClientSessions(); // drives the parent-index read
          return null;
        });

    long after = ClusterTestSupport.pttl(jedis, indexKey);
    long thirtyMinutesMs = 30L * 60 * 1000;
    assertThat(
        "read must grow the parent-index TTL to cover the live member; was " + after,
        after > thirtyMinutesMs,
        is(true));
  }

  /**
   * A by-index read resolves its members through {@code RedisChangelogTransaction.getAll}. When a
   * session was cached earlier in the same transaction while valid and has since crossed its
   * expiration, {@code getAll} must not hand it back as live — it has to apply the same {@code
   * expired()} re-check {@code getIfPresent} does, otherwise the read counts an expired session as
   * active, never reaps it, and even extends the index Set's TTL to cover it (issue&nbsp;#78
   * review).
   */
  @Test
  public void getAllDoesNotReturnCachedEntryThatHasSinceExpired() {
    String userId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              provider.createUserSession(
                  realm, user, "user1", "127.0.0.1", "form", true, null, null);
              return user.getId();
            });

    try {
      long count =
          withRealm(
              realmId,
              (s, realm) -> {
                UserModel user = s.users().getUserByUsername(realm, "user1");
                // One provider instance => one shared transaction cache across both reads.
                RedisUserSessionProvider provider =
                    new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);

                // First read caches the (still-valid) session in the transaction.
                assertThat(provider.getUserSessionsStream(realm, user).count(), is(1L));

                // Fast-forward past every online horizon (idle 1800s, max lifespan 36000s) so the
                // cached model is now expired.
                Time.setOffset(40_000);

                // Second read resolves via getAll's cache-hit branch: the expired-but-cached
                // session
                // must not be returned as live.
                return provider.getUserSessionsStream(realm, user).count();
              });

      assertThat("getAll must not return a cached session that has since expired", count, is(0L));
    } finally {
      Time.setOffset(0);
    }
  }

  /**
   * A read schedules the index-TTL extension to commit ({@code extendIndexTtlOnCommit}) using the
   * horizon of a member that was live <em>at read time</em>. If real time crosses that horizon
   * before the transaction commits (a long request, a short client-session lifespan, or clock
   * skew), a naive {@code PEXPIREAT <pastEpoch> NX} on a Set that currently has <em>no</em> TTL
   * would set an already-expired TTL and make Redis delete the whole Set — evicting every live
   * co-member, including ones a read could never restore. The commit-time guard must skip a horizon
   * that has already elapsed and leave the Set intact (issue&nbsp;#78 review finding&nbsp;1).
   *
   * <p>The horizon is a real epoch that Redis evaluates against its own clock, so the test uses a
   * short real horizon and sleeps past it between the read and the commit — {@code Time.setOffset}
   * would move only Keycloak's clock, not Redis's, and never trigger the wipe.
   */
  @Test
  public void readSchedulingAnElapsedHorizonDoesNotWipeTheIndexSet() {
    String userId =
        withRealm(realmId, (s, realm) -> s.users().getUserByUsername(realm, "user1").getId());
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // A LIVE member whose horizon is only ~3s out: live when the read resolves it, but elapsed by
    // the time the transaction commits (below). The Set carries NO TTL yet — the vulnerable state
    // where a past-epoch NX would fire and delete it.
    long memberExpMs = System.currentTimeMillis() + 3_000L;
    String member = "user-session:" + UUID.randomUUID();
    Map<String, String> data = new HashMap<>();
    data.put("id", member.substring("user-session:".length()));
    data.put("realmId", realmId);
    data.put("userId", userId);
    data.put("offline", "false");
    data.put("version", "0");
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    data.put("expiration", String.valueOf(memberExpMs));
    jedis.hset(member, data);
    jedis.sadd(indexKey, member);
    assertThat(
        "Set must start with no TTL so NX would fire",
        ClusterTestSupport.pttl(jedis, indexKey),
        is(-1L));

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          // Read resolves the live member and schedules extendIndexTtlOnCommit(indexKey,
          // memberExpMs).
          assertThat(provider.getUserSessionsStream(realm, user).count(), is(1L));
          // Real time crosses the scheduled horizon before this transaction commits.
          try {
            Thread.sleep(3_500L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          return null;
        });

    // The Set (and its live member) must survive: a past-epoch PEXPIREAT NX would have deleted it.
    assertThat(
        "an elapsed TTL horizon must never wipe the index Set (issue #78 review finding 1)",
        ClusterTestSupport.members(jedis, indexKey),
        hasSize(1));
  }

  /**
   * {@code getAuthenticatedClientSessions} resolves a live client-session member and then filters
   * by offline flag via {@code cs.getUserSession().isOffline()}. If that member's parent user
   * session has expired/vanished (getUserSession() returns null), the unguarded dereference NPEs
   * and aborts the whole read (and getActiveClientSessionStats). The orphan must be filtered out
   * instead (issue #78 review). The child resolves its parent via {@code session.sessions()} (the
   * factory-registered provider's Redis), so a parentId that resolves nowhere yields a null parent.
   */
  @Test
  public void getAuthenticatedClientSessionsSkipsOrphanedClientSessionWithNullParent() {
    String clientUuid =
        withRealm(realmId, (s, realm) -> realm.getClientByClientId("test-app").getId());
    String userSessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel user = s.users().getUserByUsername(realm, "user1");
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              return provider
                  .createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null)
                  .getId();
            });
    String indexKey = ClusterTestSupport.parentIndexKey(userSessionId);

    // A LIVE client-session member whose parent user session does not exist anywhere -> its
    // getUserSession() resolves to null.
    String danglingParentId = UUID.randomUUID().toString();
    long memberExpMs = System.currentTimeMillis() + 60L * 60 * 1000;
    String csId = danglingParentId + "::" + clientUuid;
    String csKey = "authenticated-client:" + csId;
    Map<String, String> data = new HashMap<>();
    data.put("id", csId);
    data.put("parentId", danglingParentId);
    data.put("realmId", realmId);
    data.put("clientUuid", clientUuid);
    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    data.put("expiration", String.valueOf(memberExpMs));
    data.put("version", "0");
    data.put("offline", "false");
    jedis.hset(csKey, data);
    jedis.sadd(indexKey, csKey);

    int size =
        withRealm(
            realmId,
            (s, realm) -> {
              RedisUserSessionProvider provider =
                  new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
              UserSessionModel us = provider.getUserSession(realm, userSessionId);
              // Must not NPE when a live client session's parent user session is null.
              return us.getAuthenticatedClientSessions().size();
            });

    assertThat(
        "an orphaned client session (null parent) must be filtered, not NPE the read", size, is(0));
  }

  /**
   * A read that finds a never-expiring live member {@code PERSIST}s the index Set (strips its TTL)
   * so a finite TTL can't evict the immortal member. A later finite co-tenant write must then NOT
   * re-stamp a TTL onto that persisted Set — {@code PEXPIREAT ... NX} fires on any TTL-less key,
   * including a deliberately persisted one, and the finite horizon it stamps would later evict the
   * whole Set (and the still-live never-expiring member) from by-index reads. The write-path
   * backstop must {@code GT}-grow an already-existing Set, never re-establish a TTL on it (issue
   * #78 review).
   */
  @Test
  public void finiteCoTenantWriteDoesNotRefiniteizeAPersistedIndexSet() {
    String userId =
        withRealm(realmId, (s, realm) -> s.users().getUserByUsername(realm, "user1").getId());
    String indexKey = ClusterTestSupport.userIndexKey(userId);

    // A first session establishes the Set.
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          provider.createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null);
          return null;
        });
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(1));

    // Simulate the read-path having found a never-expiring live member and PERSISTed the Set.
    jedis.persist(indexKey);
    assertThat(
        "precondition: the Set is persisted (no TTL)",
        ClusterTestSupport.pttl(jedis, indexKey),
        is(-1L));

    // A finite co-tenant write to the SAME index Set must not stamp a TTL back onto it.
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel user = s.users().getUserByUsername(realm, "user1");
          RedisUserSessionProvider provider =
              new RedisUserSessionProvider(s, jedis, RedisMode.CLUSTER);
          provider.createUserSession(realm, user, "user1", "127.0.0.1", "form", true, null, null);
          return null;
        });

    assertThat(
        "a finite co-tenant write must not re-finite-ize a persisted index Set (issue #78)",
        ClusterTestSupport.pttl(jedis, indexKey),
        is(-1L));
  }
}
