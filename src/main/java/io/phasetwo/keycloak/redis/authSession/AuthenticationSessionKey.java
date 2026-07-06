package io.phasetwo.keycloak.redis.authSession;

import io.phasetwo.keycloak.redis.Key;
import io.phasetwo.keycloak.redis.KeyFormat;

/**
 * Per-tab authentication session key. DEFAULT keeps the historical
 * {@code auth-session:<clientId>:<tabId>} layout (see the collision note on
 * {@link KeyFormat#authSessionTab}); SERVERLESS scopes under the root session:
 * {@code s:{<realm>}:ast:<rootId>:<tabId>}. The record carries all four parts
 * so either format can render; parsing fills only the parts its format
 * encodes. Equality follows the rendered key.
 */
public record AuthenticationSessionKey(String realmId, String rootId, String clientId, String tabId)
    implements Key {
  @Override
  public String key() {
    return KeyFormat.authSessionTab(realmId, rootId, clientId, tabId);
  }

  public static AuthenticationSessionKey fromString(String strKey) {
    if (KeyFormat.isServerlessKey(strKey)) {
      String[] p = KeyFormat.parseServerless(strKey, "ast");
      int sep = p[1].lastIndexOf(':');
      if (sep < 0) {
        throw new IllegalArgumentException(
            "Expected format: s:{<realm>}:ast:<rootId>:<tabId>, got: " + strKey);
      }
      return new AuthenticationSessionKey(
          p[0], p[1].substring(0, sep), "", p[1].substring(sep + 1));
    }
    if (strKey == null || !strKey.startsWith("auth-session:")) {
      throw new IllegalArgumentException("Invalid key format: " + strKey);
    }
    String[] parts = strKey.split(":", 3); // limit = 3 to handle extra colons in tabId safely
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "Expected format: auth-session:<clientId>:<tabId>, got: " + strKey);
    }
    return new AuthenticationSessionKey("", "", parts[1], parts[2]);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AuthenticationSessionKey other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}
