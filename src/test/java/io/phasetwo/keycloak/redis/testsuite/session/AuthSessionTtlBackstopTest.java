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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.phasetwo.keycloak.redis.RedisHashCas;
import io.phasetwo.keycloak.redis.authSession.RedisAuthenticationSessionProvider;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import java.util.Set;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

/**
 * The child authentication-session hash ({@code auth-session:<clientId>:<tabId>}) must carry a TTL
 * backstop just like its root ({@code root-auth-session:*}) does. The adapter did not implement
 * {@link io.phasetwo.keycloak.common.ExpirableEntity}, so the write path computed a {@code null}
 * expiration and skipped {@code PEXPIREAT} — leaving every {@code auth-session:*} key with no TTL
 * and accumulating unbounded (observed as ~110&nbsp;MB of never-expiring keys on a live
 * deployment). See the discussion on issue&nbsp;#78.
 *
 * <p>Runs as {@link RedisMode#CLUSTER}: the index-Set backstop and reap-at-commit are {@code
 * ClusterRedisChangelogTransaction} behaviours (standalone/sentinel carry none of them). The mode
 * enum alone selects that transaction, so a standalone container backing the client suffices.
 */
public class AuthSessionTtlBackstopTest extends KeycloakModelTest {

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
    RealmModel realm = createRealm(s, "test-authsession-ttl");
    s.getContext().setRealm(realm);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realm.setAccessCodeLifespanLogin(1800);
    this.realmId = realm.getId();

