package io.phasetwo.keycloak.redis.authSession;

import io.phasetwo.keycloak.redis.AdapterSupplier;
import java.util.Map;
import org.keycloak.models.KeycloakSession;
import redis.clients.jedis.UnifiedJedis;

public class AuthenticationSessionAdapterSupplier
    implements AdapterSupplier<AuthenticationSessionKey, RedisAuthenticationSessionAdapter> {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;

  public AuthenticationSessionAdapterSupplier(KeycloakSession session, UnifiedJedis jedis) {
    this.session = session;
    this.jedis = jedis;
  }

  @Override
  public RedisAuthenticationSessionAdapter newInstance(AuthenticationSessionKey key) {
    return new RedisAuthenticationSessionAdapter(session, key);
  }

  @Override
  public RedisAuthenticationSessionAdapter newInstance(
      AuthenticationSessionKey key, Map<String, String> data) {
    return new RedisAuthenticationSessionAdapter(session, key, data);
  }
}
