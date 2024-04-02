package io.phasetwo.keycloak.jpacache.userSession.persistence.entities;

import io.phasetwo.keycloak.mapstorage.common.ExpirableEntity;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.keycloak.models.UserSessionModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static org.keycloak.models.UserSessionModel.CORRESPONDING_SESSION_ID;

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
          "SELECT count(s) FROM UserSession s WHERE s.realmId = :realmId AND s.offline = :offline"),
  @NamedQuery(
      name="countClientSessionsByClientIds",
      query="SELECT clientId, count(*)" +
          " FROM UserSession u INNER JOIN u.clientSessions clientSessions" +
          " WHERE u.offline = :offline AND u.realmId = :realmId " +
          " GROUP BY clientSessions.clientId")
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

  @Builder.Default
  @OneToMany(cascade ={CascadeType.REMOVE}, orphanRemoval = true, mappedBy = "userSession")
  protected Collection<UserSessionToAttributeMapping> attributes = new LinkedList<>();

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
    return offline != null ? offline : false;
  }

  @PostPersist
  void postPersist(){
    System.out.println("UserSession: " + this.id);
  }
}
