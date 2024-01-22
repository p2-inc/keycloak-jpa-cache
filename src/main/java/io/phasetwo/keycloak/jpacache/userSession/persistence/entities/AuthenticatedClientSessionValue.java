package io.phasetwo.keycloak.jpacache.userSession.persistence.entities;

import io.phasetwo.keycloak.mapstorage.common.ExpirableEntity;
import jakarta.persistence.*;
import java.util.HashMap;
import java.util.Map;
import lombok.*;

@EqualsAndHashCode(of = "id")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "CACHE_CLIENT_SESSION")
@NamedQueries({
  @NamedQuery(
      name = "findClientSessionsByClientId",
      query =
          "SELECT s FROM AuthenticatedClientSessionValue s WHERE s.parentSession.realmId = :realmId AND s.clientId = :clientId")
})
@Entity
public class AuthenticatedClientSessionValue implements ExpirableEntity {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "USER_SESSION_ID")
  private UserSession parentSession;

  @Column(name = "CLIENT_ID")
  private String clientId;

  @Column(name = "TIMESTAMP")
  private Long timestamp;

  @Column(name = "EXPIRATION")
  private Long expiration;

  @Column(name = "AUTH_METHOD")
  private String authMethod;

  @Column(name = "REDIRECT_URI")
  private String redirectUri;

  @Column(name = "ACTION")
  private String action;

  @Column(name = "CURRENT_REFRESH_TOKEN")
  private String currentRefreshToken;

  @Column(name = "CURRENT_REFRESH_TOKEN_USE_COUNT")
  private Integer currentRefreshTokenUseCount;

  @Column(name = "OFFLINE")
  private Boolean offline;

  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name = "NAME")
  @CollectionTable(
      name = "CACHE_CLIENT_SESSION_NOTE",
      joinColumns = @JoinColumn(name = "CLIENT_SESSION_ID"))
  @Column(name = "NOTE")
  private Map<String, String> notes = new HashMap<>();

  public Map<String, String> getNotes() {
    if (notes == null) {
      notes = new HashMap<>();
    }
    return notes;
  }

  public boolean isOffline() {
    return offline != null ? offline.booleanValue() : false;
  }
}
