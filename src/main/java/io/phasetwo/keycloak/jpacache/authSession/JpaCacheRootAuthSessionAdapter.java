package io.phasetwo.keycloak.jpacache.authSession;

import static org.keycloak.models.utils.SessionExpiration.getAuthSessionLifespan;

import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.AuthenticationSession;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.RootAuthenticationSession;
import io.phasetwo.keycloak.mapstorage.common.TimeAdapter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

@JBossLog
@EqualsAndHashCode(of = "rootAuthenticationSession")
@RequiredArgsConstructor
public class JpaCacheRootAuthSessionAdapter implements RootAuthenticationSessionModel {
  private final KeycloakSession session;
  private final RealmModel realm;
  private final RootAuthenticationSession rootAuthenticationSession;
  private final int authSessionsLimit;
  private final EntityManager entityManager;

  private static final Comparator<AuthenticationSession> TIMESTAMP_COMPARATOR =
      Comparator.comparingLong(AuthenticationSession::getTimestamp);

  private Function<AuthenticationSession, JpaCacheAuthSessionAdapter> entityToAdapterFunc(
      RealmModel realm) {
    return (origEntity) -> {
      if (origEntity == null) {
        return null;
      }

      JpaCacheAuthSessionAdapter adapter =
          new JpaCacheAuthSessionAdapter(session, realm, this, origEntity, entityManager);
      return adapter;
    };
  }

  public RootAuthenticationSession getEntity() {
    return rootAuthenticationSession;
  }

  @Override
  public String getId() {
    return rootAuthenticationSession.getId();
  }

  @Override
  public RealmModel getRealm() {
    return realm;
  }

  @Override
  public int getTimestamp() {
    return TimeAdapter.fromLongWithTimeInSecondsToIntegerWithTimeInSeconds(
        TimeAdapter.fromMilliSecondsToSeconds(rootAuthenticationSession.getTimestamp()));
  }

  @Override
  public void setTimestamp(int timestamp) {
    rootAuthenticationSession.setTimestamp(TimeAdapter.fromSecondsToMilliseconds(timestamp));
    rootAuthenticationSession.setExpiration(
        TimeAdapter.fromSecondsToMilliseconds(
            SessionExpiration.getAuthSessionExpiration(realm, timestamp)));
  }

  @Override
  public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
    return rootAuthenticationSession.getAuthenticationSessions().values().stream()
        .map(entityToAdapterFunc(realm))
        .collect(Collectors.toMap(JpaCacheAuthSessionAdapter::getTabId, Function.identity()));
  }

  @Override
  public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
    if (client == null || tabId == null) {
      return null;
    }

    return rootAuthenticationSession.getAuthenticationSessions().values().stream()
        .filter(s -> Objects.equals(s.getClientId(), client.getId()))
        .filter(s -> Objects.equals(s.getTabId(), tabId))
        .map(entityToAdapterFunc(realm))
        .findFirst()
        .orElse(null);
  }

  @Override
  public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
    Objects.requireNonNull(client, "The provided client can't be null!");

    TypedQuery<AuthenticationSession> query =
        entityManager.createNamedQuery(
            "findAuthSessionsByRootsessionId", AuthenticationSession.class);
    query.setParameter("parentSessionId", rootAuthenticationSession.getId());
    List<AuthenticationSession> authenticationSessions = query.getResultList();
    if (authenticationSessions != null && authenticationSessions.size() >= authSessionsLimit) {
      Optional<AuthenticationSession> oldest =
          authenticationSessions.stream().min(TIMESTAMP_COMPARATOR);
      String tabId = oldest.map(AuthenticationSession::getTabId).orElse(null);

      if (tabId != null && !oldest.isEmpty()) {
        log.debugf(
            "Reached limit (%s) of active authentication sessions per a root authentication session. Removing oldest authentication session with TabId %s.",
            authSessionsLimit, tabId);
        // remove the oldest authentication session
        entityManager.remove(oldest.get());
        entityManager.flush();
      }
    }

    long timestamp = Time.currentTimeMillis();
    int authSessionLifespanSeconds = getAuthSessionLifespan(realm);

    AuthenticationSession authSession =
        AuthenticationSession.builder()
            .parentSessionId(rootAuthenticationSession.getId())
            .clientId(client.getId())
            .timestamp(timestamp)
            .tabId(generateTabId())
            .build();

    rootAuthenticationSession.setTimestamp(timestamp);
    rootAuthenticationSession.setExpiration(
        timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds));

    JpaCacheAuthSessionAdapter jpaCacheAuthSessionAdapter =
        entityToAdapterFunc(realm).apply(authSession);
    session.getContext().setAuthenticationSession(jpaCacheAuthSessionAdapter);

    return jpaCacheAuthSessionAdapter;
  }

  @Override
  public void removeAuthenticationSessionByTabId(String tabId) {
    if (rootAuthenticationSession.getAuthenticationSessions().remove(tabId) != null) {
      if (rootAuthenticationSession.getAuthenticationSessions().isEmpty()) {
        entityManager.remove(rootAuthenticationSession);
        entityManager.flush();
      } else {
        rootAuthenticationSession.setTimestamp(Time.currentTimeMillis());
      }
    }
  }

  @Override
  public void restartSession(RealmModel realm) {
    rootAuthenticationSession.getAuthenticationSessions().clear();
    rootAuthenticationSession.setTimestamp(Time.currentTimeMillis());
  }

  private String generateTabId() {
    return Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
  }
}
