package io.phasetwo.keycloak.redis.authSession;

import io.phasetwo.keycloak.redis.Key;
import io.phasetwo.keycloak.redis.KeyFormat;

/**
 * Root-authentication-session value key. DEFAULT
 * {@code root-auth-session:<realmId>:<id>} (historical), SERVERLESS
 * {@code s:{<realm>}:as:<id>}. Equality follows the rendered key.
 */
public record RootAuthenticationSessionKey(String realmId, String id) implements Key {
  @Override
  public String key() {
    return KeyFormat.rootAuthSession(realmId, id);
  }

  public static RootAuthenticationSessionKey fromString(String strKey) {
    if (KeyFormat.isServerlessKey(strKey)) {
      String[] p = KeyFormat.parseServerless(strKey, "as");
      return new RootAuthenticationSessionKey(p[0], p[1]);
    }
    if (strKey == null || !strKey.startsWith("root-auth-session:")) {
      throw new IllegalArgumentException("Invalid key format: " + strKey);
    }
    String[] parts = strKey.split(":", 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "Expected format: root-auth-session:<realmId>:<id>, got: " + strKey);
    }
    return new RootAuthenticationSessionKey(parts[1], parts[2]);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RootAuthenticationSessionKey other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}
