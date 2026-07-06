package io.phasetwo.keycloak.redis.userSession;

import io.phasetwo.keycloak.redis.AdapterSupplier;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import java.util.Map;
import org.keycloak.models.KeycloakSession;
import redis.clients.jedis.UnifiedJedis;

public class UserSessionAdapterSupplier
    implements AdapterSupplier<UserSessionKey, RedisUserSessionAdapter> {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;
  private final RedisChangelogTransaction<
          AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
      clientSessionTrx;

  public UserSessionAdapterSupplier(
      KeycloakSession session,
      UnifiedJedis jedis,
      RedisChangelogTransaction<
              AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
          clientSessionTrx) {
    this.session = session;
    this.jedis = jedis;
    this.clientSessionTrx = clientSessionTrx;
  }

  @Override
  public RedisUserSessionAdapter newInstance(UserSessionKey key) {
    return new RedisUserSessionAdapter(session, jedis, clientSessionTrx, key.realmId(), key.id());
  }

  @Override
  public RedisUserSessionAdapter newInstance(UserSessionKey key, Map<String, String> data) {
    return new RedisUserSessionAdapter(
        session, jedis, clientSessionTrx, key.realmId(), key.id(), data);
  }
}
