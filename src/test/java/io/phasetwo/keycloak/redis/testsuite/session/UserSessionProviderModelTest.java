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

import static io.phasetwo.keycloak.redis.testsuite.session.SessionTestUtils.*;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.*;

import io.phasetwo.keycloak.redis.connection.RedisConnectionProvider;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import io.phasetwo.keycloak.redis.userSession.RedisAuthenticatedClientSessionAdapter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import redis.clients.jedis.UnifiedJedis;

@SuppressWarnings("deprecation")
public class UserSessionProviderModelTest extends KeycloakModelTest {
  private String realmId;

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "test");
    s.getContext().setRealm(realm);

    realm.setOfflineSessionIdleTimeout(Constants.DEFAULT_OFFLINE_SESSION_IDLE_TIMEOUT);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realm.setSsoSessionIdleTimeout(1800);
    realm.setSsoSessionMaxLifespan(36000);
    realm.setClientSessionIdleTimeout(500);
    this.realmId = realm.getId();

    s.users().addUser(realm, "user1").setEmail("user1@localhost");
    s.users().addUser(realm, "user2").setEmail("user2@localhost");

    createClients(s, realm);
  }

  @Override
  public void cleanEnvironment(KeycloakSession s) {
    RealmModel realm = s.realms().getRealm(realmId);
    s.getContext().setRealm(realm);

    s.sessions().removeUserSessions(realm);

    UserModel user1 = s.users().getUserByUsername(realm, "user1");
    UserModel user2 = s.users().getUserByUsername(realm, "user2");

    UserManager um = new UserManager(s);
    if (user1 != null) {
      um.removeUser(realm, user1);
    }
    if (user2 != null) {
      um.removeUser(realm, user2);
    }

    s.realms().removeRealm(realmId);
  }

  // Copied / Adapted from org.keycloak.testsuite.model.session.UserSessionProviderModelTest

  @Test
  public void testMultipleSessionsRemovalInOneTransaction() {
    UserSessionModel[] origSessions =
        inComittedTransaction(
            session -> {
              return createSessions(session, realmId);
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          UserSessionModel userSession =
              session.sessions().getUserSession(realm, origSessions[0].getId());
          Assert.assertTrue(areEntitiesEqual(origSessions[0], userSession));

          userSession = session.sessions().getUserSession(realm, origSessions[1].getId());
          Assert.assertTrue(areEntitiesEqual(origSessions[1], userSession));
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          session
              .sessions()
              .removeUserSession(
                  realm, session.sessions().getUserSession(realm, origSessions[0].getId()));
          session
              .sessions()
              .removeUserSession(
                  realm, session.sessions().getUserSession(realm, origSessions[1].getId()));
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          UserSessionModel userSession =
              session.sessions().getUserSession(realm, origSessions[0].getId());
          Assert.assertNull(userSession);

          userSession = session.sessions().getUserSession(realm, origSessions[1].getId());
          Assert.assertNull(userSession);
        });
  }

  /**
   * Builds a handle onto the client session stored under {@code <parentId>::<clientUuid>} without
   * registering it with the client-session transaction. Deliberately goes through the adapter's own
   * setters rather than a hand-written Redis hash, so the fixture survives a rename of the stored
   * field names.
   *
   * <p>Because the client-session key is deterministic, a handle built this way addresses the same
   * Redis hash as the one the provider would hand out — which is what lets the detach tests assert
   * on Redis rather than on the in-memory {@code isMarkedForDelete()} flag of an instance nothing
   * else can see.
   */
  private static RedisAuthenticatedClientSessionAdapter clientSessionReferring(
      KeycloakSession session, String realmId, String parentId, String clientUuid) {
    RedisAuthenticatedClientSessionAdapter clientSession =
        new RedisAuthenticatedClientSessionAdapter(session, parentId + "::" + clientUuid);
    clientSession.setRealmId(realmId);
    clientSession.setParentId(parentId);
    clientSession.setClientUuid(clientUuid);
    return clientSession;
  }

  private static UnifiedJedis jedis(KeycloakSession session) {
    return session.getProvider(RedisConnectionProvider.class).getJedis();
  }

  private static String clientSessionKey(String parentId, String clientUuid) {
    return "authenticated-client:" + parentId + "::" + clientUuid;
  }

  private static String parentIndexKey(String parentId) {
    return "authenticated-client:parent-index:" + parentId;
  }

  private static String clientIndexKey(String clientUuid) {
    return "authenticated-client:client-index:" + clientUuid;
  }

  /**
   * Regression for issue #81: {@code setTimestamp} must not throw when the parent user session has
   * expired/vanished, and — crucially — must not <em>guess</em> the offline flag. The two
   * expiration branches differ by up to 1440x (30 days offline vs. this realm's 500s client-session
   * idle timeout), so guessing online for a genuine offline grant kills it within the hour. When
   * the flag is unknowable and an expiration is already stored, the stored one must survive
   * untouched.
   */
  @Test
  public void testSetTimestampWithUnknowableOfflineFlagPreservesTheStoredExpiration() {
    final String parentId = KeycloakModelUtils.generateId(); // no such user session exists
    final long storedExpiration = (Time.currentTimeMillis() + 9_999_000L);

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          ClientModel client = realm.getClientByClientId("test-app");

          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, parentId, client.getId());
          orphan.setExpiration(storedExpiration);

          int newTimestamp = Time.currentTime() + 60;
          // Must not throw despite getUserSession() resolving to null.
          orphan.setTimestamp(newTimestamp);

          assertEquals(
              "the timestamp write must still happen", newTimestamp, orphan.getTimestamp());
          assertThat(
              "the timestamp write must still be flushed",
              orphan.getDirtyFields().keySet(),
              hasItem("timestamp"));
          assertEquals(
              "an unknowable offline flag must preserve the stored expiration, not guess one",
              Long.valueOf(storedExpiration),
              orphan.getExpiration());
          return null;
        });
  }

  /**
   * Companion to {@link #testSetTimestampWithUnknowableOfflineFlagPreservesTheStoredExpiration()}:
   * preserving is only safe when there is something to preserve. Writing <em>no</em> expiration
   * leaves a hash Redis never expires — {@code RedisHashCas} skips {@code PEXPIREAT} for a null
   * expiration, {@code ExpirationUtils.isExpired} reports false for it, and one such member makes
   * the read path {@code PERSIST} the shared client-index Set for every co-tenant. So with nothing
   * stored, fall back to the shorter (online) horizon rather than emitting a TTL-less hash.
   */
  @Test
  public void testSetTimestampWithUnknowableOfflineFlagNeverLeavesTheHashWithoutAnExpiration() {
    final String parentId = KeycloakModelUtils.generateId(); // no such user session exists

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          ClientModel client = realm.getClientByClientId("test-app");

          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, parentId, client.getId());
          assertNull("precondition: nothing stored to preserve", orphan.getExpiration());

          int newTimestamp = Time.currentTime();
          orphan.setTimestamp(newTimestamp);

          assertNotNull(
              "a client session must never be written without an expiration",
              orphan.getExpiration());
          assertEquals(
              "with nothing to preserve, fall back to the shorter online horizon",
              Long.valueOf(newTimestamp * 1000L + 500_000L),
              orphan.getExpiration());
          return null;
        });
  }

  /**
   * Regression for issue #81: a client session must carry its own offline flag. Asking the parent
   * user session for it fails exactly when it matters — the parent is the thing that vanished — and
   * an offline grant that falls back to the online horizon dies 1440x early. With the flag stored
   * on the client session, an orphan still computes the correct offline expiration.
   */
  @Test
  public void testSetTimestampOnAnOfflineOrphanKeepsTheOfflineHorizon() {
    final String parentId = KeycloakModelUtils.generateId(); // no such user session exists

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          ClientModel client = realm.getClientByClientId("test-app");

          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, parentId, client.getId());
          orphan.setOffline(true);

          int newTimestamp = Time.currentTime();
          orphan.setTimestamp(newTimestamp);

          assertEquals(
              "an offline client session must keep the offline idle horizon even when orphaned",
              Long.valueOf(newTimestamp * 1000L + realm.getOfflineSessionIdleTimeout() * 1000L),
              orphan.getExpiration());
          return null;
        });
  }

  /**
   * Regression for issue #81: {@code setTimestamp} must not throw when the client is missing even
   * though the parent user session still exists (a stale client session whose client was deleted).
   * Both expiration branches dereference the client — but only to read per-client
   * <em>overrides</em> of realm defaults, so a missing client means "no overrides", not "no
   * expiration". Skipping the write entirely is what strands the hash without a TTL.
   */
  @Test
  public void testSetTimestampWithMissingClientFallsBackToRealmDefaults() {
    final String missingClientUuid = KeycloakModelUtils.generateId(); // no such client exists

    inComittedTransaction(
        session -> {
          // A real, existing user session, so only the client is missing.
          UserSessionModel userSession = createSessions(session, realmId)[0];

          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, userSession.getId(), missingClientUuid);

          int newTimestamp = Time.currentTime();
          // Must not throw despite getClient() resolving to null.
          orphan.setTimestamp(newTimestamp);

          assertEquals(
              "the timestamp write must still happen", newTimestamp, orphan.getTimestamp());
          assertEquals(
              "a missing client means no per-client overrides, so the realm defaults apply",
              Long.valueOf(newTimestamp * 1000L + 500_000L),
              orphan.getExpiration());
          return null;
        });
  }

  /**
   * Regression for issue #81: the guards added for a missing parent and a missing client are both
   * unreachable when the <em>realm</em> is the thing that is gone. {@code getClient()} is {@code
   * getRealm().getClientById(..)} and {@code getRealm()} returns {@code null} for a realm that no
   * longer resolves, so the NPE fires one frame earlier than the guard that is supposed to stop it
   * — and {@code getUserSession()} hits {@code Objects.requireNonNull(realm)} inside the provider.
   */
  @Test
  public void testAnUnresolvableRealmDoesNotThrowFromTimestampOrDetach() {
    final String goneRealmId = KeycloakModelUtils.generateId(); // no such realm exists
    final String parentId = KeycloakModelUtils.generateId();
    final String clientUuid = KeycloakModelUtils.generateId();

    inComittedTransaction(
        session -> {
          RedisAuthenticatedClientSessionAdapter stranded =
              clientSessionReferring(session, goneRealmId, parentId, clientUuid);

          assertNull("precondition: the realm must not resolve", stranded.getRealm());
          assertNull("getClient() must not dereference a null realm", stranded.getClient());
          assertNull(
              "getUserSession() must not dereference a null realm", stranded.getUserSession());

          int newTimestamp = Time.currentTime();
          stranded.setTimestamp(newTimestamp);
          assertEquals(
              "the timestamp write must still happen", newTimestamp, stranded.getTimestamp());

          stranded.detachFromUserSession();
          return null;
        });
  }

  /**
   * Regression for issue #81: {@code getRefreshTokenUseCount} reaches the realm through {@code
   * session.realms().getRealm(getRealmId())} directly rather than through {@code getRealm()}, so
   * the null-realm guard on the other entry points does not cover it. Keycloak calls it on every
   * refresh when {@code revokeRefreshToken} is enabled, and client sessions are handed out by the
   * client index with a {@code realmId} read straight off the hash — no cross-check against a realm
   * the caller already resolved.
   *
   * <p>Without a realm the reuse interval is unknowable, so the refresh must be counted rather than
   * discounted: erring toward revoking on reuse is the safe direction.
   */
  @Test
  public void testGetRefreshTokenUseCountWithUnresolvableRealmDoesNotThrow() {
    final String goneRealmId = KeycloakModelUtils.generateId(); // no such realm exists
    final String reuseId = "reuse-1";

    inComittedTransaction(
        session -> {
          RedisAuthenticatedClientSessionAdapter stranded =
              clientSessionReferring(
                  session,
                  goneRealmId,
                  KeycloakModelUtils.generateId(),
                  KeycloakModelUtils.generateId());

          // Sets both the use-count and the last-use notes, which is what takes the realm branch.
          stranded.setRefreshTokenUseCount(reuseId, 3);

          assertEquals(
              "an unresolvable realm must not discount the refresh, and must not throw",
              3,
              stranded.getRefreshTokenUseCount(reuseId));
          return null;
        });
  }

  /**
   * Re-issuing a client session for a client that already has one must replace it, not delete it.
   *
   * <p>Client-session ids are deterministic, so the "replacement" is the same Redis key as the
   * predecessor. Both create paths remove the predecessor first, which puts that key in the
   * transaction's {@code toDelete} map — and {@code commitImpl} deletes any cached model whose key
   * is in {@code toDelete}, in preference to writing it. The session the caller was just handed
   * then silently never reaches Redis.
   */
  @Test
  public void testRecreatingAnOnlineClientSessionKeepsIt() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          session.sessions().createClientSession(realm, realm.getClientById(ids[1]), userSession);
        });

    inComittedTransaction(
        session -> {
          assertTrue(
              "re-creating a client session must replace it, not delete it",
              jedis(session).exists(clientSessionKey(ids[0], ids[1])));
        });
  }

  /**
   * Re-creating a client session must replace it, not merge into it.
   *
   * <p>Writes are partial ({@code hsetex} of the dirty fields under a CAS version), and a version
   * mismatch rebases onto whatever is currently in Redis. So a "new" client session written over
   * the key of the one it replaced inherits every field the caller did not explicitly set —
   * including the predecessor's {@code refreshToken:<reuseId>}, which leaves a revoked token
   * resolvable on the successor session.
   */
  @Test
  public void testRecreatingAClientSessionDoesNotInheritThePredecessorsRefreshToken() {
    final String reuseId = "reuse-1";
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          userSession
              .getAuthenticatedClientSessions()
              .get(ids[1])
              .setRefreshToken(reuseId, "the-old-token");
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          session.sessions().createClientSession(realm, realm.getClientById(ids[1]), userSession);
        });

    inComittedTransaction(
        session -> {
          assertNull(
              "a re-created client session must not inherit the predecessor's refresh token",
              jedis(session).hget(clientSessionKey(ids[0], ids[1]), "refreshToken:" + reuseId));
        });
  }

  /**
   * The offline twin of {@link
   * #testRecreatingAClientSessionDoesNotInheritThePredecessorsRefreshToken()}: re-issuing an
   * offline token must not leave the previous offline refresh token resolvable on the successor
   * session.
   */
  @Test
  public void testRecreatingAnOfflineClientSessionDoesNotInheritTheRefreshToken() {
    final String reuseId = "reuse-1";
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    String offlineUserSessionId =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel online = session.sessions().getUserSession(realm, ids[0]);
              UserSessionModel offline = session.sessions().createOfflineUserSession(online);
              session
                  .sessions()
                  .createOfflineClientSession(
                      online.getAuthenticatedClientSessions().get(ids[1]), offline);
              return offline.getId();
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          session
              .sessions()
              .getOfflineUserSession(realm, offlineUserSessionId)
              .getAuthenticatedClientSessions()
              .get(ids[1])
              .setRefreshToken(reuseId, "the-old-offline-token");
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel online = session.sessions().getUserSession(realm, ids[0]);
          UserSessionModel offline =
              session.sessions().getOfflineUserSession(realm, offlineUserSessionId);
          session
              .sessions()
              .createOfflineClientSession(
                  online.getAuthenticatedClientSessions().get(ids[1]), offline);
        });

    inComittedTransaction(
        session -> {
          assertNull(
              "re-issuing an offline token must not carry the previous refresh token over",
              jedis(session)
                  .hget(clientSessionKey(offlineUserSessionId, ids[1]), "refreshToken:" + reuseId));
        });
  }

  /**
   * Replacing a client session must not wipe the fields the fresh instance sets before it is handed
   * to the caller. The adapter writes its own {@code id} in its constructor, so a replace that
   * marks every predecessor field deleted would drop it — leaving a hash whose {@code getId()} is
   * null.
   */
  @Test
  public void testRecreatingAClientSessionKeepsItsIdField() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          session.sessions().createClientSession(realm, realm.getClientById(ids[1]), userSession);
        });

    inComittedTransaction(
        session -> {
          assertEquals(
              "the re-created client session must keep its id",
              ids[0] + "::" + ids[1],
              jedis(session).hget(clientSessionKey(ids[0], ids[1]), "id"));
        });
  }

  /** The offline twin of {@link #testRecreatingAnOnlineClientSessionKeepsIt()}. */
  @Test
  public void testRecreatingAnOfflineClientSessionKeepsIt() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    String offlineUserSessionId =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel online = session.sessions().getUserSession(realm, ids[0]);
              UserSessionModel offline = session.sessions().createOfflineUserSession(online);
              session
                  .sessions()
                  .createOfflineClientSession(
                      online.getAuthenticatedClientSessions().get(ids[1]), offline);
              return offline.getId();
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel online = session.sessions().getUserSession(realm, ids[0]);
          UserSessionModel offline =
              session.sessions().getOfflineUserSession(realm, offlineUserSessionId);
          session
              .sessions()
              .createOfflineClientSession(
                  online.getAuthenticatedClientSessions().get(ids[1]), offline);
        });

    inComittedTransaction(
        session -> {
          assertTrue(
              "re-issuing an offline token must replace the offline client session, not delete it",
              jedis(session).exists(clientSessionKey(offlineUserSessionId, ids[1])));
        });
  }

  /**
   * A client session hash written before the offline flag was persisted must backfill it the first
   * time it is written, while its parent user session is still reachable. Otherwise every
   * pre-upgrade session keeps depending on a parent that may be gone next time, and the orphan bug
   * survives the fix for as long as those sessions live — up to the 30-day offline horizon.
   */
  @Test
  public void testSetTimestampBackfillsTheOfflineFlagFromTheParent() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    String offlineUserSessionId =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return session
                  .sessions()
                  .createOfflineUserSession(session.sessions().getUserSession(realm, ids[0]))
                  .getId();
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          RedisAuthenticatedClientSessionAdapter legacy =
              clientSessionReferring(session, realmId, offlineUserSessionId, ids[1]);
          assertNull("precondition: a legacy hash carries no offline flag", legacy.isOffline());

          int newTimestamp = Time.currentTime();
          legacy.setTimestamp(newTimestamp);

          assertEquals(
              "the offline flag must be backfilled from the parent while it is still reachable",
              Boolean.TRUE,
              legacy.isOffline());
          assertEquals(
              "and the offline horizon must be used, not the online one",
              Long.valueOf(newTimestamp * 1000L + realm.getOfflineSessionIdleTimeout() * 1000L),
              legacy.getExpiration());
          return null;
        });
  }

  /**
   * Regression for issue #81: {@code getUserSession()} must resolve <em>this</em> client session's
   * parent, not the offline sibling of a vanished online parent.
   *
   * <p>{@code getOfflineUserSession(realm, id)} falls back to {@code
   * user-session:corresponding-session-index:<id>}, which is keyed on the <em>online</em> session
   * id. That alias is right for a caller asking "give me the offline session for this login" and
   * wrong here: accepting it makes an orphan look attached, so the orphan guard never fires and
   * detach revokes the sibling's client session instead.
   */
  @Test
  public void testGetUserSessionDoesNotResolveTheOfflineSiblingOfAVanishedParent() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    // createSessions() commits in a nested transaction, so the online session has to be re-read
    // here before it can father an offline sibling.
    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          session
              .sessions()
              .createOfflineUserSession(session.sessions().getUserSession(realm, ids[0]));
        });

    // The online parent's hash TTL-expires; its offline sibling and the corresponding-session
    // index both survive.
    inComittedTransaction(
        session -> {
          jedis(session).del("user-session:" + ids[0]);
        });

    inComittedTransaction(
        session -> {
          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, ids[0], ids[1]);

          assertNull(
              "an orphan must not adopt the offline sibling of its vanished parent",
              orphan.getUserSession());
          return null;
        });
  }

  /**
   * The online mirror of {@link #testSetTimestampBackfillsTheOfflineFlagFromTheParent()}, and the
   * direction with no guard of its own: a legacy hash whose online parent is still alive <em>and
   * has an offline sibling</em> must backfill {@code false}.
   *
   * <p>Two things keep this correct, and either one alone is enough: {@code getUserSession()} tries
   * the online lookup first, and it exact-matches the parent id on the offline fallback — whose
   * corresponding-session-index alias otherwise hands back the sibling. Verified by probe: drop
   * both and this test fails with {@code expected:<false> but was:<true>}; drop either one and it
   * still passes. It is the redundancy that is being pinned here, because the backfill persists
   * whatever it decides, so a wrong answer is permanent for the life of the grant (issue #81).
   */
  @Test
  public void testSetTimestampBackfillsOnlineWhenTheLiveParentHasAnOfflineSibling() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          session
              .sessions()
              .createOfflineUserSession(session.sessions().getUserSession(realm, ids[0]));
        });

    // Age the hash back to before the offline flag existed.
    inComittedTransaction(
        session -> {
          jedis(session).hdel(clientSessionKey(ids[0], ids[1]), "offline");
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel sibling = session.sessions().getOfflineUserSession(realm, ids[0]);
          assertNotNull("precondition: the online parent has an offline sibling", sibling);
          assertNotEquals(
              "precondition: the sibling is a different user session", ids[0], sibling.getId());

          RedisAuthenticatedClientSessionAdapter legacy =
              clientSessionReferring(session, realmId, ids[0], ids[1]);
          assertNull("precondition: a legacy hash carries no offline flag", legacy.isOffline());

          int newTimestamp = Time.currentTime();
          legacy.setTimestamp(newTimestamp);

          assertEquals(
              "a live online parent must backfill false, not the offline sibling's true",
              Boolean.FALSE,
              legacy.isOffline());
          assertEquals(
              "and the online horizon must be used, not the offline one",
              Long.valueOf(newTimestamp * 1000L + realm.getClientSessionIdleTimeout() * 1000L),
              legacy.getExpiration());
          return null;
        });
  }

  /**
   * Answers the review question on #86: promoting one client to offline must not drag the user's
   * other client sessions offline with it.
   *
   * <p>{@code offline} is a partition flag, not an entitlement — the {@code offline_access} check
   * lives in {@code UserSessionManager.isOfflineTokenAllowed}, two layers above this provider. What
   * this test pins is the structural consequence: {@code createOfflineUserSession} copies no client
   * sessions at all, and {@code createOfflineClientSession} adds exactly the one client it is
   * handed, so a client that never requested offline access keeps both its online client session
   * and its online flag.
   */
  @Test
  public void testPromotingOneClientToOfflineLeavesTheOtherClientOnline() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              return new String[] {
                createSessions(session, realmId)[0].getId(),
                realm.getClientByClientId("test-app").getId(),
                realm.getClientByClientId("third-party").getId()
              };
            });

    String offlineUserSessionId =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel online = session.sessions().getUserSession(realm, ids[0]);
              UserSessionModel offline = session.sessions().createOfflineUserSession(online);
              session
                  .sessions()
                  .createOfflineClientSession(
                      online.getAuthenticatedClientSessions().get(ids[1]), offline);
              return offline.getId();
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          assertEquals(
              "only the client that requested offline access belongs to the offline session",
              Set.of(ids[1]),
              session
                  .sessions()
                  .getOfflineUserSession(realm, offlineUserSessionId)
                  .getAuthenticatedClientSessions()
                  .keySet());

          assertEquals(
              "the promoted client session is stored offline",
              "true",
              jedis(session).hget(clientSessionKey(offlineUserSessionId, ids[1]), "offline"));
          assertEquals(
              "the other client keeps its online client session, still flagged online",
              "false",
              jedis(session).hget(clientSessionKey(ids[0], ids[2]), "offline"));
          return null;
        });
  }

  /**
   * Regression for issue #81: {@code detachFromUserSession} is the revocation primitive behind
   * refresh-token reuse, authorization-code replay and offline-token revoke. Returning silently for
   * an orphan reports a successful revoke while leaving the hash, its {@code
   * refreshToken:<reuseId>} fields and both secondary-index memberships live in Redis — and nothing
   * else reaps a client session whose parent is gone ({@code removeAllExpired} is a log-only
   * no-op). The orphan must be deleted from Redis.
   */
  @Test
  public void testDetachFromUserSessionWithOrphanedParentReapsItFromRedis() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel userSession = createSessions(session, realmId)[0];
              return new String[] {
                userSession.getId(), realm.getClientByClientId("test-app").getId()
              };
            });
    final String parentId = ids[0];
    final String clientUuid = ids[1];

    inComittedTransaction(
        session -> {
          assertTrue(
              "precondition: the client session hash exists",
              jedis(session).exists(clientSessionKey(parentId, clientUuid)));
          // The parent user session's hash TTL-expires, orphaning its client sessions.
          jedis(session).del("user-session:" + parentId);
        });

    inComittedTransaction(
        session -> {
          RedisAuthenticatedClientSessionAdapter orphan =
              clientSessionReferring(session, realmId, parentId, clientUuid);
          // Must not throw despite getUserSession() resolving to null.
          orphan.detachFromUserSession();
        });

    inComittedTransaction(
        session -> {
          UnifiedJedis jedis = jedis(session);
          assertFalse(
              "detaching an orphan must delete its hash, not report a successful revoke while"
                  + " leaving it live in Redis",
              jedis.exists(clientSessionKey(parentId, clientUuid)));
          assertThat(
              "detaching an orphan must drop it from the parent index",
              jedis.smembers(parentIndexKey(parentId)),
              not(hasItem(clientSessionKey(parentId, clientUuid))));
          assertThat(
              "detaching an orphan must drop it from the client index",
              jedis.smembers(clientIndexKey(clientUuid)),
              not(hasItem(clientSessionKey(parentId, clientUuid))));
        });
  }

  /**
   * The positive path of {@link #testDetachFromUserSessionWithOrphanedParentReapsItFromRedis()}: an
   * attached client session must also actually leave Redis, which the pre-existing {@code
   * isMarkedForDelete()}-style assertions could not observe.
   */
  @Test
  public void testDetachFromUserSessionRemovesAnAttachedClientSessionFromRedis() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel userSession = createSessions(session, realmId)[0];
              return new String[] {
                userSession.getId(), realm.getClientByClientId("test-app").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          userSession.getAuthenticatedClientSessions().get(ids[1]).detachFromUserSession();
        });

    inComittedTransaction(
        session -> {
          assertFalse(
              "detaching an attached client session must delete its hash",
              jedis(session).exists(clientSessionKey(ids[0], ids[1])));
        });
  }

  /**
   * Regression for issue #81: {@code removeAuthenticatedClientSessions} reads through the
   * triple-filtered client-session map (expired / offline-flag mismatch / deleted client), so a
   * client session that is very much alive in Redis can be invisible to the only code path that
   * deletes it. Revoking then reports success and removes nothing. Deleting a client that still has
   * a live client session is the cheapest way to reach that filter.
   */
  @Test
  public void testRemoveAuthenticatedClientSessionsDeletesASessionHiddenByTheClientFilter() {
    String[] ids =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel userSession = createSessions(session, realmId)[0];
              return new String[] {
                userSession.getId(), realm.getClientByClientId("third-party").getId()
              };
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          realm.removeClient(ids[1]);
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, ids[0]);
          userSession.removeAuthenticatedClientSessions(Collections.singleton(ids[1]));
        });

    inComittedTransaction(
        session -> {
          assertFalse(
              "a revoke must delete the client session even when the read filters hide it",
              jedis(session).exists(clientSessionKey(ids[0], ids[1])));
        });
  }

  /**
   * Client-session expiration must only use the remember-me lifespans when the session actually is
   * remember-me. {@code setUserSessionExpiration} checks the flag; {@code
   * setClientSessionExpiration} does not, so on a realm with remember-me timeouts configured every
   * client session — remember-me or not — outlives its parent user session and becomes exactly the
   * orphan issue #81 is patching around.
   */
  @Test
  public void testClientSessionExpirationIgnoresRememberMeLifespansWhenNotRememberMe() {
    final int ssoIdle = 1800;
    final int ssoIdleRememberMe = 864000;

    withRealm(
        realmId,
        (s, realm) -> {
          realm.setSsoSessionIdleTimeoutRememberMe(ssoIdleRememberMe);
          realm.setClientSessionIdleTimeout(0); // do not let the min() mask the choice
          return null;
        });

    try {
      inComittedTransaction(
          session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            ClientModel client = realm.getClientByClientId("test-app");
            UserSessionModel userSession =
                session
                    .sessions()
                    .createUserSession(
                        realm,
                        session.users().getUserByUsername(realm, "user1"),
                        "user1",
                        "127.0.0.1",
                        "form",
                        false, // NOT remember-me
                        null,
                        null);
            AuthenticatedClientSessionModel clientSession =
                session.sessions().createClientSession(realm, client, userSession);

            assertEquals(
                "a non-remember-me client session must use the normal SSO idle timeout",
                Long.valueOf(clientSession.getTimestamp() * 1000L + ssoIdle * 1000L),
                ((RedisAuthenticatedClientSessionAdapter) clientSession).getExpiration());
            return null;
          });
    } finally {
      withRealm(
          realmId,
          (s, realm) -> {
            realm.setSsoSessionIdleTimeoutRememberMe(0);
            realm.setClientSessionIdleTimeout(500);
            return null;
          });
    }
  }

  @Test
  // @Ignore("multiple transactions")
  public void testExpiredClientSessions() {
    AtomicReference<List<String>> clientSessionIds = new AtomicReference<>();
    UserSessionModel[] origSessions =
        inComittedTransaction(
            session -> {
              // create some user and client sessions
              return createSessions(session, realmId);
            });
    var clientIds =
        withRealm(
            realmId,
            (s, realm) ->
                s
                    .sessions()
                    .getUserSession(realm, origSessions[0].getId())
                    .getAuthenticatedClientSessions()
                    .values()
                    .stream()
                    .map(AuthenticatedClientSessionModel::getId)
                    .collect(Collectors.toList()));
    clientSessionIds.set(clientIds);

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          UserSessionModel userSession =
              session.sessions().getUserSession(realm, origSessions[0].getId());
          areEntitiesEqual(origSessions[0], userSession);

          AuthenticatedClientSessionModel clientSession =
              session
                  .sessions()
                  .getClientSession(
                      userSession,
                      realm.getClientByClientId("test-app"),
                      session
                          .sessions()
                          .getUserSession(realm, origSessions[0].getId())
                          .getAuthenticatedClientSessionByClient(
                              realm.getClientByClientId("test-app").getId())
                          .getId(),
                      false);
          Assert.assertEquals(
              session
                  .sessions()
                  .getUserSession(realm, origSessions[0].getId())
                  .getAuthenticatedClientSessionByClient(
                      realm.getClientByClientId("test-app").getId())
                  .getId(),
              clientSession.getId());

          userSession = session.sessions().getUserSession(realm, origSessions[1].getId());
          areEntitiesEqual(origSessions[1], userSession);
        });

    // not possible to expire client session without expiring user sessions with time offset in map
    // storage because
    // expiration in map storage takes min of (clientSessionIdleExpiration, ssoSessionIdleTimeout)
    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          UserSessionModel userSession =
              session.sessions().getUserSession(realm, origSessions[0].getId());

          userSession.getAuthenticatedClientSessions().values().stream()
              .forEach(
                  clientSession -> {
                    // expire client sessions
                    clientSession.setTimestamp(1);
                  });
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);

          // assert the user session is still there
          UserSessionModel userSession =
              session.sessions().getUserSession(realm, origSessions[0].getId());
          areEntitiesEqual(origSessions[0], userSession);

          // assert the client sessions are expired
          clientSessionIds
              .get()
              .forEach(
                  clientSessionId -> {
                    Assert.assertNull(
                        session
                            .sessions()
                            .getClientSession(
                                userSession,
                                realm.getClientByClientId("test-app"),
                                clientSessionId,
                                false));
                    Assert.assertNull(
                        session
                            .sessions()
                            .getClientSession(
                                userSession,
                                realm.getClientByClientId("third-party"),
                                clientSessionId,
                                false));
                  });
        });
  }

  @Test
  public void testTransientUserSessionIsNotPersisted() {
    String id =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel userSession =
                  session
                      .sessions()
                      .createUserSession(
                          KeycloakModelUtils.generateId(),
                          realm,
                          session.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.1",
                          "form",
                          false,
                          null,
                          null,
                          UserSessionModel.SessionPersistenceState.TRANSIENT);

              ClientModel testApp = realm.getClientByClientId("test-app");
              AuthenticatedClientSessionModel clientSession =
                  session.sessions().createClientSession(realm, testApp, userSession);

              // assert the client sessions are present
              assertThat(
                  session
                      .sessions()
                      .getClientSession(userSession, testApp, clientSession.getId(), false),
                  notNullValue());
              return userSession.getId();
            });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          UserSessionModel userSession = session.sessions().getUserSession(realm, id);

          // in new transaction transient session should not be present
          assertThat(userSession, nullValue());
        });
  }

  @Test
  public void testClientSessionIsNotPersistedForTransientUserSession() {
    Object[] transientUserSessionWithClientSessionId =
        inComittedTransaction(
            session -> {
              RealmModel realm = session.realms().getRealm(realmId);
              UserSessionModel userSession =
                  session
                      .sessions()
                      .createUserSession(
                          null,
                          realm,
                          session.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.1",
                          "form",
                          false,
                          null,
                          null,
                          UserSessionModel.SessionPersistenceState.TRANSIENT);
              ClientModel testApp = realm.getClientByClientId("test-app");
              AuthenticatedClientSessionModel clientSession =
                  session.sessions().createClientSession(realm, testApp, userSession);

              // assert the client sessions are present
              assertThat(
                  session
                      .sessions()
                      .getClientSession(userSession, testApp, clientSession.getId(), false),
                  notNullValue());
              Object[] result = new Object[2];
              result[0] = userSession;
              result[1] = clientSession.getId();
              return result;
            });
    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          ClientModel testApp = realm.getClientByClientId("test-app");
          UserSessionModel userSession =
              (UserSessionModel) transientUserSessionWithClientSessionId[0];
          String clientSessionId = (String) transientUserSessionWithClientSessionId[1];
          // in new transaction transient session should not be present
          //          assertThat(
          //              session.sessions().getClientSession(userSession, testApp, clientSessionId,
          // false),
          //              nullValue());
        });
  }

  @Test
  @Ignore("Flaky")
  public void testCreateUserSessionsParallel() throws InterruptedException {
    Set<String> userSessionIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    CountDownLatch latch = new CountDownLatch(4);

    inIndependentFactories(
        4,
        30,
        () -> {
          withRealm(
              realmId,
              (session, realm) -> {
                UserModel user = session.users().getUserByUsername(realm, "user1");
                UserSessionModel userSession =
                    session
                        .sessions()
                        .createUserSession(realm, user, "user1", "", "", false, null, null);
                userSessionIds.add(userSession.getId());

                latch.countDown();

                return null;
              });

          // wait for other nodes to finish
          try {
            latch.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }

          assertThat(userSessionIds, Matchers.iterableWithSize(4));

          // wait a bit to allow replication
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }

          withRealm(
              realmId,
              (session, realm) -> {
                userSessionIds.forEach(
                    id -> Assert.assertNotNull(session.sessions().getUserSession(realm, id)));

                return null;
              });
        });
  }

  // Based off of UserSessionProviderTests (Arquillian)
  @Test
  public void testCreateSessions() {
    int started = Time.currentTime();

    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          assertSession(
              s.sessions().getUserSession(r, sessions[0].getId()),
              s.users().getUserByUsername(r, "user1"),
              "127.0.0.1",
              started,
              started,
              "test-app",
              "third-party");
          assertSession(
              s.sessions().getUserSession(r, sessions[1].getId()),
              s.users().getUserByUsername(r, "user1"),
              "127.0.0.2",
              started,
              started,
              "test-app");
          assertSession(
              s.sessions().getUserSession(r, sessions[2].getId()),
              s.users().getUserByUsername(r, "user2"),
              "127.0.0.3",
              started,
              started,
              "test-app");
          return null;
        });
  }

  @Test
  public void testUpdateSession() {
    int lastRefresh = Time.currentTime();
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          s.sessions().getUserSession(r, sessions[0].getId()).setLastSessionRefresh(lastRefresh);
          assertEquals(
              lastRefresh,
              s.sessions().getUserSession(r, sessions[0].getId()).getLastSessionRefresh());
          return null;
        });
  }

  @Test
  public void testUpdateSessionInSameTransaction() {
    int lastRefresh = Time.currentTime();
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          s.sessions().getUserSession(r, sessions[0].getId()).setLastSessionRefresh(lastRefresh);
          assertEquals(
              lastRefresh,
              s.sessions().getUserSession(r, sessions[0].getId()).getLastSessionRefresh());
          return null;
        });
  }

  @Test
  public void testRestartSession() {
    int started = Time.currentTime();

    Time.setOffset(100);
    try {
      withRealm(
          realmId,
          (s, r) -> {
            UserSessionModel[] sessions = createSessions(s, r.getId());
            UserSessionModel userSession = s.sessions().getUserSession(r, sessions[0].getId());
            assertSession(
                userSession,
                s.users().getUserByUsername(r, "user1"),
                "127.0.0.1",
                started,
                started,
                "test-app",
                "third-party");

            userSession.restartSession(
                r,
                s.users().getUserByUsername(r, "user2"),
                "user2",
                "127.0.0.6",
                "form",
                true,
                null,
                null);

            userSession = s.sessions().getUserSession(r, sessions[0].getId());
            assertSession(
                userSession,
                s.users().getUserByUsername(r, "user2"),
                "127.0.0.6",
                started + 100,
                started + 100);
            return null;
          });
    } finally {
      Time.setOffset(0);
    }
  }

  @Test
  public void testCreateClientSession() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          Map<String, AuthenticatedClientSessionModel> clientSessions =
              s.sessions().getUserSession(r, sessions[0].getId()).getAuthenticatedClientSessions();
          assertEquals(2, clientSessions.size());

          String clientUUID = r.getClientByClientId("test-app").getId();

          AuthenticatedClientSessionModel session1 = clientSessions.get(clientUUID);

          assertNull(session1.getAction());
          assertEquals(
              r.getClientByClientId("test-app").getClientId(), session1.getClient().getClientId());
          assertEquals(sessions[0].getId(), session1.getUserSession().getId());
          assertEquals("http://redirect", session1.getRedirectUri());
          assertEquals("state", session1.getNote(OIDCLoginProtocol.STATE_PARAM));
          return null;
        });
  }

  @Test
  public void testUpdateClientSession() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          String userSessionId = sessions[0].getId();
          String clientUUID = r.getClientByClientId("test-app").getId();
          UserSessionModel userSession = s.sessions().getUserSession(r, userSessionId);
          AuthenticatedClientSessionModel clientSession =
              userSession.getAuthenticatedClientSessions().get(clientUUID);

          int time = clientSession.getTimestamp();
          assertNull(clientSession.getAction());

          clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
          clientSession.setTimestamp(time + 10);

          AuthenticatedClientSessionModel updated =
              s.sessions()
                  .getUserSession(r, userSessionId)
                  .getAuthenticatedClientSessions()
                  .get(clientUUID);
          assertEquals(
              AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
          assertEquals(time + 10, updated.getTimestamp());
          return null;
        });
  }

  @Test
  public void testUpdateClientSessionWithGetByClientId() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          String userSessionId = sessions[0].getId();
          String clientUUID = r.getClientByClientId("test-app").getId();
          UserSessionModel userSession = s.sessions().getUserSession(r, userSessionId);
          AuthenticatedClientSessionModel clientSession =
              userSession.getAuthenticatedClientSessionByClient(clientUUID);

          int time = clientSession.getTimestamp();
          assertNull(clientSession.getAction());

          clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
          clientSession.setTimestamp(time + 10);

          AuthenticatedClientSessionModel updated =
              s.sessions()
                  .getUserSession(r, userSessionId)
                  .getAuthenticatedClientSessionByClient(clientUUID);
          assertEquals(
              AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
          assertEquals(time + 10, updated.getTimestamp());
          return null;
        });
  }

  @Test
  public void testUpdateClientSessionInSameTransaction() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          String userSessionId = sessions[0].getId();
          String clientUUID = r.getClientByClientId("test-app").getId();
          UserSessionModel userSession = s.sessions().getUserSession(r, userSessionId);
          AuthenticatedClientSessionModel clientSession =
              userSession.getAuthenticatedClientSessionByClient(clientUUID);

          clientSession.setAction(AuthenticatedClientSessionModel.Action.LOGGED_OUT.name());
          clientSession.setNote("foo", "bar");

          AuthenticatedClientSessionModel updated =
              s.sessions()
                  .getUserSession(r, userSessionId)
                  .getAuthenticatedClientSessionByClient(clientUUID);
          assertEquals(
              AuthenticatedClientSessionModel.Action.LOGGED_OUT.name(), updated.getAction());
          assertEquals("bar", updated.getNote("foo"));
          return null;
        });
  }

  @Test
  public void testGetUserSessions() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());
          assertSessions(
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user1"))
                  .collect(Collectors.toList()),
              sessions[0],
              sessions[1]);
          assertSessions(
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user2"))
                  .collect(Collectors.toList()),
              sessions[2]);
          return null;
        });
  }

  @Test
  public void testRemoveUserSessionsByUser() {
    withRealm(realmId, (s, r) -> createSessions(s, r.getId()));

    final Map<String, Integer> clientSessionsKept = new HashMap<>();
    withRealm(
        realmId,
        (s, r) -> {
          clientSessionsKept.putAll(
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user2"))
                  .collect(
                      Collectors.toMap(
                          UserSessionModel::getId,
                          model -> model.getAuthenticatedClientSessions().keySet().size())));

          s.sessions().removeUserSessions(r, s.users().getUserByUsername(r, "user1"));
          return null;
        });

    withRealm(
        realmId,
        (s, r) -> {
          assertEquals(
              0,
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user1"))
                  .count());
          List<UserSessionModel> userSessions =
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user2"))
                  .collect(Collectors.toList());

          assertSame(userSessions.size(), 1);

          for (UserSessionModel userSession : userSessions) {
            Assert.assertEquals(
                (int) clientSessionsKept.get(userSession.getId()),
                userSession.getAuthenticatedClientSessions().size());
          }
          return null;
        });
  }

  @Test
  public void testRemoveUserSession() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel userSession = createSessions(s, r.getId())[0];

          s.sessions()
              .removeUserSession(
                  r,
                  s.sessions()
                      .getUserSession(
                          r,
                          userSession
                              .getId())); /// seems the remove User session is not working. @xgp

          assertNull(s.sessions().getUserSession(r, userSession.getId()));
          return null;
        });
  }

  @Test
  public void testRemoveUserSessionsByRealm() {
    withRealm(
        realmId,
        (s, r) -> {
          createSessions(s, r.getId());
          s.sessions().removeUserSessions(r);

          assertEquals(
              0,
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user1"))
                  .count());
          assertEquals(
              0,
              s.sessions()
                  .getUserSessionsStream(r, s.users().getUserByUsername(r, "user2"))
                  .count());
          return null;
        });
  }

  @Test
  public void testOnClientRemoved() {
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel[] sessions = createSessions(s, r.getId());

          String thirdPartyClientUUID = r.getClientByClientId("third-party").getId();

          Map<String, Set<String>> clientSessionsKept = new HashMap<>();
          for (UserSessionModel session : sessions) {
            // session associated with the model was closed, load it by id into a new session
            session = s.sessions().getUserSession(r, session.getId());
            Set<String> clientUUIDS =
                new HashSet<>(session.getAuthenticatedClientSessions().keySet());
            clientUUIDS.remove(
                thirdPartyClientUUID); // This client will be later removed, hence his
            // clientSessions too
            clientSessionsKept.put(session.getId(), clientUUIDS);
          }

          r.removeClient(thirdPartyClientUUID);

          for (UserSessionModel session : sessions) {
            session = s.sessions().getUserSession(r, session.getId());
            Set<String> clientUUIDS = session.getAuthenticatedClientSessions().keySet();
            assertEquals(clientUUIDS, clientSessionsKept.get(session.getId()));
          }

          // Revert client
          r.addClient("third-party");
          return null;
        });
  }

  @Test
  public void testTransientUserSession() {
    String userSessionId = UUID.randomUUID().toString();
    // create an user session, but don't persist it to infinispan
    withRealm(
        realmId,
        (s, r) -> {
          ClientModel client = r.getClientByClientId("test-app");
          long sessionsBefore = s.sessions().getActiveUserSessions(r, client);

          UserSessionModel userSession =
              s.sessions()
                  .createUserSession(
                      userSessionId,
                      r,
                      s.users().getUserByUsername(r, "user1"),
                      "user1",
                      "127.0.0.1",
                      "form",
                      true,
                      null,
                      null,
                      UserSessionModel.SessionPersistenceState.TRANSIENT);
          AuthenticatedClientSessionModel clientSession =
              s.sessions().createClientSession(r, client, userSession);
          assertEquals(userSession, clientSession.getUserSession());

          assertSession(
              userSession,
              s.users().getUserByUsername(r, "user1"),
              "127.0.0.1",
              userSession.getStarted(),
              userSession.getStarted(),
              "test-app");

          // Can find session by ID in current transaction
          UserSessionModel foundSession = s.sessions().getUserSession(r, userSessionId);
          Assert.assertEquals(userSession, foundSession);

          // Count of sessions should be still the same
          Assert.assertEquals(sessionsBefore, s.sessions().getActiveUserSessions(r, client));
          return null;
        });

    // create an user session whose last refresh exceeds the max session idle timeout.
    withRealm(
        realmId,
        (s, r) -> {
          UserSessionModel userSession = s.sessions().getUserSession(r, userSessionId);
          Assert.assertNull(userSession);
          return null;
        });
  }

  @Test
  public void testCreateClientSessionWithWrappedTransientSession() {
    // Reproduces the token-exchange-external-internal:v2 failure where KC wraps the
    // RedisUserSessionAdapter in an anonymous UserSessionUtil$1 delegate before calling
    // createClientSession, causing the instanceof check in getUserSessionAdapter to fail.
    withRealm(
        realmId,
        (s, r) -> {
          UserModel user = s.users().getUserByUsername(r, "user1");
          ClientModel client = r.getClientByClientId("test-app");

          UserSessionModel transientSession =
              s.sessions()
                  .createUserSession(
                      null,
                      r,
                      user,
                      "user1",
                      "127.0.0.1",
                      "token-exchange",
                      false,
                      null,
                      null,
                      UserSessionModel.SessionPersistenceState.TRANSIENT);

          // Wrap in a plain delegate — not instanceof RedisUserSessionAdapter
          UserSessionModel wrapper =
              new UserSessionModel() {
                public String getId() {
                  return transientSession.getId();
                }

                public RealmModel getRealm() {
                  return transientSession.getRealm();
                }

                public UserSessionModel.SessionPersistenceState getPersistenceState() {
                  return transientSession.getPersistenceState();
                }

                public String getBrokerSessionId() {
                  return transientSession.getBrokerSessionId();
                }

                public String getBrokerUserId() {
                  return transientSession.getBrokerUserId();
                }

                public UserModel getUser() {
                  return transientSession.getUser();
                }

                public String getLoginUsername() {
                  return transientSession.getLoginUsername();
                }

                public String getIpAddress() {
                  return transientSession.getIpAddress();
                }

                public String getAuthMethod() {
                  return transientSession.getAuthMethod();
                }

                public boolean isRememberMe() {
                  return transientSession.isRememberMe();
                }

                public int getStarted() {
                  return transientSession.getStarted();
                }

                public int getLastSessionRefresh() {
                  return transientSession.getLastSessionRefresh();
                }

                public void setLastSessionRefresh(int seconds) {
                  transientSession.setLastSessionRefresh(seconds);
                }

                public boolean isOffline() {
                  return transientSession.isOffline();
                }

                public Map<String, AuthenticatedClientSessionModel>
                    getAuthenticatedClientSessions() {
                  return transientSession.getAuthenticatedClientSessions();
                }

                public void removeAuthenticatedClientSessions(
                    Collection<String> removedClientUUIDS) {
                  transientSession.removeAuthenticatedClientSessions(removedClientUUIDS);
                }

                public String getNote(String name) {
                  return transientSession.getNote(name);
                }

                public void setNote(String name, String value) {
                  transientSession.setNote(name, value);
                }

                public void removeNote(String name) {
                  transientSession.removeNote(name);
                }

                public Map<String, String> getNotes() {
                  return transientSession.getNotes();
                }

                public UserSessionModel.State getState() {
                  return transientSession.getState();
                }

                public void setState(UserSessionModel.State state) {
                  transientSession.setState(state);
                }

                public void restartSession(
                    RealmModel realm,
                    UserModel user,
                    String loginUsername,
                    String ipAddress,
                    String authMethod,
                    boolean rememberMe,
                    String brokerSessionId,
                    String brokerUserId) {
                  transientSession.restartSession(
                      realm,
                      user,
                      loginUsername,
                      ipAddress,
                      authMethod,
                      rememberMe,
                      brokerSessionId,
                      brokerUserId);
                }
              };

          AuthenticatedClientSessionModel clientSession =
              s.sessions().createClientSession(r, client, wrapper);

          assertNotNull(clientSession);
          assertEquals(client.getId(), clientSession.getClient().getId());
          return null;
        });
  }

  @Test
  public void testGetByClient() {
    withRealm(
        realmId,
        (s, r) -> {
          final UserSessionModel[] sessions = createSessions(s, realmId);

          KeycloakModelUtils.runJobInTransaction(
              s.getKeycloakSessionFactory(),
              (KeycloakSession kcSession) -> {
                assertSessions(
                    kcSession
                        .sessions()
                        .getUserSessionsStream(r, r.getClientByClientId("test-app"))
                        .collect(Collectors.toList()),
                    sessions[0],
                    sessions[1],
                    sessions[2]);
                assertSessions(
                    kcSession
                        .sessions()
                        .getUserSessionsStream(r, r.getClientByClientId("third-party"))
                        .collect(Collectors.toList()),
                    sessions[0]);
              });
          return null;
        });
  }

  @Test
  public void testGetByClientPaginated() {
    withRealm(
        realmId,
        (s, r) -> {
          RealmModel realm = s.realms().getRealmByName("test");

          try {
            for (int i = 0; i < 25; i++) {
              Time.setOffset(i);
              UserSessionModel userSession =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0." + i,
                          "form",
                          false,
                          null,
                          null);
              AuthenticatedClientSessionModel clientSession =
                  s.sessions()
                      .createClientSession(
                          realm, realm.getClientByClientId("test-app"), userSession);
              assertNotNull(clientSession);
              clientSession.setRedirectUri("http://redirect");
              clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, "state");
              clientSession.setTimestamp(userSession.getStarted());
              userSession.setLastSessionRefresh(userSession.getStarted());
            }
          } finally {
            Time.setOffset(0);
          }
          return null;
        });

    withRealm(
        realmId,
        (s, r) -> {
          assertPaginatedSession(s, r, r.getClientByClientId("test-app"), 0, 1, 1);
          assertPaginatedSession(s, r, r.getClientByClientId("test-app"), 0, 10, 10);
          assertPaginatedSession(s, r, r.getClientByClientId("test-app"), 10, 10, 10);
          assertPaginatedSession(s, r, r.getClientByClientId("test-app"), 20, 10, 5);
          assertPaginatedSession(s, r, r.getClientByClientId("test-app"), 30, 10, 0);
          return null;
        });
  }

  @Test
  public void testCreateAndGetInSameTransaction() {
    withRealm(
        realmId,
        (s, r) -> {
          RealmModel realm = s.realms().getRealmByName("test");
          ClientModel client = realm.getClientByClientId("test-app");
          UserSessionModel userSession =
              s.sessions()
                  .createUserSession(
                      realm,
                      s.users().getUserByUsername(realm, "user1"),
                      "user1",
                      "127.0.0.2",
                      "form",
                      true,
                      null,
                      null);
          AuthenticatedClientSessionModel clientSession =
              createClientSession(s, realmId, client, userSession, "http://redirect", "state");

          UserSessionModel userSessionLoaded =
              s.sessions().getUserSession(realm, userSession.getId());
          AuthenticatedClientSessionModel clientSessionLoaded =
              userSessionLoaded.getAuthenticatedClientSessions().get(client.getId());
          Assert.assertNotNull(userSessionLoaded);
          Assert.assertNotNull(clientSessionLoaded);

          Assert.assertEquals(userSession.getId(), clientSessionLoaded.getUserSession().getId());
          Assert.assertEquals(1, userSessionLoaded.getAuthenticatedClientSessions().size());
          return null;
        });
  }

  @Test
  public void testAuthenticatedClientSessions() {
    withRealm(
        realmId,
        (s, r) -> {
          RealmModel realm = s.realms().getRealmByName("test");
          realm.setSsoSessionIdleTimeout(1800);
          realm.setSsoSessionMaxLifespan(36000);
          UserSessionModel userSession =
              s.sessions()
                  .createUserSession(
                      realm,
                      s.users().getUserByUsername(realm, "user1"),
                      "user1",
                      "127.0.0.2",
                      "form",
                      true,
                      null,
                      null);

          ClientModel client1 = realm.getClientByClientId("test-app");
          ClientModel client2 = realm.getClientByClientId("third-party");

          // Create client1 session
          AuthenticatedClientSessionModel clientSession1 =
              s.sessions().createClientSession(realm, client1, userSession);
          clientSession1.setAction("foo1");
          int currentTime1 = Time.currentTime();
          clientSession1.setTimestamp(currentTime1);

          // Create client2 session
          AuthenticatedClientSessionModel clientSession2 =
              s.sessions().createClientSession(realm, client2, userSession);
          clientSession2.setAction("foo2");
          int currentTime2 = Time.currentTime();
          clientSession2.setTimestamp(currentTime2);

          // Ensure sessions are here
          userSession = s.sessions().getUserSession(realm, userSession.getId());
          Map<String, AuthenticatedClientSessionModel> clientSessions =
              userSession.getAuthenticatedClientSessions();
          Assert.assertEquals(2, clientSessions.size());
          testAuthenticatedClientSession(
              clientSessions.get(client1.getId()),
              "test-app",
              userSession.getId(),
              "foo1",
              currentTime1);
          testAuthenticatedClientSession(
              clientSessions.get(client2.getId()),
              "third-party",
              userSession.getId(),
              "foo2",
              currentTime2);

          // Update session1
          clientSessions.get(client1.getId()).setAction("foo1-updated");

          // Ensure updated
          userSession = s.sessions().getUserSession(realm, userSession.getId());
          clientSessions = userSession.getAuthenticatedClientSessions();
          testAuthenticatedClientSession(
              clientSessions.get(client1.getId()),
              "test-app",
              userSession.getId(),
              "foo1-updated",
              currentTime1);

          // Rewrite session2
          clientSession2 = s.sessions().createClientSession(realm, client2, userSession);
          clientSession2.setAction("foo2-rewrited");
          int currentTime3 = Time.currentTime();
          clientSession2.setTimestamp(currentTime3);

          // Ensure updated
          userSession = s.sessions().getUserSession(realm, userSession.getId());
          clientSessions = userSession.getAuthenticatedClientSessions();
          Assert.assertEquals(2, clientSessions.size());
          testAuthenticatedClientSession(
              clientSessions.get(client1.getId()),
              "test-app",
              userSession.getId(),
              "foo1-updated",
              currentTime1);
          testAuthenticatedClientSession(
              clientSessions.get(client2.getId()),
              "third-party",
              userSession.getId(),
              "foo2-rewrited",
              currentTime3);

          // remove session
          clientSession1 = userSession.getAuthenticatedClientSessions().get(client1.getId());
          clientSession1.detachFromUserSession();

          userSession = s.sessions().getUserSession(realm, userSession.getId());
          clientSessions = userSession.getAuthenticatedClientSessions();
          Assert.assertEquals(1, clientSessions.size());
          Assert.assertNull(clientSessions.get(client1.getId()));
          return null;
        });
  }

  private static void testAuthenticatedClientSession(
      AuthenticatedClientSessionModel clientSession,
      String expectedClientId,
      String expectedUserSessionId,
      String expectedAction,
      int expectedTimestamp) {
    Assert.assertEquals(expectedClientId, clientSession.getClient().getClientId());
    Assert.assertEquals(expectedUserSessionId, clientSession.getUserSession().getId());
    Assert.assertEquals(expectedAction, clientSession.getAction());
    Assert.assertEquals(expectedTimestamp, clientSession.getTimestamp());
  }

  private static void assertPaginatedSession(
      KeycloakSession session,
      RealmModel realm,
      ClientModel client,
      int start,
      int max,
      int expectedSize) {
    assertEquals(
        expectedSize, session.sessions().getUserSessionsStream(realm, client, start, max).count());
  }

  // Own Tests
  @Test
  public void testUserSessionNotes() {
    String sessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              UserModel testuser = s.users().getUserByUsername(realm, "user1");
              UserSessionModel session =
                  s.sessions()
                      .createUserSession(
                          realm, testuser, "testuser", "127.0.0.1", "test", false, null, null);
              session.setNote("key1", "value1");
              session.setNote("key2", "value2");

              UserSessionModel newlyLoadedSession =
                  s.sessions().getUserSession(realm, session.getId());
              newlyLoadedSession.setNote("key3", "value3");

              UserSessionModel currentSession = s.sessions().getUserSession(realm, session.getId());
              assertThat(currentSession.getNotes().entrySet(), hasSize(3));
              assertThat(currentSession.getNotes().get("key1"), equalTo("value1"));
              assertThat(currentSession.getNotes().get("key2"), equalTo("value2"));
              assertThat(currentSession.getNotes().get("key3"), equalTo("value3"));

              return session.getId();
            });

    // New transaction
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel testuser = s.users().getUserByUsername(realm, "user1");
          UserSessionModel session = s.sessions().getUserSession(realm, sessionId);
          session.setNote("key4", "value4");

          UserSessionModel currentSession = s.sessions().getUserSession(realm, sessionId);
          assertThat(currentSession.getNotes().entrySet(), hasSize(4));
          assertThat(currentSession.getNotes().get("key1"), equalTo("value1"));
          assertThat(currentSession.getNotes().get("key2"), equalTo("value2"));
          assertThat(currentSession.getNotes().get("key3"), equalTo("value3"));
          assertThat(currentSession.getNotes().get("key4"), equalTo("value4"));

          return null;
        });
  }

  @Test
  public void testClientSessionToUserSessionReference() {
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel testuser = s.users().getUserByUsername(realm, "user1");
          ClientModel client = s.clients().addClient(realm, "testclient");
          UserSessionModel session =
              s.sessions()
                  .createUserSession(
                      realm, testuser, "testuser", "127.0.0.1", "test", false, null, null);
          session.setNote("key1", "value1");

          AuthenticatedClientSessionModel clientSession =
              s.sessions().createClientSession(realm, client, session);
          clientSession.setNote("ckey", "cval");

          session.setNote("key2", "value2");
          clientSession.getUserSession().setNote("key3", "value3");

          UserSessionModel currentSession = s.sessions().getUserSession(realm, session.getId());
          assertThat(currentSession.getNotes().entrySet(), hasSize(3));
          assertThat(currentSession.getNotes().get("key1"), equalTo("value1"));
          assertThat(currentSession.getNotes().get("key2"), equalTo("value2"));
          assertThat(currentSession.getNotes().get("key3"), equalTo("value3"));

          return session.getId();
        });
  }

  @Test
  public void testBrokerUserSessions() {
    withRealm(
        realmId,
        (s, realm) -> {
          UserModel testuser = s.users().getUserByUsername(realm, "user1");
          UserSessionModel session =
              s.sessions()
                  .createUserSession(
                      realm,
                      testuser,
                      "testuser",
                      "127.0.0.1",
                      "test",
                      false,
                      "brokerSession",
                      "brokerUserId");

          UserSessionModel currentSession =
              s.sessions().getUserSessionByBrokerSessionId(realm, "brokerSession");
          assertThat(currentSession.getBrokerSessionId(), is("brokerSession"));
          assertThat(currentSession.getBrokerUserId(), is("brokerUserId"));

          List<UserSessionModel> brokerSessions =
              s.sessions()
                  .getUserSessionByBrokerUserIdStream(realm, "brokerUserId")
                  .collect(Collectors.toList());
          assertThat(brokerSessions, hasSize(1));
          assertThat(brokerSessions.get(0).getBrokerSessionId(), is("brokerSession"));
          assertThat(brokerSessions.get(0).getBrokerUserId(), is("brokerUserId"));

          UserSessionModel sessionByPredicate =
              s.sessions()
                  .getUserSessionWithPredicate(
                      realm,
                      session.getId(),
                      false,
                      s2 -> s2.getBrokerUserId().equals("brokerUserId"));
          assertThat(sessionByPredicate.getBrokerSessionId(), is("brokerSession"));
          assertThat(sessionByPredicate.getBrokerUserId(), is("brokerUserId"));

          return null;
        });

    withRealm(
        realmId,
        (s, realm) -> {
          UserModel testuser = s.users().getUserByUsername(realm, "user1");
          UserSessionModel session =
              s.sessions()
                  .createUserSession(
                      realm,
                      testuser,
                      "testuser",
                      "127.0.0.1",
                      "test",
                      false,
                      "brokerSession",
                      "brokerUserId");
          UserSessionModel offlineSession = s.sessions().createOfflineUserSession(session);

          UserSessionModel currentSession =
              s.sessions().getUserSessionByBrokerSessionId(realm, "brokerSession");
          assertThat(currentSession.getBrokerSessionId(), is("brokerSession"));
          assertThat(currentSession.getBrokerUserId(), is("brokerUserId"));

          List<UserSessionModel> brokerSessions =
              s.sessions()
                  .getOfflineUserSessionByBrokerUserIdStream(realm, "brokerUserId")
                  .collect(Collectors.toList());
          assertThat(brokerSessions, hasSize(1));
          assertThat(brokerSessions.get(0).getBrokerSessionId(), is("brokerSession"));
          assertThat(brokerSessions.get(0).getBrokerUserId(), is("brokerUserId"));

          UserSessionModel sessionByPredicate =
              s.sessions()
                  .getUserSessionWithPredicate(
                      realm,
                      offlineSession.getId(),
                      true,
                      s2 -> s2.getBrokerUserId().equals("brokerUserId"));
          assertThat(sessionByPredicate.getBrokerSessionId(), is("brokerSession"));
          assertThat(sessionByPredicate.getBrokerUserId(), is("brokerUserId"));

          return session.getId();
        });
  }

  @Test
  public void testActiveClientSessionStats() {
    withRealm(
        realmId,
        (s, r) -> {
          RealmModel realm = s.realms().getRealmByName("test");
          realm.setSsoSessionIdleTimeout(1800);
          realm.setSsoSessionMaxLifespan(36000);
          UserSessionModel userSession =
              s.sessions()
                  .createUserSession(
                      realm,
                      s.users().getUserByUsername(realm, "user1"),
                      "user1",
                      "127.0.0.2",
                      "form",
                      true,
                      null,
                      null);

          ClientModel client1 = realm.getClientByClientId("test-app");
          ClientModel client2 = realm.getClientByClientId("third-party");

          // Create client1 session
          AuthenticatedClientSessionModel clientSession1 =
              s.sessions().createClientSession(realm, client1, userSession);
          clientSession1.setAction("foo1");
          int currentTime1 = Time.currentTime();
          clientSession1.setTimestamp(currentTime1);

          // Create client2 session
          AuthenticatedClientSessionModel clientSession2 =
              s.sessions().createClientSession(realm, client2, userSession);
          clientSession2.setAction("foo2");
          int currentTime2 = Time.currentTime();
          clientSession2.setTimestamp(currentTime2);

          return null;
        });

    withRealm(
        realmId,
        (s, r) -> {
          RealmModel realm = s.realms().getRealmByName("test");
          ClientModel client1 = realm.getClientByClientId("test-app");
          ClientModel client2 = realm.getClientByClientId("third-party");

          Map<String, Long> stats = s.sessions().getActiveClientSessionStats(realm, false);
          assertThat(stats.entrySet(), hasSize(2));
          assertThat(stats.get(client1.getId()), is(1L));
          assertThat(stats.get(client2.getId()), is(1L));

          return null;
        });
  }

  @Test
  public void testRemoveSessions() {
    String sessionId =
        withRealm(
            realmId,
            (s, realm) ->
                s.sessions()
                    .createUserSession(
                        realm,
                        s.users().getUserByUsername(realm, "user1"),
                        "user1",
                        "127.0.0.2",
                        "form",
                        true,
                        null,
                        null)
                    .getId());
    withRealm(realmId, (s, realm) -> s.clients().addClient(realm, "clientId"));

    withRealm(
        realmId,
        (s, realm) -> {
          assertNotNull(s.sessions().getUserSession(realm, sessionId));
          s.sessions().removeUserSessions(realm, s.users().getUserByUsername(realm, "user1"));

          return null;
        });

    String session2Id =
        withRealm(
            realmId,
            (s, realm) -> {
              assertNull(s.sessions().getUserSession(realm, sessionId));

              UserSessionModel userSession =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.2",
                          "form",
                          true,
                          null,
                          null);
              ClientModel client1 = realm.getClientByClientId("clientId");

              s.sessions().createClientSession(realm, client1, userSession);
              return userSession.getId();
            });

    withRealm(
        realmId,
        (s, realm) -> {
          assertNotNull(s.sessions().getUserSession(realm, session2Id));
          s.sessions().onClientRemoved(realm, realm.getClientByClientId("clientId"));

          return null;
        });

    String offlineSessionId =
        withRealm(
            realmId,
            (s, realm) -> {
              assertNull(s.sessions().getUserSession(realm, session2Id));

              UserSessionModel userSession =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.2",
                          "form",
                          true,
                          null,
                          null);
              return s.sessions().createOfflineUserSession(userSession).getId();
            });

    withRealm(
        realmId,
        (s, realm) -> {
          UserSessionModel offlineUserSession =
              s.sessions().getOfflineUserSession(realm, offlineSessionId);
          assertTrue(offlineUserSession.isOffline());

          s.sessions().removeOfflineUserSession(realm, offlineUserSession);

          return null;
        });

    String offlineSessionId2 =
        withRealm(
            realmId,
            (s, realm) -> {
              UserSessionModel offlineUserSession =
                  s.sessions().getOfflineUserSession(realm, offlineSessionId);
              assertNull(offlineUserSession); // Returned corresponding live session

              UserSessionModel userSession =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.2",
                          "form",
                          true,
                          null,
                          null);
              return s.sessions().createOfflineUserSession(userSession).getId();
            });

    withRealm(
        realmId,
        (s, realm) -> {
          UserSessionModel offlineUserSession =
              s.sessions().getOfflineUserSession(realm, offlineSessionId2);
          assertTrue(offlineUserSession.isOffline());

          s.sessions()
              .removeOfflineUserSession(
                  realm,
                  s.sessions()
                      .getUserSession(
                          realm,
                          offlineUserSession.getNote(UserSessionModel.CORRESPONDING_SESSION_ID)));

          return null;
        });

    withRealm(
        realmId,
        (s, realm) -> {
          UserSessionModel offlineUserSession =
              s.sessions().getOfflineUserSession(realm, offlineSessionId2);
          assertNull(offlineUserSession); // Returned corresponding live session

          return null;
        });
  }

  @SuppressWarnings("removal")
  @Test
  @Ignore("Not used")
  public void testImportUserSessions() {
    withRealm(realmId, (s, realm) -> s.clients().addClient(realm, "clientId"));
    UserSessionModel userSession1 =
        withRealm(
            realmId,
            (s, realm) -> {
              UserSessionModel model =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user1"),
                          "user1",
                          "127.0.0.2",
                          "form",
                          true,
                          null,
                          null);
              s.sessions()
                  .createClientSession(
                      realm, s.clients().getClientByClientId(realm, "clientId"), model);

              return model;
            });

    UserSessionModel userSession2 =
        withRealm(
            realmId,
            (s, realm) -> {
              UserSessionModel model =
                  s.sessions()
                      .createUserSession(
                          realm,
                          s.users().getUserByUsername(realm, "user2"),
                          "user2",
                          "127.0.0.2",
                          "form",
                          true,
                          null,
                          null);
              s.sessions()
                  .createClientSession(
                      realm, s.clients().getClientByClientId(realm, "clientId"), model);

              return model;
            });

    withRealm(
        realmId,
        (s, realm) -> {
          s.sessions().removeUserSessions(realm);
          return null;
        });

    withRealm(
        realmId,
        (s, realm) -> {
          assertThat(
              s.sessions()
                  .getUserSessionsStream(realm, s.users().getUserByUsername(realm, "user1"))
                  .collect(Collectors.toList()),
              hasSize(0));
          assertThat(
              s.sessions()
                  .getUserSessionsStream(realm, s.users().getUserByUsername(realm, "user2"))
                  .collect(Collectors.toList()),
              hasSize(0));

          s.sessions().importUserSessions(Arrays.asList(userSession1, userSession2), false);
          return null;
        });

    withRealm(
        realmId,
        (s, realm) -> {
          assertThat(
              s.sessions()
                  .getUserSessionsStream(realm, s.users().getUserByUsername(realm, "user1"))
                  .collect(Collectors.toList()),
              hasSize(1));
          assertThat(
              s.sessions()
                  .getUserSessionsStream(realm, s.users().getUserByUsername(realm, "user2"))
                  .collect(Collectors.toList()),
              hasSize(1));
          Map<String, Long> stats = s.sessions().getActiveClientSessionStats(realm, false);
          assertThat(stats.get(s.clients().getClientByClientId(realm, "clientId").getId()), is(2L));

          return null;
        });
  }

  // Ignore versions
  public boolean areEntitiesEqual(UserSessionModel entity1, UserSessionModel entity2) {
    if (entity1 == entity2) return true;
    if (entity1 == null || entity2 == null) return false;

    return // Objects.equals(entity1.getRealm().getId(), entity2.getRealm().getId()) && - session
    // closed exception
    Objects.equals(entity1.getLoginUsername(), entity2.getLoginUsername())
        && Objects.equals(entity1.getBrokerUserId(), entity2.getBrokerUserId())
        && Objects.equals(entity1.getIpAddress(), entity2.getIpAddress())
        &&
        // Objects.equals(entity1.getUser().getId(), entity2.getUser().getId()) && - session closed
        // exception
        Objects.equals(entity1.getLastSessionRefresh(), entity2.getLastSessionRefresh())
        && entity1.isOffline() == entity2.isOffline()
        && Objects.equals(entity1.getBrokerSessionId(), entity2.getBrokerSessionId())
        && Objects.equals(entity1.getId(), entity2.getId())
        && entity1.isRememberMe() == entity2.isRememberMe()
        && Objects.equals(entity1.getAuthMethod(), entity2.getAuthMethod())
        && Objects.equals(entity1.getStarted(), entity2.getStarted())
        && Objects.equals(entity1.getPersistenceState(), entity2.getPersistenceState());
  }
}
