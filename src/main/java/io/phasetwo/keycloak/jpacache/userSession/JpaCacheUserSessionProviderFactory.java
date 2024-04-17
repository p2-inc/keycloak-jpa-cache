package io.phasetwo.keycloak.jpacache.userSession;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.*;

@JBossLog
@SuppressWarnings("rawtypes")
@AutoService(UserSessionProviderFactory.class)
public class JpaCacheUserSessionProviderFactory
    implements UserSessionProviderFactory<JpaCacheUserSessionProvider>, IsSupported {

  @Override
  public JpaCacheUserSessionProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheUserSessionProvider(session, em);
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

  @Override
  public void loadPersistentSessions(
      KeycloakSessionFactory sessionFactory, int maxErrors, int sessionsPerSegment) {
    throw new UnsupportedOperationException(
        "loadPersistentSessions not supported in this implementation");
  }
}