    createClients(s, realm);
  }

  @Override
  public void cleanEnvironment(KeycloakSession s) {
    RealmModel realm = s.realms().getRealm(realmId);
    s.getContext().setRealm(realm);
    s.realms().removeRealm(realmId);
    jedis.flushAll();
  }

  @Test
  public void createdAuthSessionKeyCarriesTtlBackstop() {
    String[] created = new String[2]; // clientUuid, tabId
    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisAuthenticationSessionProvider provider =
              new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
          RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm);
          AuthenticationSessionModel authSession = root.createAuthenticationSession(client);
          created[0] = client.getId();
          created[1] = authSession.getTabId();
          return null;
        });

    String authSessionKey = String.format("auth-session:%s:%s", created[0], created[1]);
    assertThat("the auth-session hash must be written", jedis.exists(authSessionKey), is(true));

    long pttl = jedis.pttl(authSessionKey);
    assertThat(
        "the auth-session key must carry a positive TTL backstop so it cannot leak forever; pttl was "
            + pttl,
        pttl > 0L,
        is(true));
  }

  /**
   * Since #78 gave each child auth-session hash its own native TTL, {@code
   * removeAuthenticationSessionByTabId} must re-stamp the <em>surviving</em> children when it bumps
   * the root's horizon. Otherwise a still-active child keeps its original (now shorter) TTL and can
   * expire before its refreshed root — silently dropping a live login mid-flow. Two tabs share a
   * root; after time passes and one tab is removed, the survivor's PTTL must track the root's new
   * horizon, not its original one.
   */
  @Test
  public void removeByTabIdReStampsSurvivingChildTtl() {
    String[] ctx = new String[3]; // rootId, survivingClientUuid, survivingTabId
    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisAuthenticationSessionProvider provider =
              new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
          RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm);
          root.createAuthenticationSession(client); // the tab we will remove
          AuthenticationSessionModel survivor = root.createAuthenticationSession(client);
          ctx[0] = root.getId();
          ctx[1] = client.getId();
          ctx[2] = survivor.getTabId();
          return null;
        });

    String survivorKey = String.format("auth-session:%s:%s", ctx[1], ctx[2]);
    long lifespanMs = 1800L * 1000; // realm accessCodeLifespanLogin
    // Sanity: survivor starts near the original horizon.
    assertThat(jedis.pttl(survivorKey) <= lifespanMs, is(true));

    try {
      // Advance well past creation but comfortably inside the lifespan.
      Time.setOffset(600); // +10 min

      withRealm(
          realmId,
          (s, realm) -> {
            RedisAuthenticationSessionProvider provider =
                new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
            RootAuthenticationSessionModel root =
                provider.getRootAuthenticationSession(realm, ctx[0]);
            // Remove the OTHER tab, leaving the survivor; this bumps the root's horizon.
            String removeTabId =
                root.getAuthenticationSessions().keySet().stream()
                    .filter(t -> !t.equals(ctx[2]))
                    .findFirst()
                    .orElseThrow();
            root.removeAuthenticationSessionByTabId(removeTabId);
            return null;
          });

      // With the fix the survivor's TTL is re-stamped to the bumped horizon (~600s + 1800s from
      // creation ≈ 2400s remaining); without it, it keeps the original ~1800s.
      long after = jedis.pttl(survivorKey);
      long twoThousandSecondsMs = 2000L * 1000;
      assertThat(
          "surviving child TTL must be re-stamped to the root's bumped horizon; pttl was " + after,
          after > twoThousandSecondsMs,
          is(true));
    } finally {
      Time.setOffset(0);
    }
  }

  /**
   * {@code restartSession} clears the root's children and starts it fresh, so it must also re-stamp
   * the root's own TTL horizon (#78 gave the root a native expiration). Resetting only the
   * timestamp would leave a root restarted late in its lifespan under its old, possibly imminent,
   * expiration — so it could vanish mid-flow before the next {@code createAuthenticationSession}
   * re-stamps it. After time passes and the root is restarted, its PTTL must track the fresh
   * horizon, not the original one.
   */
  @Test
  public void restartReStampsRootTtl() {
    String rootId =
        withRealm(
            realmId,
            (s, realm) -> {
              ClientModel client = realm.getClientByClientId("test-app");
              RedisAuthenticationSessionProvider provider =
                  new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
              RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm);
              root.createAuthenticationSession(client);
              return root.getId();
            });

    String rootKey = String.format("root-auth-session:%s:%s", realmId, rootId);
    long lifespanMs = 1800L * 1000; // realm accessCodeLifespanLogin
    // Sanity: root starts near the original horizon.
    assertThat(jedis.pttl(rootKey) <= lifespanMs, is(true));

    try {
      // Advance well past creation but comfortably inside the lifespan.
      Time.setOffset(600); // +10 min

      withRealm(
          realmId,
          (s, realm) -> {
            RedisAuthenticationSessionProvider provider =
                new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
            RootAuthenticationSessionModel root =
                provider.getRootAuthenticationSession(realm, rootId);
            root.restartSession(realm);
            return null;
          });

      // With the fix the root's TTL is re-stamped to the bumped horizon (~600s + 1800s from
      // creation
      // ≈ 2400s remaining); without it, it keeps the original ~1800s (elapsed to ~1200s remaining).
      long after = jedis.pttl(rootKey);
      long twoThousandSecondsMs = 2000L * 1000;
      assertThat(
          "restart must re-stamp the root's TTL to a fresh horizon; pttl was " + after,
          after > twoThousandSecondsMs,
          is(true));
    } finally {
      Time.setOffset(0);
    }
  }

  /**
   * Opening a new tab ({@code createAuthenticationSession}) bumps the root's horizon and stamps the
   * new child's TTL — but it must also re-stamp the <em>already-open</em> sibling tabs. Since #78
   * gave each child its own native hash TTL, leaving an earlier sibling on its original horizon
   * lets it TTL-expire before the refreshed root and vanish mid-login while the root is still
   * alive. Tab A is created, time passes, tab B is opened; tab A's PTTL must track the bumped
   * horizon (issue #78 review).
   */
  @Test
  public void createAuthenticationSessionReStampsExistingSiblingTtl() {
    String[] ctx = new String[3]; // rootId, clientUuid, tabAId
    withRealm(
        realmId,
        (s, realm) -> {
          ClientModel client = realm.getClientByClientId("test-app");
          RedisAuthenticationSessionProvider provider =
              new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
          RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm);
          AuthenticationSessionModel tabA = root.createAuthenticationSession(client);
          ctx[0] = root.getId();
          ctx[1] = client.getId();
          ctx[2] = tabA.getTabId();
          return null;
        });

    String tabAKey = String.format("auth-session:%s:%s", ctx[1], ctx[2]);
    long lifespanMs = 1800L * 1000; // realm accessCodeLifespanLogin
    assertThat(
        "tab A starts near the original horizon", jedis.pttl(tabAKey) <= lifespanMs, is(true));

    try {
      // Time passes, then a second tab is opened under the same root.
      Time.setOffset(600); // +10 min

      withRealm(
          realmId,
          (s, realm) -> {
            ClientModel client = realm.getClientByClientId("test-app");
            RedisAuthenticationSessionProvider provider =
                new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
            RootAuthenticationSessionModel root =
                provider.getRootAuthenticationSession(realm, ctx[0]);
            root.createAuthenticationSession(client); // tab B — must re-stamp tab A too
            return null;
          });

      long after = jedis.pttl(tabAKey);
      long twoThousandSecondsMs = 2000L * 1000;
      assertThat(
          "opening a new tab must re-stamp the existing sibling tab's TTL; pttl was " + after,
          after > twoThousandSecondsMs,
          is(true));
    } finally {
      Time.setOffset(0);
    }
  }

  /**
   * The auth-session parent-index read ({@code getAuthenticationSessions}) must self-heal like the
   * other by-index reads: a dangling member (its child hash TTL-expired/vanished) is registered for
   * reap-at-commit, so an actively-used root's parent-index Set does not accumulate stale members
   * (issue #78 review).
   */
  @Test
  public void getAuthenticationSessionsReapsDanglingChildAtCommit() {
    String rootId =
        withRealm(
            realmId,
            (s, realm) -> {
              ClientModel client = realm.getClientByClientId("test-app");
              RedisAuthenticationSessionProvider provider =
                  new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
              RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm);
              root.createAuthenticationSession(client);
              return root.getId();
            });
    String indexKey = "auth-session:parent:" + rootId;

    // Plant a dangling parent-index member (no backing hash), alongside the real live child.
    String dangling = "auth-session:" + UUID.randomUUID() + ":" + UUID.randomUUID();
    jedis.sadd(indexKey, dangling);
    assertThat(ClusterTestSupport.members(jedis, indexKey), hasSize(2));

    withRealm(
        realmId,
        (s, realm) -> {
          RedisAuthenticationSessionProvider provider =
              new RedisAuthenticationSessionProvider(s, jedis, RedisMode.CLUSTER, 300);
          RootAuthenticationSessionModel root =
              provider.getRootAuthenticationSession(realm, rootId);
          root.getAuthenticationSessions(); // drives the parent-index read → schedules the reap
          return null;
        });

    // After commit the dangling member is reaped; the real live child remains.
    Set<String> after = ClusterTestSupport.members(jedis, indexKey);
    assertThat(
        "dangling auth-session parent-index member must be reaped at commit", after, hasSize(1));
    assertThat(after.contains(dangling), is(false));
  }
}
