package io.phasetwo.keycloak.jpacache.userSession;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.jpacache.connection.JpaCacheProvider;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import jakarta.persistence.EntityManager;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;
import static org.keycloak.userprofile.DeclarativeUserProfileProvider.PROVIDER_PRIORITY;

@JBossLog
@AutoService(UserSessionProviderFactory.class)
public class JpaCacheUserSessionProviderFactory implements UserSessionProviderFactory<JpaCacheUserSessionProvider>, EnvironmentDependentProviderFactory {

  @Override
  public JpaCacheUserSessionProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheUserSessionProvider(session, em);
  }

  @Override
  public void init(Config.Scope config) {

  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {

  }

  @Override
  public void close() {

  }

  @Override
  public String getId() {
    return "infinispan"; // use same name as infinispan provider to override it
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }

  @Override
  public boolean isSupported() {
    return isJpaCacheEnabled();
  }

  @Override
  public void loadPersistentSessions(KeycloakSessionFactory sessionFactory, int maxErrors, int sessionsPerSegment) {
    throw new UnsupportedOperationException("loadPersistentSessions not supported in this implementation");
  }
}
