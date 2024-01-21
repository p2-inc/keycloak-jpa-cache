package io.phasetwo.keycloak.jpacache;

import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;
import static org.keycloak.userprofile.DeclarativeUserProfileProvider.PROVIDER_PRIORITY;

import com.google.auto.service.AutoService;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.*;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.LegacyDatastoreProviderFactory;

@JBossLog
@AutoService(DatastoreProviderFactory.class)
public class JpaCacheDatastoreProviderFactory extends LegacyDatastoreProviderFactory {
  private static final String PROVIDER_ID =
      "legacy"; // Override legacy provider to disable timers / event listeners and stuff...

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public DatastoreProvider create(KeycloakSession session) {
    log.infof("Creating JpaCacheDatastoreProvider...");
    return createProviderCached(
        session, DatastoreProvider.class, () -> new JpaCacheDatastoreProvider(this, session));
  }

  /*
  @Override
  public void init(Config.Scope scope) {
    super.init(scope);
    log.info("Using JPA cache datastore...");
  }

  @Override
  public void postInit(KeycloakSessionFactory keycloakSessionFactory) {
    super.postInit(keycloakSessionFactory);
  }

  @Override
  public void close() {
    super.close();
  }
  */

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }

  @Override
  public boolean isSupported() {
    return isJpaCacheEnabled();
  }
}
