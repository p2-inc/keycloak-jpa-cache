package io.phasetwo.keycloak.redis.config;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.redis.RedisStoreConfig;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Self-configures a deployment that opted into the Redis datastore, so operators no longer have to
 * list the supporting options by hand. Contributed values are defaults: anything set explicitly
 * (CLI args, {@code KC_*} env, {@code keycloak.conf}) wins.
 *
 * <p>It contributes two things:
 *
 * <ul>
 *   <li>The datastore selection itself, when only the deprecated {@code
 *       KC_COMMUNITY_REDIS_CACHE_ENABLED} flag was given. This is what keeps the old flag working
 *       as an alias for {@code --spi-datastore--provider=redis}.
 *   <li>The authorization cache disable. Keycloak's authorization cache reaches for the Infinispan
 *       store factory directly, which this extension does not provide.
 * </ul>
 *
 * <p>Note that the realm cache is deliberately left alone: it is a node-local cache whose
 * invalidations travel over this extension's Redis {@code PUBSUB} {@code ClusterProvider}, so it
 * stays coherent.
 *
 * <p>Discovered through the standard SmallRye {@link ConfigSourceFactory} ServiceLoader hook (the
 * same discovery Keycloak uses for its own config sources), at both {@code kc.sh build} and a
 * re-augmenting {@code start}. Using a factory rather than a plain {@link ConfigSource} lets us
 * read the already-resolved configuration through the {@link ConfigSourceContext}: we contribute
 * only when Redis was actually asked for, and only for keys the deployment left unset, so
 * correctness never depends on out-ranking another source.
 */
@AutoService(ConfigSourceFactory.class)
public class RedisConfigDefaultsSourceFactory implements ConfigSourceFactory {

  /**
   * Spellings the datastore selection can arrive under. Keycloak accepts both the {@code --} and
   * the older single-dash separator, and resolves {@code KC_SPI_DATASTORE_PROVIDER} to the latter,
   * so the probe has to cover both rather than assume one.
   */
  static final List<String> DATASTORE_PROVIDER_KEYS =
      List.of("kc.spi-datastore--provider", "kc.spi-datastore-provider");

  static final String DATASTORE_PROVIDER = DATASTORE_PROVIDER_KEYS.get(0);
  static final String AUTHORIZATION_CACHE_ENABLED = "kc.spi-authorization-cache--default--enabled";

  /**
   * Below every Keycloak config source (persisted build values = 200, {@code keycloak.conf} = 299,
   * {@code KC_*} env = 500, CLI args = 600), so {@link #getConfigSources} observes whatever a
   * deployment set and fills in only the gaps.
   */
  static final int PRIORITY = 100;

  @Override
  public OptionalInt getPriority() {
    return OptionalInt.of(PRIORITY);
  }

  @Override
  public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
    String datastore = null;
    for (String key : DATASTORE_PROVIDER_KEYS) {
      String candidate = value(context, key);
      if (candidate != null) {
        datastore = candidate;
        break;
      }
    }

    boolean selected = RedisStoreConfig.DATASTORE_PROVIDER_ID.equals(datastore);
    if (!selected && !(datastore == null && RedisStoreConfig.isLegacyActivation())) {
      return List.of();
    }

    Map<String, String> defaults = new HashMap<>();
    if (!selected) {
      defaults.put(DATASTORE_PROVIDER, RedisStoreConfig.DATASTORE_PROVIDER_ID);
    }
    if (value(context, AUTHORIZATION_CACHE_ENABLED) == null) {
      defaults.put(AUTHORIZATION_CACHE_ENABLED, "false");
    }
    return defaults.isEmpty() ? List.of() : List.of(new MapConfigSource(defaults));
  }

  private static String value(ConfigSourceContext context, String key) {
    ConfigValue configValue = context.getValue(key);
    return configValue == null ? null : configValue.getValue();
  }

  private static final class MapConfigSource implements ConfigSource {

    private final Map<String, String> properties;

    private MapConfigSource(Map<String, String> properties) {
      this.properties = properties;
    }

    @Override
    public Map<String, String> getProperties() {
      return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
      return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
      return properties.get(propertyName);
    }

    @Override
    public String getName() {
      return "redis-defaults";
    }

    @Override
    public int getOrdinal() {
      return PRIORITY;
    }
  }
}
