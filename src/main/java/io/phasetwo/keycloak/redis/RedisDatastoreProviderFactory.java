package io.phasetwo.keycloak.redis;

import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;

import com.google.auto.service.AutoService;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.common.Profile;
import org.keycloak.models.*;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

/**
 * The Redis datastore, selected with {@code --spi-datastore--provider=redis}. Selecting it is what
 * activates every other provider in this extension; see {@link RedisStoreConfig}.
 */
@AutoService(DatastoreProviderFactory.class)
@JBossLog
public class RedisDatastoreProviderFactory extends DefaultDatastoreProviderFactory {

  @Override
  public String getId() {
    return RedisStoreConfig.DATASTORE_PROVIDER_ID;
  }

  @Override
  public DatastoreProvider create(KeycloakSession session) {
    return createProviderCached(
        session, DatastoreProvider.class, () -> new RedisDatastoreProvider(this, session));
  }

  @Override
  public void init(Config.Scope config) {
    super.init(config);
    log.info("Using redis datastore...");
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    if (!Profile.isFeatureEnabled(Profile.Feature.STATELESS)) {
      throw new IllegalStateException(
          "The redis datastore requires the 'stateless' feature. Start Keycloak with --features=stateless.");
    }
    super.postInit(factory);
  }
}
