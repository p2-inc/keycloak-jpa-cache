package io.phasetwo.keycloak.jpacache.cluster;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.cluster.*;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@JBossLog
@AutoService(ClusterProviderFactory.class)
public class JpaClusterProviderFactory implements ClusterProviderFactory, IsSupported {

  @Override
  public ClusterProvider create(KeycloakSession session) {
    log.trace("create");
    return createProviderCached(session, ClusterProvider.class, () -> new JpaClusterProvider(this, session));
  }

  @Override
  public void init(Config.Scope config) {
    log.trace("init");
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    log.trace("postInit");
  }

  @Override
  public void close() {
    log.trace("close");
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 2;
  }

  @Override
  public String getId() {
    return "infinispan";
  }
}
