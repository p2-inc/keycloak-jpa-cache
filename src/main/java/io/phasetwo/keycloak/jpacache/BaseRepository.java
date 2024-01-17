package io.phasetwo.keycloak.jpacache;

import jakarta.persistence.EntityManager;
import org.keycloak.models.KeycloakSession;
import lombok.Getter;
import lombok.RequiredArgsContstructor;

@Getter
@RequiredArgsContstructor
public abstract class BaseRepository {
  private final KeycloakSession session;
  private final EntityManager entityManager;
}
