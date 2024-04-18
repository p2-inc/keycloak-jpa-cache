package io.phasetwo.keycloak.jpacache.cluster;

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
public class JpaClusterProvider implements ClusterProvider {

  private final JpaClusterProviderFactory factory;
  private final KeycloakSession session;

  public JpaClusterProvider(JpaClusterProviderFactory factory, KeycloakSession session) {
    this.factory = factory;
    this.session = session;
  }
  
  @Override
  public int getClusterStartupTime() {
    log.trace("getClusterStartupTime");
    return 0;
  }

  @Override
  public <T> ExecutionResult<T> executeIfNotExecuted(
      String taskKey, int taskTimeoutInSeconds, Callable<T> task) {
    log.tracef("executeIfNotExecuted %s %d", taskKey, taskTimeoutInSeconds);
    return null;
  }

  @Override
  public Future<Boolean> executeIfNotExecutedAsync(
      String taskKey, int taskTimeoutInSeconds, Callable task) {
    log.tracef("executeIfNotExecutedAsync %s %d", taskKey, taskTimeoutInSeconds);
    return null;
  }

  @Override
  public void registerListener(String taskKey, ClusterListener task) {
    log.tracef("registerListener %s", taskKey);
  }
  
  @Override
  public void notify(String taskKey, ClusterEvent event, boolean ignoreSender, DCNotify dcNotify) {
    log.tracef("notify %s %b %s", taskKey, ignoreSender, dcNotify);

    // this is where i put entities in the database.

  }
  
  @Override
  public void close() {
    log.trace("close");
  }
}
