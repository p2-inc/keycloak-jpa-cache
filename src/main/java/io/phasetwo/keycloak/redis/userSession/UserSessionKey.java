package io.phasetwo.keycloak.redis.userSession;

import io.phasetwo.keycloak.redis.Key;
import io.phasetwo.keycloak.redis.KeyFormat;

/**
 * User-session value key. Rendering is {@link KeyFormat}-driven: DEFAULT
 * {@code user-session:<id>} (historical, realm not rendered), SERVERLESS
 * {@code s:{<realm>}:us:<id>}.
 *
 * <p>Equality follows the RENDERED key, not the record fields: in DEFAULT
 * mode a key parsed from an index member carries no realm, and it must still
 * hit the changelog-transaction cache entry created with the realm present.
 */
public record UserSessionKey(String realmId, String id) implements Key {
  @Override
  public String key() {
    return KeyFormat.userSession(realmId, id);
  }

  public static UserSessionKey fromString(String strKey) {
    if (KeyFormat.isServerlessKey(strKey)) {
      String[] p = KeyFormat.parseServerless(strKey, "us");
      return new UserSessionKey(p[0], p[1]);
    }
    if (strKey == null || !strKey.startsWith("user-session:")) {
      throw new IllegalArgumentException("Invalid key format: " + strKey);
    }
    return new UserSessionKey("", strKey.substring("user-session:".length()));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof UserSessionKey other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}
