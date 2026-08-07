package io.phasetwo.keycloak.redis;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;

/**
 * Resolves whether this extension is active for the running deployment.
 *
 * <p>Since Keycloak 26.7 the extension opts in through Keycloak's own datastore selection, {@code
 * --spi-datastore--provider=redis}. Selecting the datastore is what makes every Redis provider
 * factory {@link io.phasetwo.keycloak.common.IsSupported supported}; leaving it unselected keeps
 * the jar on the classpath but dormant, so it never shadows a deployment that did not ask for it.
 *
 * <p>The former {@code KC_COMMUNITY_REDIS_CACHE_ENABLED} env var (or {@code
 * kc.community.redis.cache.enabled} system property) is still honoured as a deprecated alias. In
 * the Quarkus distribution {@link
 * io.phasetwo.keycloak.redis.config.RedisConfigDefaultsSourceFactory} translates it into the
 * datastore selection before any provider is loaded; the direct check here covers embeddings that
 * do not run SmallRye config.
 *
 * <p>Resolved on every call rather than memoized: it is only consulted while provider factories are
 * being loaded, and embedded test runs reuse the JVM across servers with different options.
 */
@JBossLog
public final class RedisStoreConfig {

  public static final String DATASTORE_PROVIDER_ID = "redis";

  public static final String ENV_REDIS_CACHE_ENABLED = "KC_COMMUNITY_REDIS_CACHE_ENABLED";
  public static final String PROP_REDIS_CACHE_ENABLED = "kc.community.redis.cache.enabled";

  private static final AtomicBoolean DEPRECATION_LOGGED = new AtomicBoolean();

  private RedisStoreConfig() {}

  /** True when this deployment selected the Redis datastore. */
  public static boolean isEnabled() {
    boolean legacy = isLegacyActivation();
    if (legacy && DEPRECATION_LOGGED.compareAndSet(false, true)) {
      log.warnf(
          "%s is deprecated. Select the Redis datastore with --spi-datastore--provider=%s instead.",
          ENV_REDIS_CACHE_ENABLED, DATASTORE_PROVIDER_ID);
    }
    return legacy || DATASTORE_PROVIDER_ID.equals(Config.getProvider("datastore"));
  }

  /** True when activation came from the deprecated env var / system property. */
  public static boolean isLegacyActivation() {
    return Boolean.parseBoolean(System.getenv(ENV_REDIS_CACHE_ENABLED))
        || Boolean.parseBoolean(System.getProperty(PROP_REDIS_CACHE_ENABLED));
  }
}
