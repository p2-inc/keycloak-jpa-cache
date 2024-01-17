package io.phasetwo.keycloak.jpacache;

import static io.phasetwo.keycloak.jpacache.cache.KeycloakSessionCache.*;

import io.phasetwo.keycloak.jpacache.authSession.persistence.AuthSessionRepository;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.AuthenticationSession;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.RootAuthenticationSession;
import io.phasetwo.keycloak.jpacache.cache.InvalidateCache;
import io.phasetwo.keycloak.jpacache.cache.L1Cached;
import io.phasetwo.keycloak.jpacache.loginFailure.persistence.LoginFailureRepository;
import io.phasetwo.keycloak.jpacache.loginFailure.persistence.entities.LoginFailure;
import io.phasetwo.keycloak.jpacache.singleUseObject.persistence.SingleUseObjectRepository;
import io.phasetwo.keycloak.jpacache.singleUseObject.persistence.entities.SingleUseObject;
import io.phasetwo.keycloak.jpacache.userSession.persistence.UserSessionRepository;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.AuthenticatedClientSessionValue;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.UserSession;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.UserSessionToAttributeMapping;
import lombok.Setter;
import org.keycloak.common.util.MultivaluedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import jakarta.persistence.EntityManager;
import org.keycloak.models.KeycloakSession;

public class JpaCompositeRepository implements CompositeRepository {
  private final EntityManager entityManager;

  private UserSessionRepository userSessionRepository;
  private AuthSessionRepository authSessionRepository;
  private LoginFailureRepository loginFailureRepository;
  private SingleUseObjectRepository singleUseObjectRepository;

