package io.phasetwo.keycloak.jpacache;

import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.storage.datastore.LegacyDatastoreProvider;

@JBossLog
public class JpaCacheDatastoreProvider extends LegacyDatastoreProvider {
  private final KeycloakSession session;

  public JpaCacheDatastoreProvider(
      JpaCacheDatastoreProviderFactory factory, KeycloakSession session) {
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
