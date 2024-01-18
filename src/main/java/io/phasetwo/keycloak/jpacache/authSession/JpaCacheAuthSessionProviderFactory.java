package io.phasetwo.keycloak.jpacache.authSession;

import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.sessions.AuthenticationSessionProviderFactory;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import jakarta.persistence.EntityManager;
import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static org.keycloak.userprofile.DeclarativeUserProfileProvider.PROVIDER_PRIORITY;

@AutoService(AuthenticationSessionProviderFactory.class)
public class JpaCacheAuthSessionProviderFactory implements AuthenticationSessionProviderFactory<JpaCacheAuthSessionProvider>, EnvironmentDependentProviderFactory {
  public static final String AUTH_SESSIONS_LIMIT = "authSessionsLimit";

  public static final int DEFAULT_AUTH_SESSIONS_LIMIT = 300;

  private int authSessionsLimit = 0;

  @Override
  public JpaCacheAuthSessionProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheAuthSessionProvider(session, em, authSessionsLimit);
  }

  @Override
  public void init(Config.Scope config) {
    int configInt = config.getInt(AUTH_SESSIONS_LIMIT, DEFAULT_AUTH_SESSIONS_LIMIT);
    // use default if provided value is not a positive number
    authSessionsLimit = (configInt <= 0) ? DEFAULT_AUTH_SESSIONS_LIMIT : configInt;
  }

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
  public boolean isSupported() {
    return isJpaCacheEnabled();
  }
}
