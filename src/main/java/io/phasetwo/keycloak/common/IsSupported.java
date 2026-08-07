package io.phasetwo.keycloak.common;

import io.phasetwo.keycloak.redis.RedisStoreConfig;
import org.keycloak.Config;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

public interface IsSupported extends EnvironmentDependentProviderFactory {

  default boolean isSupported() {
    return RedisStoreConfig.isEnabled();
  }

  @Override
  default boolean isSupported(Config.Scope config) {
    return RedisStoreConfig.isEnabled();
  }
}
