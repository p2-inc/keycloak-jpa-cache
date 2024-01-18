package io.phasetwo.keycloak.jpacache.singleUseObject;

import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import jakarta.persistence.EntityManager;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;
import static org.keycloak.userprofile.DeclarativeUserProfileProvider.PROVIDER_PRIORITY;

@AutoService(SingleUseObjectProviderFactory.class)
public class JpaCacheSingleUseObjectProviderFactory implements SingleUseObjectProviderFactory<JpaCacheSingleUseObjectProvider>, EnvironmentDependentProviderFactory {

  @Override
  public JpaCacheSingleUseObjectProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheSingleUseObjectProvider(session, em);
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
  public boolean isSupported() {
    return isJpaCacheEnabled();
  }
}
