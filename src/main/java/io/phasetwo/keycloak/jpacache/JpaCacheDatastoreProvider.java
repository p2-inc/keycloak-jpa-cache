package io.phasetwo.keycloak.jpacache;

import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.*;
import org.keycloak.provider.Provider;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.storage.datastore.LegacyDatastoreProvider;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

@JBossLog
public class JpaCacheDatastoreProvider extends LegacyDatastoreProvider {
  private final KeycloakSession session;
  
  public JpaCacheDatastoreProvider(JpaCacheDatastoreProviderFactory factory, KeycloakSession session) {
    super(factory, session);
    this.session = session;
  }
  
  @Override
  public SingleUseObjectProvider singleUseObjects() {
    return session.getProvider(SingleUseObjectProvider.class);
  }

  @Override
  public UserLoginFailureProvider loginFailures() {
    return session.getProvider(UserLoginFailureProvider.class);
  }

  @Override
  public AuthenticationSessionProvider authSessions() {
    return session.getProvider(AuthenticationSessionProvider.class);
  }

  @Override
  public UserSessionProvider userSessions() {
    return session.getProvider(UserSessionProvider.class);
  }

}
