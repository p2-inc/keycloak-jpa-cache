package io.phasetwo.keycloak.jpacache.loginFailure.persistence.entities;

import jakarta.persistence.*;
import lombok.*;

@EqualsAndHashCode(of = "id")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@NamedQueries({
  @NamedQuery(
      name = "findByUserId",
      query =
          "SELECT lf FROM LoginFailure lf WHERE lf.realmId = :realmId AND lf.userId = :userId ORDER BY lastFailure DESC"),
  @NamedQuery(
      name = "deleteByRealmId",
      query = "DELETE FROM LoginFailure lf WHERE lf.realmId = :realmId")
})
@Table(name = "CACHE_LOGIN_FAILURE")
@Entity
public class LoginFailure {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @Column(name = "USER_ID")
  private String userId;

  @Column(name = "REALM_ID")
  private String realmId;

  @Column(name = "FAILED_LOGIN_NOT_BEFORE")
  private Long failedLoginNotBefore;

  @Column(name = "NUM_FAILURES")
  private Integer numFailures;

  @Column(name = "LAST_FAILURE")
  private Long lastFailure;

  @Column(name = "LAST_IP_FAILURE")
  private String lastIpFailure;
}
