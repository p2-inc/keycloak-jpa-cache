package io.phasetwo.keycloak.jpacache.authSession.persistence.entities;

import lombok.*;
import jakarta.persistence.*;
import org.keycloak.sessions.CommonClientSessionModel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@EqualsAndHashCode(of = {"parentSessionId", "tabId"})
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
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
  private Map<String, CommonClientSessionModel.ExecutionStatus> executionStatus = new HashMap<>();

  @Builder.Default
  private Set<String> requiredActions = new HashSet<>();

  @Builder.Default
  private Set<String> clientScopes = new HashSet<>();
  
  @Builder.Default
  private Map<String, String> userNotes = new HashMap<>();
  
  @Builder.Default
  private Map<String, String> authNotes = new HashMap<>();
  
  @Builder.Default
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
