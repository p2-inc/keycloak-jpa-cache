package io.phasetwo.keycloak.jpacache;

import org.keycloak.provider.ProviderFactory;

public interface JpaCacheProviderFactory<T extends JpaCacheProvider> extends ProviderFactory<T> {}
