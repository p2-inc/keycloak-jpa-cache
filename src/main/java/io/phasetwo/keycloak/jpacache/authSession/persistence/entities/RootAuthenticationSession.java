package io.phasetwo.keycloak.jpacache.authSession.persistence.entities;

import io.phasetwo.keycloak.common.ExpirableEntity;
import jakarta.persistence.*;
import java.util.HashMap;
import java.util.Map;
import lombok.*;

@EqualsAndHashCode(of = "id")
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@NamedQueries({
  @NamedQuery(
      name = "findRootAuthSession",
      query =
          "SELECT s FROM RootAuthenticationSession s WHERE s.realmId = :realmId AND s.id = :id"),
  @NamedQuery(
      name = "deleteRootAuthSession",
      query = "DELETE FROM RootAuthenticationSession s WHERE s.realmId = :realmId AND s.id = :id")
})
@Table(name = "CACHE_ROOT_AUTH_SESSION")
@Entity
public class RootAuthenticationSession implements ExpirableEntity {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @Column(name = "REALM_ID")
  private String realmId;

  @Column(name = "TIMESTAMP")
  private Long timestamp;

  @Column(name = "EXPIRATION")
  private Long expiration;

  @Builder.Default
  @OneToMany(mappedBy = "parentSession")
  @MapKeyColumn(name = "TAB_ID")
  private Map<String, AuthenticationSession> authenticationSessions = new HashMap<>();
}
