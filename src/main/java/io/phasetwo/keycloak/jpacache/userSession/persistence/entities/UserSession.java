package io.phasetwo.keycloak.jpacache.userSession.persistence.entities;

import lombok.*;
import jakarta.persistence.*;
import java.util.Date;
import org.keycloak.models.UserSessionModel;
import io.phasetwo.keycloak.mapstorage.common.ExpirableEntity;
import java.util.HashMap;
import java.util.Map;
import static org.keycloak.models.UserSessionModel.CORRESPONDING_SESSION_ID;

@EqualsAndHashCode(of = "id")
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "CACHE_USER_SESSION")
@NamedQueries({@NamedQuery(name = "findUserSessionsByUserId", query = "SELECT s FROM UserSession s WHERE s.realmId = :realmId AND s.userId = :userId"), @NamedQuery(name = "findUserSessionsByBrokerSessionId", query = "SELECT s FROM UserSession s WHERE s.realmId = :realmId AND s.brokerSessionId = :brokerSessionId"), @NamedQuery(name = "findUserSessionsByBrokerUserId", query = "SELECT s FROM BrokerUserSession s WHERE s.realmId = :realmId AND s.brokerUserId = :userId"), @NamedQuery(name = "findAllUserSessions", query = "SELECT s FROM UserSession WHERE s.realmId = :realmId"), @NamedQuery(name = "removeAllUserSessions", query = "SELECT s FROM UserSession WHERE s.realmId = :realmId"), @NamedQuery(name = "countOfflineUserSessions", query = "SELECT count(s) FROM UserSession WHERE s.realmId = :realmId AND s.offline = :offline")})
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
  private Boolean offline = false;;

  @Column(name = "REMEMBER_ME")
  private Boolean rememberMe = false;

  @Column(name = "LAST_SESSION_REFRESH")
  private Long lastSessionRefresh;

  @Column(name = "STATE")
  @Enumerated(EnumType.STRING)
  private UserSessionModel.State state;

  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @CollectionTable(name="CACHE_USER_SESSION_NOTE", joinColumns=@JoinColumn(name="USER_SESSION_ID"))
  @Column(name = "NOTE")
  private Map<String, String> notes = new HashMap<>();

  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="")
  @CollectionTable(name="CACHE_CLIENT_SESSION", joinColumns=@JoinColumn(name="USER_SESSION_ID"))
  private Map<String, AuthenticatedClientSessionValue> clientSessions = new HashMap<>();

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
