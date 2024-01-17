package io.phasetwo.keycloak.jpacache;

import com.google.auto.service.AutoService;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config.Scope;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import io.phasetwo.keycloak.jpacache.cache.L1CacheInterceptor;

@JBossLog
@AutoService(JpaCacheProviderFactory.class)
public class DefaultJpaCacheProviderFactory implements JpaCacheProviderFactory<JpaCacheProvider> {

  public static final String PROVIDER_ID = "jpa-cache";

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public JpaCacheProvider create(KeycloakSession session) {
    final EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    final CompositeRepository repository = new JpaCompositeRepository(session, em);
    return new JpaCacheProvider() {
      @Override
      public CompositeRepository getRepository() {
        L1CacheInterceptor intercepted = new L1CacheInterceptor(session, repository);
        return (CompositeRepository) Proxy.newProxyInstance(Thread.currentThread()
                                                            .getContextClassLoader(), new Class[]{CompositeRepository.class}, intercepted);
      }
      
      @Override
      public void close() {
        
      }
    };
  }

  @Override
  public void init(Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}
}
