package io.phasetwo.keycloak.redis.userSession;

import static io.phasetwo.keycloak.redis.userSession.expiration.RedisSessionExpiration.setClientSessionExpiration;

import com.google.common.collect.ImmutableMap;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.userSession.expiration.SessionExpirationData;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

@JBossLog
public class RedisAuthenticatedClientSessionAdapter extends MapEntity<AuthenticatedClientSessionKey>
    implements AuthenticatedClientSessionModel, ExpirableEntity {

  private final KeycloakSession session;

  private static final String REFRESH_TOKEN_LAST_USE_PREFIX = "refreshTokenLastUsePrefix";

  public RedisAuthenticatedClientSessionAdapter(
      KeycloakSession session, String realmId, String id) {
    this(session, realmId, id, null);
  }

  public RedisAuthenticatedClientSessionAdapter(
      KeycloakSession session, String realmId, String id, Map<String, String> existingData) {
    super(new AuthenticatedClientSessionKey(realmId, id), existingData);
    this.session = session;
    setFieldFromKey("id", id);
    setFieldFromKey("realmId", realmId);
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    if (getRealmId() != null) {
      siPutIf(b, getParentId(),
        KeyFormat.clientSessionParentIndex(getRealmId(), getParentId()), getKey().key());
      siPutIf(b, getClientUuid(),
        KeyFormat.clientSessionClientIndex(getRealmId(), getClientUuid()), getKey().key());
    }
    return b.build();
  }

  @Override
  public void detachFromUserSession() {
    getUserSession().removeAuthenticatedClientSessions(Collections.singleton(getClientUuid()));
  }

  @Override
  public UserSessionModel getUserSession() {
    // find both offline and online sessions. TODO: find a more efficient way to determine the
    // transaction state
    return Stream.of(
            session.sessions().getUserSession(getRealm(), getParentId()),
            session.sessions().getOfflineUserSession(getRealm(), getParentId()))
        .filter(Objects::nonNull)
        .findAny()
        .orElse(null);
  }

  @Override
  public String getId() {
    return getString("id");
  }

  public String getParentId() {
    return getString("parentId");
  }

  public void setParentId(String parentId) {
    setField("parentId", parentId);
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
    ns.putAll(notes);
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
  public void setTimestamp(int timestamp) {
    setField("timestamp", timestamp);
    var data = (RedisUserSessionAdapter) this.getUserSession();
    setClientSessionExpiration(
        this,
        SessionExpirationData.builder().realm(getRealm()).build(),
        getClient(),
        data.isOffline());
  }

  @Override
  public int getTimestamp() {
    return getInt("timestamp", 0);
  }

  // Re: refresh token fields
  // might be good to store these as fields, instead of using defaults, which use notes
  // as they will get updated frequently and will benefit from partial field update, rather
  // than having to serialize the whole note map

  @Override
  public void setRefreshToken(String reuseId, String refreshToken) {
    setField(refreshTokenKey("refreshToken", reuseId), refreshToken);
  }

  @Override
  public String getRefreshToken(String reuseId) {
    return getString(refreshTokenKey("refreshToken", reuseId));
  }

  @Override
  public void setRefreshTokenUseCount(String reuseId, int count) {
    String currentCountStr = getNote(REFRESH_TOKEN_USE_PREFIX + reuseId);
    int currentCount =
        currentCountStr == null || currentCountStr.isEmpty()
            ? 0
            : Integer.parseInt(currentCountStr);

    if (count != currentCount) {
      setNote(REFRESH_TOKEN_LAST_USE_PREFIX + reuseId, String.valueOf(Time.currentTimeMillis()));
      setNote(REFRESH_TOKEN_USE_PREFIX + reuseId, String.valueOf(count));
    }
  }

  @Override
  public int getRefreshTokenUseCount(String reuseId) {
    String currentCount = getNote(REFRESH_TOKEN_USE_PREFIX + reuseId);

    if (currentCount == null) {
      return 0;
    }

    String lastUseTimestampString = getNote(REFRESH_TOKEN_LAST_USE_PREFIX + reuseId);
    if (lastUseTimestampString == null) {
      return Integer.parseInt(currentCount);
    }

    long lastUseTimestamp = Long.parseLong(lastUseTimestampString);
    if (lastUseTimestamp
        > Time.currentTimeMillis()
            - session
                .realms()
                .getRealm(getRealmId())
                .getAttribute("refreshTokenReuseInterval", 0L)) {
      return Math.max(0, Integer.parseInt(currentCount) - 1); // do not count refresh
    }

    return Integer.parseInt(currentCount);
  }

  @Override
  public void setRefreshTokenLastRefresh(String reuseId, int refreshTokenLastRefresh) {
    setField(refreshTokenKey("refreshTokenLastRefresh", reuseId), refreshTokenLastRefresh);
  }

  @Override
  public int getRefreshTokenLastRefresh(String reuseId) {
    return getInt(refreshTokenKey("refreshTokenLastRefresh", reuseId), 0);
  }

  static String refreshTokenKey(String key, String reuseId) {
    return String.format("%s:%s", key, reuseId);
  }

  // end: refresh token fields

  @Override
  public RealmModel getRealm() {
    return session.realms().getRealm(getRealmId());
  }

  public void setRealmId(String realmId) {
    setField("realmId", realmId);
  }

  public String getRealmId() {
    return getString("realmId");
  }

  @Override
  public ClientModel getClient() {
    return getRealm().getClientById(getClientUuid());
  }

  public void setClientUuid(String uuid) {
    setField("clientUuid", uuid);
  }

  public String getClientUuid() {
    return getString("clientUuid");
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

  @Override
  public Long getExpiration() {
    if (isNull("expiration")) return null;
    return getLong("expiration", 0L);
  }

  @Override
  public void setExpiration(Long expiration) {
    setField("expiration", expiration);
  }
}
