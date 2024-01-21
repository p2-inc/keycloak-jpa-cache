package io.phasetwo.keycloak.jpacache.loginFailure;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;

import io.phasetwo.keycloak.jpacache.loginFailure.persistence.entities.LoginFailure;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

@JBossLog
@RequiredArgsConstructor
public class JpaCacheLoginFailureProvider implements UserLoginFailureProvider {
  private final KeycloakSession session;
  private final EntityManager entityManager;

  private Function<LoginFailure, UserLoginFailureModel> entityToAdapterFunc(RealmModel realm) {
    return origEntity -> new JpaCacheLoginFailureAdapter(realm, origEntity);
  }

  @Override
  public UserLoginFailureModel getUserLoginFailure(RealmModel realm, String userId) {
    return findByUserId(realm, userId).map(entityToAdapterFunc(realm)).orElse(null);
  }

  private Optional<LoginFailure> findByUserId(RealmModel realm, String userId) {
    TypedQuery<LoginFailure> query =
        entityManager.createNamedQuery("findByUserId", LoginFailure.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("userId", userId);
    return query.getResultList().stream().findFirst();
  }

  @Override
  public UserLoginFailureModel addUserLoginFailure(RealmModel realm, String userId) {
    log.tracef("addUserLoginFailure(%s, %s)%s", realm, userId, getShortStackTrace());

    LoginFailure userLoginFailureEntity = findByUserId(realm, userId).orElse(null);

    if (userLoginFailureEntity == null) {
      userLoginFailureEntity =
          LoginFailure.builder()
              .userId(userId)
              .realmId(realm.getId())
              .id(KeycloakModelUtils.generateId())
              .build();
      entityManager.persist(userLoginFailureEntity);
      entityManager.flush();
    }
    return entityToAdapterFunc(realm).apply(userLoginFailureEntity);
  }

  @Override
  public void removeUserLoginFailure(RealmModel realm, String userId) {
    log.tracef("removeUserLoginFailure(%s, %s)%s", realm, userId, getShortStackTrace());

    findByUserId(realm, userId)
        .ifPresent(
            userLoginFailureEntity -> {
              entityManager.remove(userLoginFailureEntity);
              entityManager.flush();
            });
  }

  @Override
  public void removeAllUserLoginFailures(RealmModel realm) {
    Query query = entityManager.createNamedQuery("deleteByRealmId");
    query.setParameter("realmId", realm.getId());
    query.executeUpdate();
  }

  @Override
  public void close() {
    // Nothing to do
  }
}