  public JpaCompositeRepository(KeycloakSession session, EntityManager entityManager) {
    this.entityManager = entityManager;
    this.userSessionRepository = new JpaCacheUserSessionRepository(session, entityManager);
    this.authSessionRepository = new JpaCacheAuthSessionRepository(session, entityManager);
    this.loginFailureRepository = new JpaCacheLoginFailureRepository(session, entityManager);
    this.singleUseObjectRepository = new JpaCacheSingleUseObjectRepository(session, entityManager);
  }
    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void insert(UserSession session) {
        this.userSessionRepository.insert(session);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void insert(UserSession session, String correspondingSessionId) {
        this.userSessionRepository.insert(session, correspondingSessionId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void update(UserSession session) {
        this.userSessionRepository.update(session);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void update(UserSession session, String correspondingSessionId) {
        this.userSessionRepository.update(session, correspondingSessionId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void addClientSession(UserSession session, AuthenticatedClientSessionValue clientSession) {
        this.userSessionRepository.addClientSession(session, clientSession);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public UserSession findUserSessionById(String id) {
        return this.userSessionRepository.findUserSessionById(id);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findAll() {
        return this.userSessionRepository.findAll();
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findUserSessionsByBrokerSession(String brokerSessionId) {
        return this.userSessionRepository.findUserSessionsByBrokerSession(brokerSessionId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findUserSessionsByUserId(String userId) {
        return this.userSessionRepository.findUserSessionsByUserId(userId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findUserSessionsByClientId(String clientId) {
        return this.userSessionRepository.findUserSessionsByClientId(clientId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findUserSessionsByBrokerUserId(String brokerUserId) {
        return this.userSessionRepository.findUserSessionsByBrokerUserId(brokerUserId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void deleteUserSession(UserSession session) {
        this.userSessionRepository.deleteUserSession(session);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void deleteUserSession(String id) {
        this.userSessionRepository.deleteUserSession(id);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    @InvalidateCache
    public void deleteCorrespondingUserSession(UserSession session) {
        this.userSessionRepository.deleteCorrespondingUserSession(session);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public Set<String> findUserSessionIdsByAttribute(String name, String value, int firstResult, int maxResult) {
        return this.userSessionRepository.findUserSessionIdsByAttribute(name, value, firstResult, maxResult);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public List<UserSession> findUserSessionsByAttribute(String name, String value) {
        return this.userSessionRepository.findUserSessionsByAttribute(name, value);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public UserSession findUserSessionByAttribute(String name, String value) {
        return this.userSessionRepository.findUserSessionByAttribute(name, value);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public MultivaluedHashMap<String, String> findAllUserSessionAttributes(String userSessionId) {
        return this.userSessionRepository.findAllUserSessionAttributes(userSessionId);
    }

    @L1Cached(cacheName = USER_SESSION_CACHE)
    public UserSessionToAttributeMapping findUserSessionAttribute(String userSessionId, String attributeName) {
        return this.userSessionRepository.findUserSessionAttribute(userSessionId, attributeName);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void insertOrUpdate(RootAuthenticationSession session) {
        this.authSessionRepository.insertOrUpdate(session);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void insertOrUpdate(AuthenticationSession session, RootAuthenticationSession parent) {
        this.authSessionRepository.insertOrUpdate(session, parent);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void deleteRootAuthSession(String sessionId) {
        this.authSessionRepository.deleteRootAuthSession(sessionId);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void deleteRootAuthSession(RootAuthenticationSession session) {
        this.authSessionRepository.deleteRootAuthSession(session);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void deleteAuthSession(AuthenticationSession session) {
        this.authSessionRepository.deleteAuthSession(session);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    @InvalidateCache
    public void deleteAuthSessions(String parentSessionId) {
        this.authSessionRepository.deleteAuthSessions(parentSessionId);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    public List<AuthenticationSession> findAuthSessionsByParentSessionId(String parentSessionId) {
        return this.authSessionRepository.findAuthSessionsByParentSessionId(parentSessionId);
    }

    @L1Cached(cacheName = AUTH_SESSION_CACHE)
    public RootAuthenticationSession findRootAuthSessionById(String id) {
        return this.authSessionRepository.findRootAuthSessionById(id);
    }

    @L1Cached(cacheName = LOGIN_FAILURE_CACHE)
    @InvalidateCache
    public void insertOrUpdate(LoginFailure loginFailure) {
        this.loginFailureRepository.insertOrUpdate(loginFailure);
    }

    @L1Cached(cacheName = LOGIN_FAILURE_CACHE)
    public List<LoginFailure> findLoginFailuresByUserId(String userId) {
        return this.loginFailureRepository.findLoginFailuresByUserId(userId);
    }

    @L1Cached(cacheName = LOGIN_FAILURE_CACHE)
    @InvalidateCache
    public void deleteLoginFailure(LoginFailure loginFailure) {
        this.loginFailureRepository.deleteLoginFailure(loginFailure);
    }

    @L1Cached(cacheName = LOGIN_FAILURE_CACHE)
    @InvalidateCache
    public void deleteLoginFailureByUserId(String userId) {
        this.loginFailureRepository.deleteLoginFailureByUserId(userId);
    }

    @L1Cached(cacheName = LOGIN_FAILURE_CACHE)
    public List<LoginFailure> findAllLoginFailures() {
        return this.loginFailureRepository.findAllLoginFailures();
    }

    @L1Cached(cacheName = SUO_CACHE)
    public SingleUseObject findSingleUseObjectByKey(String key) {
        return this.singleUseObjectRepository.findSingleUseObjectByKey(key);
    }

    @L1Cached(cacheName = SUO_CACHE)
    @InvalidateCache
    public void insertOrUpdate(SingleUseObject singleUseObject, int ttl) {
        this.singleUseObjectRepository.insertOrUpdate(singleUseObject, ttl);
    }

    @L1Cached(cacheName = SUO_CACHE)
    @InvalidateCache
    public void insertOrUpdate(SingleUseObject singleUseObject) {
        this.singleUseObjectRepository.insertOrUpdate(singleUseObject);
    }

    @L1Cached(cacheName = SUO_CACHE)
    @InvalidateCache
    public boolean deleteSingleUseObjectByKey(String key) {
        return this.singleUseObjectRepository.deleteSingleUseObjectByKey(key);
    }
}
