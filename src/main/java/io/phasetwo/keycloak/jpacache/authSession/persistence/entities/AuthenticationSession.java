package io.phasetwo.keycloak.jpacache.authSession.persistence.entities;

import lombok.*;
import jakarta.persistence.*;
import org.keycloak.sessions.CommonClientSessionModel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Date;

@EqualsAndHashCode(of = {"parentSessionId", "tabId"})
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@NamedQueries({@NamedQuery(name = "findAuthSessionsByCompoundId", query = "SELECT s FROM AuthenticationSession s WHERE s.parentSessionId = :parentSessionId AND s.tabId = :tabId AND s.clientId = :clientId"), @NamedQuery(name = "findAuthSessionsByRootSessionId", query = "SELECT s FROM AuthenticationSession s WHERE s.parentSessionId = :parentSessionId")})
@Table(name = "CACHE_AUTH_SESSION")
@Entity
public class AuthenticationSession {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "PARENT_SESSION_ID")
  private RootAuthenticationSession parentSession;

  @Column(name = "TAB_ID")
  private String tabId;

  @Column(name = "USER_ID")
  private String userId;

  @Column(name = "CLIENT_ID")
  private String clientId;

  @Column(name = "REDIRECT_URI")
  private String redirectUri;

  @Column(name = "ACTION")
  private String action;

  @Column(name = "PROTOCOL")
  private String protocol;

  @Column(name = "TIMESTAMP")
  private Date timestamp;

  @Builder.Default
  
  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @CollectionTable(name="CACHE_AUTH_SESSION_EXECUTION_STATUS", joinColumns=@JoinColumn(name="AUTH_SESSION_ID"))
  @Column(name="STATUS")  
  @Enumerated(EnumType.STRING)
  private Map<String, CommonClientSessionModel.ExecutionStatus> executionStatus = new HashMap<>();

  @Builder.Default
  @ElementCollection(targetClass = String.class, fetch = FetchType.LAZY)
  @CollectionTable(name = "CACHE_AUTH_SESSION_REQUIRED_ACTION", joinColumns = @JoinColumn(name = "AUTH_SESSION_ID"))
  @Column(name = "REQUIRED_ACTION", nullable = false)
  private Set<String> requiredActions = new HashSet<>();

  @Builder.Default
  @ElementCollection(targetClass = String.class, fetch = FetchType.LAZY)
  @CollectionTable(name = "CACHE_AUTH_SESSION_CLIENT_SCOPE", joinColumns = @JoinColumn(name = "AUTH_SESSION_ID"))
  @Column(name = "CLIENT_SCOPE", nullable = false)
  private Set<String> clientScopes = new HashSet<>();
  
  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @CollectionTable(name="CACHE_AUTH_SESSION_USER_NOTE", joinColumns=@JoinColumn(name="AUTH_SESSION_ID"))
  @Column(name="NOTE")  
  private Map<String, String> userNotes = new HashMap<>();
  
  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @CollectionTable(name="CACHE_AUTH_SESSION_AUTH_NOTE", joinColumns=@JoinColumn(name="AUTH_SESSION_ID"))
  @Column(name="NOTE")  
  private Map<String, String> authNotes = new HashMap<>();
  
  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @CollectionTable(name="CACHE_AUTH_SESSION_CLIENT_NOTE", joinColumns=@JoinColumn(name="AUTH_SESSION_ID"))
  @Column(name="NOTE")  
  private Map<String, String> clientNotes = new HashMap<>();
  
  public Map<String, CommonClientSessionModel.ExecutionStatus> getExecutionStatus() {
    if (executionStatus == null) {
      executionStatus = new HashMap<>();
    }
    return executionStatus;
  }
  
  public Set<String> getRequiredActions() {
    if (requiredActions == null) {
      requiredActions = new HashSet<>();
    }
    return requiredActions;
  }
  
  public Set<String> getClientScopes() {
    if (clientScopes == null) {
      clientScopes = new HashSet<>();
    }
    return clientScopes;
  }
  
  public Map<String, String> getUserNotes() {
    if (userNotes == null) {
      userNotes = new HashMap<>();
    }
    return userNotes;
  }
  
  public Map<String, String> getAuthNotes() {
    if (authNotes == null) {
      authNotes = new HashMap<>();
    }
    return authNotes;
  }
  
  public Map<String, String> getClientNotes() {
    if (clientNotes == null) {
      clientNotes = new HashMap<>();
    }
    return clientNotes;
  }
}
