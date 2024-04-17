package io.phasetwo.keycloak.jpacache;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.*;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

@AutoService(DatastoreProviderFactory.class)
@JBossLog
public class JpaCacheDatastoreProviderFactory extends DefaultDatastoreProviderFactory
    implements IsSupported {
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

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }
}
