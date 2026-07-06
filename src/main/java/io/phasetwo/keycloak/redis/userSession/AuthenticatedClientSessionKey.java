package io.phasetwo.keycloak.redis.userSession;

import io.phasetwo.keycloak.redis.Key;
import io.phasetwo.keycloak.redis.KeyFormat;

/**
 * Client-session value key. The model-level {@code id} stays the historical
 * composite {@code <sid>::<clientUuid>}; rendering is {@link KeyFormat}-driven:
 * DEFAULT {@code authenticated-client:<sid>::<clientUuid>}, SERVERLESS
 * {@code s:{<realm>}:cs:<sid>:<clientUuid>} (single colon — session ids and
 * client uuids never contain colons). Equality follows the rendered key (see
 * {@link UserSessionKey}).
 */
public record AuthenticatedClientSessionKey(String realmId, String id) implements Key {
  @Override
  public String key() {
    return KeyFormat.clientSession(realmId, id);
  }

  public static AuthenticatedClientSessionKey fromString(String strKey) {
    if (KeyFormat.isServerlessKey(strKey)) {
      String[] p = KeyFormat.parseServerless(strKey, "cs");
      int sep = p[1].indexOf(':');
      if (sep < 0) {
        throw new IllegalArgumentException(
            "Expected format: s:{<realm>}:cs:<sid>:<clientUuid>, got: " + strKey);
      }
      return new AuthenticatedClientSessionKey(
          p[0], p[1].substring(0, sep) + "::" + p[1].substring(sep + 1));
    }
    if (strKey == null || !strKey.startsWith("authenticated-client:")) {
      throw new IllegalArgumentException("Invalid key format: " + strKey);
    }
    return new AuthenticatedClientSessionKey(
        "", strKey.substring("authenticated-client:".length()));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AuthenticatedClientSessionKey other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}
