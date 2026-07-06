package io.phasetwo.keycloak.redis.userSession;

import io.phasetwo.keycloak.redis.AdapterSupplier;
import java.util.Map;
import org.keycloak.models.KeycloakSession;
import redis.clients.jedis.UnifiedJedis;

public class AuthenticatedClientSessionAdapterSupplier
    implements AdapterSupplier<
        AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter> {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;

  public AuthenticatedClientSessionAdapterSupplier(KeycloakSession session, UnifiedJedis jedis) {
    this.session = session;
    this.jedis = jedis;
  }

  @Override
  public RedisAuthenticatedClientSessionAdapter newInstance(AuthenticatedClientSessionKey key) {
    return new RedisAuthenticatedClientSessionAdapter(session, key.realmId(), key.id());
  }

  @Override
  public RedisAuthenticatedClientSessionAdapter newInstance(
      AuthenticatedClientSessionKey key, Map<String, String> data) {
    return new RedisAuthenticatedClientSessionAdapter(session, key.realmId(), key.id(), data);
  }
}
