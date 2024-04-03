package io.phasetwo.keycloak.jpacache;

import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;

import com.google.auto.service.AutoService;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

@JBossLog
@AutoService(DatastoreProviderFactory.class)
public class JpaCacheDatastoreProviderFactory extends DefaultDatastoreProviderFactory
    implements EnvironmentDependentProviderFactory {
  private static final String PROVIDER_ID =
      "legacy"; // Override legacy provider to disable timers / event listeners and stuff...

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public DatastoreProvider create(KeycloakSession session) {
    log.tracef("Creating JpaCacheDatastoreProvider...");
    return createProviderCached(
        session, DatastoreProvider.class, () -> new JpaCacheDatastoreProvider(this, session));
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }

  @Override
  public boolean isSupported(Config.Scope config) {
    return isJpaCacheEnabled();
  }
}
