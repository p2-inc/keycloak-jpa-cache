package io.phasetwo.keycloak.jpacache;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

@AutoService(Spi.class)
public class JpaCacheSpi implements Spi {

  public static final String NAME = "jpa-cache";

  @Override
  public boolean isInternal() {
    return true;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Class<? extends Provider> getProviderClass() {
    return JpaCacheProvider.class;
  }

  @Override
  public Class<? extends ProviderFactory> getProviderFactoryClass() {
    return JpaCacheProviderFactory.class;
  }
}
