package io.phasetwo.keycloak.jpacache.authSession;

import io.phasetwo.keycloak.jpacache.authSession.persistence.AuthSessionRepository;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.AuthenticationSession;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.RootAuthenticationSession;
import io.phasetwo.keycloak.jpacache.transaction.CassandraModelTransaction;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import io.phasetwo.keycloak.mapstorage.common.TimeAdapter;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.keycloak.models.utils.SessionExpiration.getAuthSessionLifespan;

@JBossLog
@EqualsAndHashCode(of = "rootAuthenticationSession")
@RequiredArgsConstructor
public class JpaCacheRootAuthSessionAdapter implements RootAuthenticationSessionModel {
  private final KeycloakSession session;
  private final RealmModel realm;
  private final RootAuthenticationSession rootAuthenticationSession;
  private final int authSessionsLimit;

  private Map<String, JpaCacheAuthSessionAdapter> sessionModels = new HashMap<>();
  private boolean updated = false;
  private boolean deleted = false;

  private static final Comparator<AuthenticationSession> TIMESTAMP_COMPARATOR = Comparator.comparingLong(AuthenticationSession::getTimestamp);

  private Function<AuthenticationSession, JpaCacheAuthSessionAdapter> entityToAdapterFunc(RealmModel realm) {
    return (origEntity) -> {
      if (origEntity == null) {
        return null;
      }

      if (sessionModels.containsKey(origEntity.getTabId())) {
        return sessionModels.get(origEntity.getTabId());
      }

      JpaCacheAuthSessionAdapter adapter = new JpaCacheAuthSessionAdapter(session, realm, this, origEntity, authSessionRepository);
      session.getTransactionManager()
          .enlistAfterCompletion((CassandraModelTransaction) adapter::flush);
      sessionModels.put(adapter.getTabId(), adapter);
      
      return adapter;
    };
  }

  public void markDeleted() {
    deleted = true;
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
    return TimeAdapter.fromLongWithTimeInSecondsToIntegerWithTimeInSeconds(TimeAdapter.fromMilliSecondsToSeconds(rootAuthenticationSession.getTimestamp()));
  }

  @Override
  public void setTimestamp(int timestamp) {
    rootAuthenticationSession.setTimestamp(TimeAdapter.fromSecondsToMilliseconds(timestamp));
    rootAuthenticationSession.setExpiration(TimeAdapter.fromSecondsToMilliseconds(SessionExpiration.getAuthSessionExpiration(realm, timestamp)));
    
    updated = true;
  }

  @Override
  public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
    return authSessionRepository.findAuthSessionsByParentSessionId(rootAuthenticationSession.getId())
        .stream()
        .map(entityToAdapterFunc(realm))
        .collect(Collectors.toMap(JpaCacheAuthSessionAdapter::getTabId, Function.identity()));
  }
  
  @Override
  public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
    if (client == null || tabId == null) {
      return null;
    }

    return authSessionRepository.findAuthSessionsByParentSessionId(rootAuthenticationSession.getId())
        .stream()
        .filter(s -> Objects.equals(s.getClientId(), client.getId()))
        .filter(s -> Objects.equals(s.getTabId(), tabId))
        .map(entityToAdapterFunc(realm))
        .findFirst()
        .orElse(null);
  }

  @Override
  public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
    Objects.requireNonNull(client, "The provided client can't be null!");
    
    List<AuthenticationSession> authenticationSessions = authSessionRepository.findAuthSessionsByParentSessionId(rootAuthenticationSession.getId());
    if (authenticationSessions != null && authenticationSessions.size() >= authSessionsLimit) {
      Optional<AuthenticationSession> oldest = authenticationSessions.stream()
                                               .min(TIMESTAMP_COMPARATOR);
      String tabId = oldest.map(AuthenticationSession::getTabId)
                     .orElse(null);

      if (tabId != null && !oldest.isEmpty()) {
        log.debugf("Reached limit (%s) of active authentication sessions per a root authentication session. Removing oldest authentication session with TabId %s.", authSessionsLimit, tabId);

        // remove the oldest authentication session
        authSessionRepository.deleteAuthSession(oldest.get());
      }
    }

    long timestamp = Time.currentTimeMillis();
    int authSessionLifespanSeconds = getAuthSessionLifespan(realm);

    AuthenticationSession authSession = AuthenticationSession.builder()
                                        .parentSessionId(rootAuthenticationSession.getId())
                                        .clientId(client.getId())
                                        .timestamp(timestamp)
                                        .tabId(generateTabId())
                                        .build();

    rootAuthenticationSession.setTimestamp(timestamp);
    rootAuthenticationSession.setExpiration(timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds));
    
    authSessionRepository.insertOrUpdate(authSession, rootAuthenticationSession);
    updated = true;

    JpaCacheAuthSessionAdapter jpaCacheAuthSessionAdapter = entityToAdapterFunc(realm).apply(authSession);
    session.getContext().setAuthenticationSession(jpaCacheAuthSessionAdapter);

    return jpaCacheAuthSessionAdapter;
  }

  @Override
  public void removeAuthenticationSessionByTabId(String tabId) {
    List<AuthenticationSession> allAuthSessions = authSessionRepository.findAuthSessionsByParentSessionId(rootAuthenticationSession.getId());
    AuthenticationSession toDelete = allAuthSessions.stream()
                                     .filter(s -> Objects.equals(s.getTabId(), tabId))
                                     .findFirst()
                                     .orElse(null);
    
    if (toDelete != null) {
      authSessionRepository.deleteAuthSession(toDelete);
    }
    
    JpaCacheAuthSessionAdapter model = sessionModels.get(tabId);
    if (model != null) {
      model.markDeleted();
    }
    sessionModels.remove(tabId);
    
    if (toDelete != null) {
      if (allAuthSessions.size() == 1) {
        session.authenticationSessions()
            .removeRootAuthenticationSession(realm, this);
        deleted = true;
      } else {
        long timestamp = Time.currentTimeMillis();
        rootAuthenticationSession.setTimestamp(timestamp);
        int authSessionLifespanSeconds = getAuthSessionLifespan(realm);
        rootAuthenticationSession.setExpiration(timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds));
        updated = true;
      }
    }
  }

  @Override
  public void restartSession(RealmModel realm) {
    authSessionRepository.deleteAuthSessions(rootAuthenticationSession.getId());
    sessionModels.clear();
    long timestamp = Time.currentTimeMillis();
    rootAuthenticationSession.setTimestamp(timestamp);
    int authSessionLifespanSeconds = getAuthSessionLifespan(realm);
    rootAuthenticationSession.setExpiration(timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds));
    updated = true;
  }

  private String generateTabId() {
    return Base64Url.encode(SecretGenerator.getInstance()
                            .randomBytes(8));
  }

  public void flush() {
    if (updated && !deleted) {
      authSessionRepository.insertOrUpdate(rootAuthenticationSession);
      updated = false;
    }
  }
}
