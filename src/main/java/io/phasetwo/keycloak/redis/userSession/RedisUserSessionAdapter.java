package io.phasetwo.keycloak.redis.userSession;

import static io.phasetwo.keycloak.common.ExpirationUtils.isExpired;
import static io.phasetwo.keycloak.redis.userSession.expiration.RedisSessionExpiration.setUserSessionExpiration;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import io.phasetwo.keycloak.redis.userSession.expiration.SessionExpirationData;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.models.*;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisUserSessionAdapter extends MapEntity<UserSessionKey>
    implements UserSessionModel, ExpirableEntity {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;
  private final RedisChangelogTransaction<
          AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
      clientSessionTrx;

  private Map<String, AuthenticatedClientSessionModel> clientSessions = Maps.newHashMap();

  public RedisUserSessionAdapter(
      KeycloakSession session,
      UnifiedJedis jedis,
      RedisChangelogTransaction<
              AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
          clientSessionTrx,
      String id) {
    this(session, jedis, clientSessionTrx, id, null);
  }

  public RedisUserSessionAdapter(
      KeycloakSession session,
      UnifiedJedis jedis,
      RedisChangelogTransaction<
              AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
          clientSessionTrx,
      String id,
      Map<String, String> existingData) {
    super(new UserSessionKey(id), existingData);
    this.session = session;
    this.jedis = jedis;
    this.clientSessionTrx = clientSessionTrx;
    setField("id", id);
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    siPut(b, "user-session:realm-index:%s", getRealmId(), getKey().key());
    siPut(b, "user-session:user-index:%s", getUserId(), getKey().key());
    siPut(b, "user-session:broker-user-index:%s", getBrokerUserId(), getKey().key());
    siPut(b, "user-session:broker-session-index:%s", getBrokerSessionId(), getKey().key());
    String csi = getNote(CORRESPONDING_SESSION_ID);
    siPut(b, "user-session:corresponding-session-index:%s", csi, getKey().key());
    return b.build();
  }

  @Override
  public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() {

    String indexKey = String.format("authenticated-client:parent-index:%s", getId());
    log.tracef("[redis] SMEMBERS %s", indexKey);
    Set<String> strIds = jedis.smembers(indexKey);
    if (strIds != null && !strIds.isEmpty()) {
      // Resolve members; a member whose client-session hash has expired/vanished is dangling and is
      // registered for reap-at-commit so the parent-index self-heals without writing on the read
      // path, in every mode (issue #78).
      List<RedisAuthenticatedClientSessionAdapter> live = Lists.newArrayList();
      for (String strId : strIds) {
        RedisAuthenticatedClientSessionAdapter cs =
            clientSessionTrx.getIfPresent(AuthenticatedClientSessionKey.fromString(strId));
        if (cs == null) {
          clientSessionTrx.reapIndexMemberOnCommit(indexKey, strId);
        } else {
          live.add(cs);
        }
      }
      // Grow the parent-index Set TTL to cover its longest-lived live member (or PERSIST when a
      // live member never expires), exactly as the provider's user-index/client-index read paths do
      // (issue #78, review finding A). The Set's TTL is stamped on write from whichever client
      // session last touched it, so a longer-lived client session that is never re-written could be
      // stranded under a shorter co-tenant's TTL and silently dropped from the parent-index; reads
      // must push it back up. Deferred to commit — never written on the read path.
      clientSessionTrx.extendIndexTtlToCoverLiveMembers(
          indexKey,
          live.stream()
              .map(RedisAuthenticatedClientSessionAdapter::getExpiration)
              .collect(Collectors.toList()));
      clientSessions =
          live.stream()
              .filter(this::filterAndRemoveExpiredClientSessions)
              .filter(this::belongsToThisUserSession)
              .filter(this::matchingOfflineFlag)
              .filter(this::hasResolvableClient)
              .collect(
                  Collectors.toMap(
                      RedisAuthenticatedClientSessionAdapter::getClientUuid,
                      s -> (AuthenticatedClientSessionModel) s));
    }
    return clientSessions;
  }

  /**
   * Whether this client session's client (and realm) still resolve.
   *
   * <p>Despite the name this deliberately does <em>not</em> reap: the lookup goes to JPA, and under
   * concurrent load a transient miss would delete a live client session, which {@code
   * UserSessionConcurrencyTest} reproduces as a CAS storm. Hiding a session used to make it
   * permanently undeletable, because every explicit delete path reads through this same filtered
   * map; that hole is closed in {@link #removeAuthenticatedClientSessions(Collection)} instead,
   * which falls back to the deterministic key (issue #81).
   */
  private boolean hasResolvableClient(RedisAuthenticatedClientSessionAdapter clientSession) {
    RealmModel realm = clientSession.getRealm();
    return realm != null
        && session.clients().getClientById(realm, clientSession.getClientUuid()) != null;
  }

  private boolean belongsToThisUserSession(RedisAuthenticatedClientSessionAdapter clientSession) {
    if (getId().equals(clientSession.getParentId())) return true;
    // A member of authenticated-client:parent-index:<this> whose parentId points somewhere else is
    // a stale or corrupt index entry, not one of our client sessions. Comparing parent ids is both
    // exact and free; the offline-flag comparison below only caught this case by accident, through
    // the null parent lookup of a dangling parentId (issue #78 review, issue #81).
    log.warnf(
        "Client session %s is indexed under user session %s but claims parent %s",
        clientSession.getId(), getId(), clientSession.getParentId());
    return false;
  }

  private boolean matchingOfflineFlag(RedisAuthenticatedClientSessionAdapter clientSession) {
    // Prefer the flag stored on the client session: it costs no lookup and it still answers for an
    // orphan, whose parent user session is by definition unreachable (issue #81). Only a hash
    // written before that field existed needs the parent, and an orphan among those cannot belong
    // to this user session's view.
    Boolean clientSessionOffline = clientSession.isOffline();
    if (clientSessionOffline == null) {
      UserSessionModel parent = clientSession.getUserSession();
      if (parent == null) return false;
      clientSessionOffline = parent.isOffline();
    }
    return isOffline() == clientSessionOffline;
  }

  private boolean filterAndRemoveExpiredClientSessions(
      RedisAuthenticatedClientSessionAdapter redisAuthenticatedClientSessionAdapter) {
    try {
      if (isExpired(redisAuthenticatedClientSessionAdapter, false)) {
        clientSessionTrx.addForDelete(redisAuthenticatedClientSessionAdapter);
        return false;
      }
    } catch (ModelIllegalStateException ex) {
      clientSessionTrx.addForDelete(redisAuthenticatedClientSessionAdapter);
      // clientSessions.remove(redisAuthenticatedClientSessionAdapter);
      return false;
    }

    return true;
  }

  @Override
  public int getStarted() {
    return getTimestamp();
  }

  @Override
  public boolean isOffline() {
    return getBool("offline", false);
  }

  public void setOffline(boolean offline) {
    setField("offline", offline);
  }

  @Override
  public boolean isRememberMe() {
    return getBool("rememberMe", false);
  }

  public void setRememberMe(boolean rememberMe) {
    setField("rememberMe", rememberMe);
  }

  @Override
  public UserModel getUser() {
    return session.users().getUserById(getRealm(), getUserId());
  }

  public String getUserId() {
    return getString("userId");
  }

  public void setUserId(String userId) {
    setField("userId", userId);
  }

  @Override
  public RealmModel getRealm() {
    return session.realms().getRealm(getRealmId());
  }

  public String getRealmId() {
    return getString("realmId");
  }

  public void setRealmId(String realmId) {
    setField("realmId", realmId);
  }

  @Override
  public String getId() {
    return getString("id");
  }

  @Override
  public String getAuthMethod() {
    return getString("authMethod");
  }

  public void setAuthMethod(String authMethod) {
    setField("authMethod", authMethod);
  }

  @Override
  public String getBrokerSessionId() {
    return getString("brokerSessionId");
  }

  public void setBrokerSessionId(String brokerSessionId) {
    setField("brokerSessionId", brokerSessionId);
  }

  @Override
  public String getBrokerUserId() {
    return getString("brokerUserId");
  }

  public void setBrokerUserId(String brokerUserId) {
    setField("brokerUserId", brokerUserId);
  }

  @Override
  public String getIpAddress() {
    return getString("ipAddress");
  }

  public void setIpAddress(String ipAddress) {
    setField("ipAddress", ipAddress);
  }

  @Override
  public String getLoginUsername() {
    return getString("loginUsername");
  }

  public void setLoginUsername(String loginUsername) {
    setField("loginUsername", loginUsername);
  }

  @Override
  public void setLastSessionRefresh(int lastSessionRefresh) {
    setField("lastSessionRefresh", lastSessionRefresh);

    setUserSessionExpiration(this, SessionExpirationData.builder().realm(getRealm()).build());
  }

  @Override
  public int getLastSessionRefresh() {
    return getInt("lastSessionRefresh", 0);
  }

  public void setTimestamp(int timestamp) {
    setField("timestamp", timestamp);
  }

  public int getTimestamp() {
    return getInt("timestamp", 0);
  }

  @Override
  public void setNote(String name, String value) {
    if (value == null) {
      removeNote(name);
    } else {
      getNotes().put(name, value);
    }
  }

  public void setNotes(Map<String, String> notes) {
    Map<String, String> ns = getNotes();
    ns.clear();
    for (Map.Entry<String, String> note : notes.entrySet()) {
      ns.put(note.getKey(), note.getValue());
    }
  }

  @Override
  public Map<String, String> getNotes() {
    return getMap("notes");
  }

  @Override
  public String getNote(String name) {
    return getNotes().get(name);
  }

  @Override
  public void removeNote(String name) {
    getNotes().remove(name);
  }

  @Override
  public UserSessionModel.SessionPersistenceState getPersistenceState() {
    String psStr = getString("persistenceState");
    if (psStr != null) return UserSessionModel.SessionPersistenceState.valueOf(psStr);
    else return null;
  }

  public void setPersistenceState(UserSessionModel.SessionPersistenceState persistenceState) {
    setField("persistenceState", persistenceState.name());
  }

  @Override
  public UserSessionModel.State getState() {
    String stateStr = getString("state");
    if (stateStr != null) return UserSessionModel.State.valueOf(stateStr);
    else return null;
  }

  @Override
  public void setState(UserSessionModel.State state) {
    if (state == null) return;
    setField("state", state.name());
  }

  @Override
  public void restartSession(
      RealmModel realm,
      UserModel user,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId) {
    String correspondingSessionId = getNote(CORRESPONDING_SESSION_ID);

    setRealmId(realm.getId());
    setUserId(user.getId());
    setLoginUsername(loginUsername);
    setIpAddress(ipAddress);
    setAuthMethod(authMethod);
    setRememberMe(rememberMe);
    setBrokerSessionId(brokerSessionId);
    setBrokerUserId(brokerUserId);
    int currentTime = Time.currentTime();
    setTimestamp(currentTime);
    setLastSessionRefresh(currentTime);
    setNotes(Maps.newConcurrentMap());
    removeField("state");
    removeAuthenticatedClientSessions(Sets.newHashSet(getAuthenticatedClientSessions().keySet()));

    if (correspondingSessionId != null) {
      setNote(CORRESPONDING_SESSION_ID, correspondingSessionId);
    }
  }

  @Override
  public void removeAuthenticatedClientSessions(Collection<String> removedClientUUIDS) {
    Map<String, AuthenticatedClientSessionModel> acs = getAuthenticatedClientSessions();
    for (String clientUuid : removedClientUUIDS) {
      AuthenticatedClientSessionModel ac = acs.get(clientUuid);
      RedisAuthenticatedClientSessionAdapter a;
      if (ac instanceof RedisAuthenticatedClientSessionAdapter) {
        a = (RedisAuthenticatedClientSessionAdapter) ac;
      } else if (ac != null) {
        a = clientSessionTrx.get(new AuthenticatedClientSessionKey(ac.getId()));
      } else {
        // getAuthenticatedClientSessions() is filtered (expired / offline-flag mismatch / deleted
        // client), so a client session that is very much alive in Redis can be invisible here.
        // Deleting is the whole point of this call -- reporting a successful revoke while removing
        // nothing is the worst outcome -- so fall back to the deterministic key (issue #81).
        a =
            clientSessionTrx.getIfPresent(
                new AuthenticatedClientSessionKey(
                    RedisAuthenticatedClientSessionAdapter.clientSessionId(getId(), clientUuid)));
        if (a == null) {
          log.warnf(
              "No client session to remove for client %s on user session %s", clientUuid, getId());
        }
      }
      if (a != null) {
        clientSessionTrx.addForDelete(a);
        acs.remove(clientUuid);
      }
    }
  }

  @Override
  public Long getExpiration() {
    if (isNull("expiration")) return null;
    return getLong("expiration", 0L);
  }

  @Override
  public void setExpiration(Long expiration) {
    setField("expiration", expiration);
  }

  public void addAuthenticatedClientSession(
      RedisAuthenticatedClientSessionAdapter clientSessionEntity) {
    clientSessionEntity.setParentId(this.getId());
    clientSessionTrx.addForSave(clientSessionEntity);
  }
}
