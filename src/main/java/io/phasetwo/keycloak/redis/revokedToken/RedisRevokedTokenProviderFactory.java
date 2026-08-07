package io.phasetwo.keycloak.redis.revokedToken;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import java.util.Set;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RevokedTokenProviderFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.provider.Provider;

@SuppressWarnings("rawtypes")
@AutoService(RevokedTokenProviderFactory.class)
public class RedisRevokedTokenProviderFactory
    implements RevokedTokenProviderFactory<RedisRevokedTokenProvider>, IsSupported {

  @Override
  public RedisRevokedTokenProvider create(KeycloakSession session) {
    return new RedisRevokedTokenProvider(session.getProvider(SingleUseObjectProvider.class));
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public Set<Class<? extends Provider>> dependsOn() {
    return Set.of(SingleUseObjectProvider.class);
  }

  @Override
  public String getId() {
    return "infinispan"; // use same name as infinispan provider to override it
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }
}
