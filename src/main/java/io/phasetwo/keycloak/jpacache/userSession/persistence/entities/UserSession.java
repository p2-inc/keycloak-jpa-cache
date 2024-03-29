package io.phasetwo.keycloak.jpacache.userSession.persistence.entities;

import static org.keycloak.models.UserSessionModel.CORRESPONDING_SESSION_ID;

import io.phasetwo.keycloak.mapstorage.common.ExpirableEntity;
import jakarta.persistence.*;
import java.util.HashMap;
import java.util.Map;
import lombok.*;
import org.keycloak.models.UserSessionModel;

@EqualsAndHashCode(of = "id")
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "CACHE_USER_SESSION")
@NamedQueries({
  @NamedQuery(
      name = "findUserSessionsByUserId2",
      query = "SELECT s FROM UserSession s WHERE s.realmId = :realmId AND s.userId = :userId"),
  @NamedQuery(
      name = "findUserSessionsByBrokerSessionId",
      query =
          "SELECT s FROM UserSession s WHERE s.realmId = :realmId AND s.brokerSessionId = :brokerSessionId"),
  @NamedQuery(
      name = "findUserSessionsByBrokerUserId",
      query =
          "SELECT s FROM UserSession s WHERE s.realmId = :realmId AND s.brokerUserId = :brokerUserId"),
  @NamedQuery(
      name = "findAllUserSessions",
      query = "SELECT s FROM UserSession s WHERE s.realmId = :realmId"),
  @NamedQuery(
      name = "removeAllUserSessions",
      query = "DELETE FROM UserSession s WHERE s.realmId = :realmId"),
  @NamedQuery(
      name = "removeExpiredUserSessions",
      query = "DELETE FROM UserSession s WHERE s.expiration IS NOT NULL AND s.expiration < :now"),
  @NamedQuery(
      name = "removeExpiredUserSessionsByRealm",
      query =
          "DELETE FROM UserSession s WHERE s.realmId = :realmId AND s.expiration IS NOT NULL AND s.expiration < :now"),
  @NamedQuery(
      name = "countOfflineUserSessions",
      query =
          "SELECT count(s) FROM UserSession s WHERE s.realmId = :realmId AND s.offline = :offline")
})
@Entity
public class UserSession implements ExpirableEntity {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @Column(name = "REALM_ID")
  private String realmId;

  @Column(name = "USER_ID")
  private String userId;

  @Column(name = "LOGIN_USERNAME")
  private String loginUsername;

  @Column(name = "IP_ADDRESS")
  private String ipAddress;

  @Column(name = "AUTH_METHOD")
  private String authMethod;

  @Column(name = "BROKER_SESSION_ID")
  private String brokerSessionId;

  @Column(name = "BROKER_USER_ID")
  private String brokerUserId;

  @Column(name = "TIMESTAMP")
  private Long timestamp;

  @Column(name = "EXPIRATION")
  private Long expiration;

  @Column(name = "OFFLINE")
  private Boolean offline = false;

  @Column(name = "REMEMBER_ME")
  private Boolean rememberMe = false;

  @Column(name = "LAST_SESSION_REFRESH")
  private Long lastSessionRefresh;

  @Column(name = "STATE")
  @Enumerated(EnumType.STRING)
  private UserSessionModel.State state;

  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name = "NAME")
  @CollectionTable(
      name = "CACHE_USER_SESSION_NOTE",
      joinColumns = @JoinColumn(name = "USER_SESSION_ID"))
  @Column(name = "NOTE")
  private Map<String, String> notes = new HashMap<>();

  @Builder.Default
  @OneToMany(cascade = CascadeType.ALL, mappedBy = "parentSession")
  @MapKeyColumn(name = "CLIENT_ID")
  private Map<String, AuthenticatedClientSessionValue> clientSessions = new HashMap<>();

  @Column(name = "PERSISTENCE_STATE")
  @Enumerated(EnumType.STRING)
  private UserSessionModel.SessionPersistenceState persistenceState;

  public boolean hasCorrespondingSession() {
    return getNotes().containsKey(CORRESPONDING_SESSION_ID);
  }

  public Map<String, String> getNotes() {
    if (notes == null) {
      notes = new HashMap<>();
    }
    return notes;
  }

  public Map<String, AuthenticatedClientSessionValue> getClientSessions() {
    if (clientSessions == null) {
      clientSessions = new HashMap<>();
    }
    return clientSessions;
  }

  public boolean isOffline() {
    return offline != null ? offline.booleanValue() : false;
  }
}
