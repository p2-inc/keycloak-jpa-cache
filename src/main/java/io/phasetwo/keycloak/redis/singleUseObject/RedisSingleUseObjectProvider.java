package io.phasetwo.keycloak.redis.singleUseObject;

import com.google.common.collect.Maps;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisSingleUseObjectProvider implements SingleUseObjectProvider {
  private final KeycloakSession session;
  private final UnifiedJedis jedis;
  private final RedisChangelogTransaction<SingleUseObjectKey, RedisSingleUseObjectAdapter> suoTrx;

  public RedisSingleUseObjectProvider(
      KeycloakSession session, UnifiedJedis jedis, RedisMode redisMode) {
    this.jedis = jedis;
    this.session = session;
    this.suoTrx =
        RedisChangelogTransaction.create(
            "singleUseObject",
            jedis,
            redisMode,
            new SingleUseObjectAdapterSupplier(session, jedis));
    session.getTransactionManager().enlistAfterCompletion(suoTrx);
  }

  @Override
  public void put(String key, long lifespanSeconds, Map<String, String> notes) {
    log.debugf("[redis] Put %s %s", key, notes);
    RedisSingleUseObjectAdapter a = suoTrx.get(new SingleUseObjectKey(key));
    if (a != null) {
      a.setExpiration(Time.currentTimeMillis() + (lifespanSeconds * 1000L));
      a.replaceNotes(notes);
    } else {
      var suoAdapter = new RedisSingleUseObjectAdapter(session, key, notes);
      suoAdapter.setExpiration(Time.currentTimeMillis() + (lifespanSeconds * 1000L));
      suoTrx.addForSave(suoAdapter);
    }
  }

  @Override
  public Map<String, String> get(String key) {
    RedisSingleUseObjectAdapter a = suoTrx.getIfPresent(new SingleUseObjectKey(key));
    if (a == null) {
      return null;
    } else {
      return convertNulls(a.getNotes());
    }
  }

  @Override
  public Map<String, String> remove(String key) {
    RedisSingleUseObjectAdapter a = suoTrx.getIfPresent(new SingleUseObjectKey(key));
    if (a == null) return null;
    Map<String, String> notes = Maps.newHashMap(a.getNotes()); // TODO need to clone?
    //    Map<String, String> ns = notes == null ? null : convertNulls(notes);
    suoTrx.addForDelete(a);
    return convertNulls(notes);
  }

  @Override
  public boolean replace(String key, Map<String, String> notes) {
    RedisSingleUseObjectAdapter a = suoTrx.getIfPresent(new SingleUseObjectKey(key));
    if (a == null) return false;
    a.replaceNotes(notes);
    return true;
  }

  @Override
  public boolean putIfAbsent(String key, long lifespanSeconds) {
    SingleUseObjectKey k = new SingleUseObjectKey(key);
    RedisSingleUseObjectAdapter a = suoTrx.getIfPresent(k);
    if (a != null) {
      return false;
    } else {
      a = suoTrx.get(k);
      a.setExpiration(Time.currentTimeMillis() + (lifespanSeconds * 1000L));
      return true;
    }
  }

  @Override
  public boolean contains(String key) {
    RedisSingleUseObjectAdapter a = suoTrx.getIfPresent(new SingleUseObjectKey(key));
    return (a != null);
  }

  /** Replace sentinel values in the map with actual nulls. */
  public static Map<String, String> convertNulls(Map<String, String> input) {
    if (input == null || input.isEmpty()) return input;
    Map<String, String> output = Maps.newHashMap();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      if (entry.getValue() == null) {
        output.put(entry.getKey(), null); // null is allowed here
      } else if (MapEntity.NULL_SENTINEL.equals(entry.getValue())) {
        output.put(entry.getKey(), null);
      } else {
        output.put(entry.getKey(), entry.getValue());
      }
    }
    return output;
  }

  @Override
  public void close() {}
}
