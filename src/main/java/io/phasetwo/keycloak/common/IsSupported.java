package io.phasetwo.keycloak.common;

import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;

import org.keycloak.Config;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

public interface IsSupported extends EnvironmentDependentProviderFactory {

  @Override
  default boolean isSupported() {
    return isJpaCacheEnabled();
  }

  @Override
  default boolean isSupported(Config.Scope config) {
    return isJpaCacheEnabled();
  }
}
