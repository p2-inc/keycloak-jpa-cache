package io.phasetwo.keycloak.jpacache;

import io.phasetwo.keycloak.jpacache.authSession.persistence.AuthSessionRepository;
import io.phasetwo.keycloak.jpacache.loginFailure.persistence.LoginFailureRepository;
import io.phasetwo.keycloak.jpacache.singleUseObject.persistence.SingleUseObjectRepository;
import io.phasetwo.keycloak.jpacache.userSession.persistence.UserSessionRepository;

public interface CompositeRepository extends UserSessionRepository, AuthSessionRepository, LoginFailureRepository, SingleUseObjectRepository {
}
