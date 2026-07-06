package io.phasetwo.keycloak.redis.userSession;

import static io.phasetwo.keycloak.common.ExpirationUtils.isExpired;
import static io.phasetwo.keycloak.redis.userSession.expiration.RedisSessionExpiration.setUserSessionExpiration;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import io.phasetwo.keycloak.redis.userSession.expiration.SessionExpirationData;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
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
      String realmId,
      String id) {
    this(session, jedis, clientSessionTrx, realmId, id, null);
  }

  public RedisUserSessionAdapter(
      KeycloakSession session,
      UnifiedJedis jedis,
      RedisChangelogTransaction<
              AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
          clientSessionTrx,
      String realmId,
      String id,
      Map<String, String> existingData) {
    super(new UserSessionKey(realmId, id), existingData);
    this.session = session;
    this.jedis = jedis;
    this.clientSessionTrx = clientSessionTrx;
    setFieldFromKey("id", id);
    setFieldFromKey("realmId", realmId);
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    // Index names are KeyFormat-driven (historical layout by default;
    // realm-hash-tagged C§3 grammar in serverless mode).
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    siPutIf(b, getRealmId(), KeyFormat.userSessionRealmIndex(getRealmId()), getKey().key());
    siPutIf(b, getUserId(),
        KeyFormat.userSessionUserIndex(getRealmId(), getUserId()), getKey().key());
    siPutIf(b, getBrokerUserId(),
        KeyFormat.userSessionBrokerUserIndex(getRealmId(), getBrokerUserId()), getKey().key());
    siPutIf(b, getBrokerSessionId(),
        KeyFormat.userSessionBrokerSessionIndex(getRealmId(), getBrokerSessionId()),
        getKey().key());
    String csi = getNote(CORRESPONDING_SESSION_ID);
    siPutIf(b, csi, KeyFormat.userSessionCorrespondingIndex(getRealmId(), csi), getKey().key());
    return b.build();
  }

  @Override
  public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() {

    String indexKey = KeyFormat.clientSessionParentIndex(getRealmId(), getId());
    log.tracef("[redis] SMEMBERS %s", indexKey);
    Set<String> strIds = jedis.smembers(indexKey);
    if (strIds != null && !strIds.isEmpty()) {
      clientSessions =
          strIds.stream()
              .filter(raw -> {
                // audit §4.7 index hygiene: skip members whose value key
                // expired; the reap itself DEFERS to transaction commit —
                // no Redis writes outside commitImpl.
                if (clientSessionTrx.getIfPresent(
                        AuthenticatedClientSessionKey.fromString(raw)) == null) {
                  clientSessionTrx.reapIndexMemberOnCommit(indexKey, raw);
                  return false;
                }
                return true;
              })
              .map(AuthenticatedClientSessionKey::fromString)
              .map(clientSessionTrx::getIfPresent)
              .filter(Objects::nonNull)
              .filter(this::filterAndRemoveExpiredClientSessions)
              .filter(this::matchingOfflineFlag)
              .filter(this::filterAndRemoveClientSessionWithoutClient)
              .collect(
                  Collectors.toMap(
                      RedisAuthenticatedClientSessionAdapter::getClientUuid,
                      s -> (AuthenticatedClientSessionModel) s));
    }
    return clientSessions;
  }

  private boolean filterAndRemoveClientSessionWithoutClient(
      RedisAuthenticatedClientSessionAdapter redisAuthenticatedClientSessionAdapter) {
    ClientModel client =
        session
            .clients()
            .getClientById(
                redisAuthenticatedClientSessionAdapter.getRealm(),
                redisAuthenticatedClientSessionAdapter.getClientUuid());

    return client != null;
  }

  private boolean matchingOfflineFlag(
      RedisAuthenticatedClientSessionAdapter redisAuthenticatedClientSessionAdapter) {
    boolean isClientSessionOffline =
        redisAuthenticatedClientSessionAdapter.getUserSession().isOffline();

    return isOffline() == isClientSessionOffline;
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
      if (ac != null) {
        RedisAuthenticatedClientSessionAdapter a;
        if (ac instanceof RedisAuthenticatedClientSessionAdapter) {
          a = (RedisAuthenticatedClientSessionAdapter) ac;
        } else {
          a = clientSessionTrx.get(new AuthenticatedClientSessionKey(getRealmId(), ac.getId()));
        }
        if (a != null) {
          clientSessionTrx.addForDelete(a);
          acs.remove(clientUuid);
        }
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
