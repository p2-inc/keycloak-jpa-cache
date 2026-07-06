package io.phasetwo.keycloak.redis.loginFailure;

import io.phasetwo.keycloak.redis.Key;
import io.phasetwo.keycloak.redis.KeyFormat;

/**
 * Login-failure value key. DEFAULT {@code login-failure:<realmId>:<userId>}
 * (historical), SERVERLESS {@code s:{<realm>}:lf:<userId>}. Equality follows
 * the rendered key.
 */
public record LoginFailureKey(String realmId, String userId) implements Key {
  @Override
  public String key() {
    return KeyFormat.loginFailure(realmId, userId);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof LoginFailureKey other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}
