package io.phasetwo.keycloak.redis.testsuite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.phasetwo.keycloak.redis.revokedToken.RedisRevokedTokenProvider;
import java.util.UUID;
import org.junit.Test;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Keycloak 26.7 split revoked tokens out of {@code SingleUseObjectProvider} into their own SPI.
 * This asserts the extension serves that SPI from Redis rather than letting it fall through to JPA.
 */
public class RevokedTokenModelTest extends KeycloakModelTest {

  private String realmId;

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "realm");
    s.getContext().setRealm(realm);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realmId = realm.getId();
  }

  @Override
  public void cleanEnvironment(KeycloakSession s) {
    RealmModel realm = s.realms().getRealm(realmId);
    s.getContext().setRealm(realm);
    s.realms().removeRealm(realmId);
  }

  @Test
  public void testRevokedTokensAreServedByRedis() {
    withRealm(
        realmId,
        (session, realm) -> {
          assertTrue(
              "revokedTokens() should resolve to the Redis provider, not the JPA fallback",
              session.revokedTokens() instanceof RedisRevokedTokenProvider);
          return null;
        });
  }

  @Test
  public void testPutIsSingleUseAndContainsRoundTrips() {
    String id = UUID.randomUUID().toString();

    withRealm(
        realmId,
        (session, realm) -> {
          assertFalse(session.revokedTokens().contains(id));
          assertTrue(session.revokedTokens().put(id, 60));
          return null;
        });

    withRealm(
        realmId,
        (session, realm) -> {
          assertTrue(
              "revocation should survive the transaction", session.revokedTokens().contains(id));
          assertFalse(
              "a second revocation of the same id must not succeed",
              session.revokedTokens().put(id, 60));
          return null;
        });
  }

  @Test
  public void testUnknownTokenIsNotRevoked() {
    withRealm(
        realmId,
        (session, realm) -> {
          assertFalse(session.revokedTokens().contains(UUID.randomUUID().toString()));
          return null;
        });
  }
}
