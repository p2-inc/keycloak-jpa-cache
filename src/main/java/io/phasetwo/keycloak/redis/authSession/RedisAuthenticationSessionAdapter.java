package io.phasetwo.keycloak.redis.authSession;

import com.google.common.collect.ImmutableMap;
import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.MapEntity;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

@JBossLog
public class RedisAuthenticationSessionAdapter extends MapEntity<AuthenticationSessionKey>
    implements AuthenticationSessionModel {

  private final KeycloakSession session;

  private RootAuthenticationSessionModel parent;

  public RedisAuthenticationSessionAdapter(KeycloakSession session, AuthenticationSessionKey key) {
    this(session, key, null);
  }

  public RedisAuthenticationSessionAdapter(
      KeycloakSession session, AuthenticationSessionKey key, Map<String, String> existingData) {

    super(key, existingData);
    this.session = session;
    setFieldFromKey("tabId", key.tabId());
    setFieldFromKey("parentId", key.rootId());
    setFieldFromKey("realmId", key.realmId());
    setFieldFromKey("clientUuid", key.clientId());
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    siPutIf(b, getString("parentId"),
        KeyFormat.authSessionParentIndex(getString("realmId"), getString("parentId")),
        getKey().key());
    return b.build();
  }

  @Override
  public String getTabId() {
    return getString("tabId");
  }

  @Override
  public RootAuthenticationSessionModel getParentSession() {
    if (parent == null) {
      this.parent =
          session
              .authenticationSessions()
              .getRootAuthenticationSession(getRealm(), getString("parentId"));
    }
    return this.parent;
  }

  public void setParentSession(RootAuthenticationSessionModel parent) {
    setField("parentId", parent.getId());
    setField("realmId", parent.getRealm().getId());
    this.parent = parent;
  }

  public void setTimestamp(long timestamp) {
    setField("timestamp", timestamp);
  }

  public long getTimestamp() {
    return getLong("timestamp", 0);
  }

  @Override
  public RealmModel getRealm() {
    return session.realms().getRealm(getString("realmId"));
  }

  @Override
  public ClientModel getClient() {
    return getRealm().getClientById(getString("clientUuid"));
  }

  public void setClientUuid(String uuid) {
    setField("clientUuid", uuid);
  }

  @Override
  public String getRedirectUri() {
    return getString("redirectUri");
  }

  @Override
  public void setRedirectUri(String uri) {
    setField("redirectUri", uri);
  }

  @Override
  public String getAction() {
    return getString("action");
  }

  @Override
  public void setAction(String action) {
    setField("action", action);
  }

  @Override
  public String getProtocol() {
    return getString("protocol");
  }

  @Override
  public void setProtocol(String protocol) {
    setField("protocol", protocol);
  }

  public String getUserId() {
    return getString("userId");
  }

  public void setUserId(String userId) {
    setField("userId", userId);
  }

  @Override
  public Set<String> getClientScopes() {
    return getSet("clientScopes");
  }

  @Override
  public void setClientScopes(Set<String> clientScopes) {
    Set<String> scopes = getSet("clientScopes");
    scopes.clear();
    for (String clientScope : clientScopes) {
      scopes.add(clientScope);
    }
  }

  @Override
  public Map<String, String> getClientNotes() {
    return getMap("clientNotes");
  }

  @Override
  public String getClientNote(String name) {
    return getClientNotes().get(name);
  }

  @Override
  public void setClientNote(String name, String value) {
    if (value == null) {
      removeClientNote(name);
    } else {
      getClientNotes().put(name, value);
    }
  }

  @Override
  public void removeClientNote(String name) {
    getClientNotes().remove(name);
  }

  @Override
  public void clearClientNotes() {
    getClientNotes().clear();
  }

  public Map<String, String> getAuthNotes() {
    return getMap("authNotes");
  }

  @Override
  public String getAuthNote(String name) {
    return getAuthNotes().get(name);
  }

  @Override
  public void setAuthNote(String name, String value) {
    if (value == null) {
      removeAuthNote(name);
    } else {
      getAuthNotes().put(name, value);
    }
  }

  @Override
  public void removeAuthNote(String name) {
    getAuthNotes().remove(name);
  }

  @Override
  public void clearAuthNotes() {
    getAuthNotes().clear();
  }

  @Override
  public Map<String, String> getUserSessionNotes() {
    return getMap("userSessionNotes");
  }

  @Override
  public void setUserSessionNote(String name, String value) {
    if (value == null) {
      getUserSessionNotes().remove(name);
    } else {
      getUserSessionNotes().put(name, value);
    }
  }

  @Override
  public void clearUserSessionNotes() {
    getUserSessionNotes().clear();
  }

  @Override
  public Set<String> getRequiredActions() {
    return getSet("requiredActions");
  }

  @Override
  public void addRequiredAction(String action) {
    getRequiredActions().add(action);
  }

  @Override
  public void removeRequiredAction(String action) {
    getRequiredActions().remove(action);
  }

  @Override
  public void addRequiredAction(UserModel.RequiredAction action) {
    addRequiredAction(action.name());
  }

  @Override
  public void removeRequiredAction(UserModel.RequiredAction action) {
    removeRequiredAction(action.name());
  }

  public Map<String, String> getExecStatus() {
    return getMap("executionStatus");
  }

  @Override
  public Map<String, AuthenticationSessionModel.ExecutionStatus> getExecutionStatus() {
    return getExecStatus().entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry -> AuthenticationSessionModel.ExecutionStatus.valueOf(entry.getValue())));
    // todo this is a clone of the map so modifications won't work
  }

  @Override
  public void setExecutionStatus(
      String authenticator, AuthenticationSessionModel.ExecutionStatus status) {
    getExecStatus().put(authenticator, status.name());
  }

  @Override
  public void clearExecutionStatus() {
    getExecStatus().clear();
  }

  @Override
  public UserModel getAuthenticatedUser() {
    // todo
    log.trace("getAuthenticatedUser");
    if (getUserId() == null) return null;
    return session.users().getUserById(getRealm(), getUserId());

    /* TODO transient/lightweight users
    if (updater.getEntity().getAuthUserId() == null) {
      return null;
    }

    if (Profile.isFeatureEnabled(Feature.TRANSIENT_USERS) && getUserSessionNotes().containsKey(SESSION_NOTE_LIGHTWEIGHT_USER)) {
      LightweightUserAdapter cachedUser = session.getAttribute("authSession.user." + parent.getId(), LightweightUserAdapter.class);

      if (cachedUser != null) {
        return cachedUser;
      }

      LightweightUserAdapter lua = LightweightUserAdapter.fromString(session, parent.getRealm(), getUserSessionNotes().get(SESSION_NOTE_LIGHTWEIGHT_USER));
      session.setAttribute("authSession.user." + parent.getId(), lua);
      lua.setUpdateHandler(lua1 -> {
          if (lua == lua1) {  // Ensure there is no conflicting user model, only the latest lightweight user can be used
            setUserSessionNote(SESSION_NOTE_LIGHTWEIGHT_USER, lua1.serialize());
          }
        });

      return lua;
    } else {
      return session.users().getUserById(getRealm(), updater.getEntity().getAuthUserId());
    }
    */
  }

  @Override
  public void setAuthenticatedUser(UserModel user) {
    // todo
    log.tracef("setAuthenticatedUser %s", user);
    if (user == null) {
      setUserId(null);
    } else {
      setUserId(user.getId());
    }

    /* TODO transient/lightweight users
    if (user == null) {
      updater.getEntity().setAuthUserId(null);
      setUserSessionNote(SESSION_NOTE_LIGHTWEIGHT_USER, null);
    } else {
      updater.getEntity().setAuthUserId(user.getId());

      if (isLightweightUser(user)) {
        LightweightUserAdapter lua = (LightweightUserAdapter) user;
        setUserSessionNote(SESSION_NOTE_LIGHTWEIGHT_USER, lua.serialize());
        lua.setUpdateHandler(lua1 -> {
            if (lua == lua1) {  // Ensure there is no conflicting user model, only the latest lightweight user can be used
              setUserSessionNote(SESSION_NOTE_LIGHTWEIGHT_USER, lua1.serialize());
            }
          });
      }
    }
    update();
    */
  }

  public void setAuthNotes(Map<String, String> authNotes) {
    if (getAuthNotes() != null) {
      getAuthNotes().clear();
      getAuthNotes().putAll(authNotes);
    }
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || o instanceof AuthenticationSessionModel that && that.getTabId().equals(getTabId());
  }

  @Override
  public int hashCode() {
    return getTabId().hashCode();
  }
}
