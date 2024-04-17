package io.phasetwo.keycloak.jpacache.loginFailure;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import jakarta.persistence.EntityManager;
import org.keycloak.Config;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserLoginFailureProviderFactory;

@SuppressWarnings("rawtypes")
@AutoService(UserLoginFailureProviderFactory.class)
public class JpaCacheLoginFailureProviderFactory
    implements UserLoginFailureProviderFactory<JpaCacheLoginFailureProvider>, IsSupported {

  @Override
  public JpaCacheLoginFailureProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheLoginFailureProvider(session, em);
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return "infinispan"; // use same name as infinispan provider to override it
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }
}
