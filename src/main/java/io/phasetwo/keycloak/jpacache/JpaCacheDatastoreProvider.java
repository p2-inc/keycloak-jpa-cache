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
  public RealmProvider realms() {
    RealmProvider rp = super.realms();
    log.infof("RealmProvider from superclass %s", rp);
    try {
      RealmModel r = rp.getRealmByName("master");
      log.infof("master realm %s", r);
    } catch (Exception e) {
      log.warn("realms()", e);
    }
    return rp;
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
