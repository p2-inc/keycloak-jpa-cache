package io.phasetwo.keycloak.jpacache;

import org.keycloak.provider.Provider;

public interface JpaCacheProvider extends Provider {
  CompositeRepository getRepository();
}
