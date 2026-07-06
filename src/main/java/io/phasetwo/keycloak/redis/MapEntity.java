package io.phasetwo.keycloak.redis;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public abstract class MapEntity<K extends Key> {

  public static final String NULL_SENTINEL = "<null:ohRL7DzMSx>";

  private final K key;
  private final Map<String, String> data;
  private final Set<String> dirtyFields = Sets.newHashSet();
  private final Set<String> deletedFields = Sets.newHashSet();
  private boolean markedForDelete = false;
  private boolean isNew = true;

  protected MapEntity(K key, Map<String, String> existingData) {
    this.key = key;
    if (existingData != null && !existingData.isEmpty()) {
      isNew = false;
      this.data = Maps.newHashMap(existingData);
    } else {
      this.data = Maps.newHashMap();
    }
  }

  protected void removeField(String key) {
    log.tracef("removeField %s", key);
    data.remove(key);
    deletedFields.add(key);
  }

  protected void setField(String key, Object value) {
    log.tracef("setField %s %s", key, value);
    String strVal = value == null ? NULL_SENTINEL : String.valueOf(value);
    String current = data.get(key);
    if (!Objects.equals(current, strVal)) {
      data.put(key, strVal);
      dirtyFields.add(key);
      deletedFields.remove(key);
    } else {
      log.tracef("field isn't different. skipping. %s %s", key, value);
    }
  }

  protected boolean getBool(String key, boolean defaultValue) {
    String val = getString(key);
    return val != null ? Boolean.parseBoolean(val) : defaultValue;
  }

  protected int getInt(String key, int defaultValue) {
    String val = getString(key);
    return val != null ? Integer.parseInt(val) : defaultValue;
  }

  protected long getLong(String key, long defaultValue) {
    String val = getString(key);
    return val != null ? Long.parseLong(val) : defaultValue;
  }

  protected String getString(String key) {
    String val = data.get(key);
    return NULL_SENTINEL.equals(val) ? null : val;
  }

  protected boolean isNull(String key) {
    return getString(key) == null;
  }

  public Map<String, String> getMap(String prefix) {
    final String prefixWithColon = prefix + ":";

    return new AbstractMap<>() {
      @Override
      public Set<Entry<String, String>> entrySet() {
        Set<Entry<String, String>> result = Sets.newHashSet();
        for (Map.Entry<String, String> entry : data.entrySet()) {
          if (entry.getKey().startsWith(prefixWithColon)) {
            String subKey = entry.getKey().substring(prefixWithColon.length());
            result.add(new AbstractMap.SimpleEntry<>(subKey, entry.getValue()));
          }
        }
        return result;
      }

      @Override
      public String get(Object key) {
        if (!(key instanceof String)) return null;
        return getString(prefixWithColon + key);
      }

      @Override
      public String put(String key, String value) {
        String fullKey = prefixWithColon + key;
        String oldValue = getString(fullKey);
        setField(fullKey, value);
        return oldValue;
      }

      @Override
      public String remove(Object key) {
        if (!(key instanceof String)) return null;
        String fullKey = prefixWithColon + key;
        String oldValue = getString(fullKey);
        removeField(fullKey);
        return oldValue;
      }

      @Override
      public boolean containsKey(Object key) {
        if (!(key instanceof String)) return false;
        return data.containsKey(prefixWithColon + key);
      }

      @Override
      public void clear() {
        Set<String> keysToRemove = Sets.newHashSet();
        for (String k : data.keySet()) {
          if (k.startsWith(prefixWithColon)) {
            keysToRemove.add(k);
          }
        }
        for (String k : keysToRemove) {
          removeField(k);
        }
      }

      @Override
      public int size() {
        int count = 0;
        for (String k : data.keySet()) {
          if (k.startsWith(prefixWithColon)) {
            count++;
          }
        }
        return count;
      }
    };
  }

  public Set<String> getSet(String prefix) {
    final String prefixWithColon = prefix + ":";
    final String valueSentinel = String.format("<set:%s>", prefix);

    return new AbstractSet<String>() {

      private Set<String> computeSet() {
        Set<String> result = Sets.newHashSet();
        for (String key : data.keySet()) {
          if (key.startsWith(prefixWithColon)) {
            result.add(key.substring(prefixWithColon.length()));
          }
        }
        return result;
      }

      private String toKey(String item) {
        return prefixWithColon + item;
      }

      @Override
      public boolean add(String value) {
        String fullKey = toKey(value);
        if (data.containsKey(fullKey)) return false;
        setField(fullKey, valueSentinel); // Use empty string as value
        return true;
      }

      @Override
      public boolean remove(Object o) {
        if (!(o instanceof String)) return false;
        String fullKey = toKey((String) o);
        if (!data.containsKey(fullKey)) return false;
        removeField(fullKey);
        return true;
      }

      @Override
      public boolean contains(Object o) {
        if (!(o instanceof String)) return false;
        return data.containsKey(toKey((String) o));
      }

      @Override
      public Iterator<String> iterator() {
        Iterator<String> base = computeSet().iterator();
        return new Iterator<String>() {
          private String current = null;

          @Override
          public boolean hasNext() {
            return base.hasNext();
          }

          @Override
          public String next() {
            current = base.next();
            return current;
          }

          @Override
          public void remove() {
            if (current != null) {
              removeField(toKey(current));
            }
          }
        };
      }

      @Override
      public int size() {
        return computeSet().size();
      }

      @Override
      public void clear() {
        Set<String> keysToRemove = computeSet();
        for (String val : keysToRemove) {
          removeField(toKey(val));
        }
      }
    };
  }

  public final Long getVersion() {
    if (isNull("version")) setVersion(0L); // make sure it's set in all cases;
    return getLong("version", 0L);
  }

  public final void setVersion(Long version) {
    setField("version", version);
  }

  // make this <Key,String>?
  public abstract Map<String, String> getSecondaryIndexes();

  protected static void siPut(
      ImmutableMap.Builder<String, String> b, String format, String param, String value) {
    if (!Strings.isNullOrEmpty(param) && value != null) {
      b.put(String.format(format, param), value);
    }
  }

  /**
   * Key-derived field setter: keys parsed from index members only carry the
   * parts their format encodes (DEFAULT has no realm, SERVERLESS per-tab keys
   * no clientId) — an empty part must never clobber a field loaded from Redis.
   */
  protected void setFieldFromKey(String field, String value) {
    if (!Strings.isNullOrEmpty(value)) {
      setField(field, value);
    }
  }

  /** Prebuilt-key variant of {@link #siPut}: skips when {@code guardParam} is empty. */
  protected static void siPutIf(
      ImmutableMap.Builder<String, String> b, String guardParam, String indexKey, String value) {
    if (!Strings.isNullOrEmpty(guardParam) && value != null) {
      b.put(indexKey, value);
    }
  }

  public K getKey() {
    return this.key;
  }

  public boolean isDirty() {
    return !dirtyFields.isEmpty() || !deletedFields.isEmpty();
  }

  public boolean isMarkedForDelete() {
    return markedForDelete;
  }

  /**
   * Replays this entity's pending field-level mutations onto a freshly loaded instance.
   *
   * <p>This intentionally skips the synthetic {@code version} field because the target instance
   * must preserve the version that was just read from Redis for the next CAS attempt.
   */
  public void replayPendingChangesOnto(MapEntity<K> target) {
    for (String key : dirtyFields) {
      if ("version".equals(key)) {
        continue;
      }
      target.setField(key, data.get(key));
    }
    for (String key : deletedFields) {
      if ("version".equals(key)) {
        continue;
      }
      target.removeField(key);
    }
    if (markedForDelete) {
      target.markForDelete();
    }
  }

  public Map<String, String> getDirtyFields() {
    Map<String, String> dirty = Maps.newHashMap();
    for (String k : dirtyFields) {
      dirty.put(k, data.get(k));
    }
    return dirty;
  }

  public Set<String> getDeletedFields() {
    return deletedFields;
  }

  public void markForDelete() {
    markedForDelete = true;
  }

  @Override
  public String toString() {
    return data.toString();
  }

  protected Map<String, String> getFieldSnapshot() {
    return Map.copyOf(data);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MapEntity<?> that = (MapEntity<?>) o;
    return Objects.equals(key, that.key)
        && Objects.equals(getFieldSnapshot(), that.getFieldSnapshot());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, getFieldSnapshot());
  }
}
