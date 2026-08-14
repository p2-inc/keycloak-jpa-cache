package io.phasetwo.keycloak.redis.testsuite.session;

import static io.phasetwo.keycloak.redis.testsuite.session.SessionTestUtils.createClients;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.connection.DefaultRedisConnectionProviderFactory;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.jbosslog.JBossLog;
import org.junit.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import redis.clients.jedis.UnifiedJedis;

/**
 * Every key an authentication session creates must carry a TTL.
 *
 * <p>This is a regression guard with a measured motive. The per-tab session hash was written with
 * no expiry at all: {@code RedisAuthenticationSessionAdapter} did not implement {@code
 * ExpirableEntity}, so the {@code PEXPIREAT} was skipped, the root expired without it, and the tab
 * survived forever. On a live deployment that was 57,419 orphaned tabs holding 110 MB — the largest
 * single consumer in the keyspace — plus their parent-index members. Nothing could evict any of it,
 * because the default {@code volatile-lru} policy only considers keys that have a TTL.
 *
 * <p>So the assertion is deliberately blunt: TTL present, and bounded by the root's lifespan.
 */
@JBossLog
public class AuthSessionExpirationTest extends KeycloakModelTest {

  private static final int LIFESPAN_SECONDS = 1800;

  private String realmId;

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "authexp");
    s.getContext().setRealm(realm);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realm.setAccessCodeLifespanLogin(LIFESPAN_SECONDS);
    this.realmId = realm.getId();
    createClients(s, realm);
  }

  @Override
  public void cleanEnvironment(KeycloakSession s) {
    RealmModel realm = s.realms().getRealm(realmId);
    s.getContext().setRealm(realm);
    s.realms().removeRealm(realmId);
  }

  @Test
  public void tabHashAndParentIndexBothExpire() {
    AtomicReference<String> rootId = new AtomicReference<>();
    AtomicReference<String> tabId = new AtomicReference<>();
    AtomicReference<String> clientUuid = new AtomicReference<>();

    withRealm(
        realmId,
        (session, realm) -> {
          RootAuthenticationSessionModel root =
              session.authenticationSessions().createRootAuthenticationSession(realm);
          ClientModel client = realm.getClientByClientId("test-app");
          AuthenticationSessionModel tab = root.createAuthenticationSession(client);
          rootId.set(root.getId());
          tabId.set(tab.getTabId());
          clientUuid.set(client.getId());
          return null;
        });

    UnifiedJedis jedis = DefaultRedisConnectionProviderFactory.getJedis();

    String rootKey = KeyFormat.rootAuthSession(realmId, rootId.get());
    String tabKey =
        KeyFormat.authSessionTab(realmId, rootId.get(), clientUuid.get(), tabId.get());
    String parentIndexKey = KeyFormat.authSessionParentIndex(realmId, rootId.get());

    assertHasBoundedTtl(jedis, rootKey, "root auth session");
    assertHasBoundedTtl(jedis, tabKey, "auth session tab");
    assertHasBoundedTtl(jedis, parentIndexKey, "auth session parent index");
  }

  /**
   * {@code TTL} returns -2 when the key is gone and -1 when it exists with no expiry — the second is
   * the leak this guards against, and is easy to misread as "fine" because the key is present and
   * the data is correct.
   */
  private static void assertHasBoundedTtl(UnifiedJedis jedis, String key, String what) {
    long ttl = jedis.ttl(key);
    assertThat(what + " (" + key + ") should exist", ttl, greaterThan(-2L));
    assertThat(what + " (" + key + ") must carry a TTL, got " + ttl, ttl, greaterThan(0L));
    assertThat(
        what + " (" + key + ") TTL should not exceed the auth-session lifespan",
        ttl,
        lessThanOrEqualTo((long) LIFESPAN_SECONDS));
  }
}
