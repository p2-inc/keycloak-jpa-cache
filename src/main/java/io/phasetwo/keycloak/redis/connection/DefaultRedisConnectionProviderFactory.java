package io.phasetwo.keycloak.redis.connection;

import static io.phasetwo.keycloak.redis.RedisMetrics.*;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import io.phasetwo.keycloak.redis.RedisHashCas;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.RedisSentinelClient;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.util.Pool;

@JBossLog
@AutoService(RedisConnectionProviderFactory.class)
public class DefaultRedisConnectionProviderFactory
    implements RedisConnectionProviderFactory<RedisConnectionProvider>,
        EnvironmentDependentProviderFactory,
        IsSupported {
  public static final String PROVIDER_ID = "default";

  private static UnifiedJedis jedisClient;
  private static RedisMode mode = RedisMode.STANDALONE;
  private static Set<HostAndPort> nodes = Set.of();
  private static String masterName;
  private static JedisClientConfig clientConfig;
  private static GenericObjectPoolConfig<Connection> poolConfig;

  @Override
  public RedisConnectionProvider create(KeycloakSession session) {
    return new RedisConnectionProvider() {
      @Override
      public UnifiedJedis getJedis() {
        return jedisClient;
      }

      @Override
      public UnifiedJedis createClient() {
        return buildClient(mode, nodes, masterName, clientConfig, poolConfig);
      }

      @Override
      public RedisMode getRedisMode() {
        return mode;
      }

      @Override
      public void close() {
        // Shared client lifecycle is managed by the factory.
      }
    };
  }

  @Override
  public void init(Config.Scope scope) {
    String modeValue = scope.get("mode", "TopologyInfo.Node");
    mode = parseMode(modeValue);
    log.tracef("mode: %s", mode);

    String nodesValue = scope.get("nodes", "redis:6379");
    nodes = parseNodes(nodesValue);
    log.tracef("nodes: %s", nodes);

    masterName = scope.get("masterName");
    if (mode == RedisMode.SENTINEL && (masterName == null || masterName.isBlank())) {
      throw new IllegalStateException(
          "Sentinel mode requires 'masterName' to be configured. "
              + "Set KC_SPI_REDIS_CONNECTION_DEFAULT_MASTER_NAME.");
    }
    log.tracef("masterName: %s", masterName);

    boolean useSsl = scope.getBoolean("ssl", false);
    log.tracef("useSsl: %b", useSsl);

    String username = scope.get("username");
    String password = scope.get("password");

    int redisTimeout = parseTimeoutMillis(scope.get("timeout"));
    poolConfig = buildPoolConfig();
    clientConfig = buildClientConfig(useSsl, username, password, redisTimeout);

    jedisClient = buildClient(mode, nodes, masterName, clientConfig, poolConfig);

    RedisHashCas.initialize(jedisClient);

    addClientMetrics(jedisClient);
  }

  private static GenericObjectPoolConfig<Connection> buildPoolConfig() {
    final GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
    poolConfig.setMaxTotal(100);
    poolConfig.setMaxIdle(50);
    poolConfig.setMinIdle(10);
    poolConfig.setMaxWaitMillis(3000);
    poolConfig.setBlockWhenExhausted(true);
    // may be the issue with the subscriber
    // poolConfig.setTestOnBorrow(true);
    poolConfig.setTestOnBorrow(false);
    poolConfig.setTestWhileIdle(true);
    poolConfig.setTimeBetweenEvictionRunsMillis(60000);
    poolConfig.setMinEvictableIdleTimeMillis(300000);
    poolConfig.setNumTestsPerEvictionRun(-1);
    return poolConfig;
  }

  public static UnifiedJedis getJedis() {
    if (jedisClient == null) {
      throw new IllegalStateException("Redis client not initialized. Call init() first.");
    }
    return jedisClient;
  }

  private static void addClientMetrics(UnifiedJedis client) {
    if (client instanceof RedisClient) {
      Pool<?> pool = ((RedisClient) client).getPool();
      addJedisPoolMetrics(pool);
      return;
    }
    if (client instanceof RedisSentinelClient) {
      log.debug("Pool-level metrics are not available for RedisSentinelClient");
      return;
    }
    if (client instanceof RedisClusterClient) {
      RedisClusterClient clusterClient = (RedisClusterClient) client;
      clusterClient.getClusterNodes().forEach((node, pool) -> addJedisPoolMetrics(pool, node));
    }
  }

  private static RedisMode parseMode(String modeValue) {
    if (modeValue == null || modeValue.isBlank()) {
      return RedisMode.STANDALONE;
    }
    switch (modeValue.trim().toLowerCase(Locale.ROOT)) {
      case "standalone":
        return RedisMode.STANDALONE;
      case "sentinel":
        return RedisMode.SENTINEL;
      case "cluster":
        return RedisMode.CLUSTER;
      default:
        log.warnf("Unknown redis mode '%s', defaulting to standalone", modeValue);
        return RedisMode.STANDALONE;
    }
  }

  private static Set<HostAndPort> parseNodes(String nodesValue) {
    Set<HostAndPort> parsed = new LinkedHashSet<>();
    if (nodesValue != null && !nodesValue.isBlank()) {
      String[] entries = nodesValue.split(",");
      for (String entry : entries) {
        String trimmed = entry.trim();
        if (!trimmed.isEmpty()) {
          parsed.add(HostAndPort.from(trimmed));
        }
      }
    }
    if (parsed.isEmpty()) {
      throw new IllegalStateException("No redis nodes configured. Set 'nodes'.");
    }
    return parsed;
  }

  private static int parseTimeoutMillis(String timeoutValue) {
    if (timeoutValue == null || timeoutValue.isBlank()) {
      return 2000;
    }
    String trimmed = timeoutValue.trim().toLowerCase(Locale.ROOT);
    try {
      if (trimmed.endsWith("ms")) {
        return Integer.parseInt(trimmed.substring(0, trimmed.length() - 2));
      }
      if (trimmed.endsWith("s")) {
        long seconds = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        return (int) Math.min(Integer.MAX_VALUE, seconds * 1000L);
      }
      if (trimmed.endsWith("m")) {
        long minutes = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        return (int) Math.min(Integer.MAX_VALUE, minutes * 60_000L);
      }
      if (trimmed.endsWith("h")) {
        long hours = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        return (int) Math.min(Integer.MAX_VALUE, hours * 3_600_000L);
      }
      return Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      log.warnf("Invalid timeout value '%s', using default 2000ms", timeoutValue);
      return 2000;
    }
  }

  private static JedisClientConfig buildClientConfig(
      boolean useSsl, String username, String password, int timeoutMillis) {
    DefaultJedisClientConfig.Builder builder =
        DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(timeoutMillis)
            .socketTimeoutMillis(timeoutMillis);
    if (useSsl) {
      builder.ssl(useSsl);
    }
    if (username != null && !username.isBlank()) {
      builder.user(username);
    }
    if (password != null && !password.isBlank()) {
      builder.password(password);
    }
    return builder.build();
  }

  private static UnifiedJedis buildClient(
      RedisMode mode,
      Set<HostAndPort> nodes,
      String masterName,
      JedisClientConfig clientConfig,
      GenericObjectPoolConfig<Connection> poolConfig) {
    if (mode == RedisMode.CLUSTER) {
      return RedisClusterClient.builder()
          .nodes(nodes)
          .clientConfig(clientConfig)
          // .poolConfig(poolConfig)
          .build();
    }

    if (mode == RedisMode.SENTINEL) {
      return RedisSentinelClient.builder()
          .masterName(masterName)
          .sentinels(nodes)
          // .sentinelClientConfig(clientConfig)
          .clientConfig(clientConfig)
          // .poolConfig(poolConfig)
          .build();
    }

    HostAndPort node = nodes.iterator().next();
    if (nodes.size() > 1) {
      log.warnf("Multiple nodes configured for %s; using %s", mode, node);
    }
    return RedisClient.builder()
        .hostAndPort(node)
        .clientConfig(clientConfig)
        //  .poolConfig(poolConfig)
        .build();
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public void close() {
    if (jedisClient != null) {
      jedisClient.close();
      jedisClient = null;
    }
  }
}
