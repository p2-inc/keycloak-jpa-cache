package io.phasetwo.keycloak.redis.revokedToken;

import java.util.Objects;
import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Keeps revoked tokens in Redis rather than the database.
 *
 * <p>Keycloak 26.7 split revoked tokens out of {@link SingleUseObjectProvider} into their own SPI,
 * and under the {@code stateless} feature the only built-in implementation is the JPA one.
 * Delegating back to the single-use object store — exactly as Keycloak's own {@code
 * InfinispanRevokedTokenProvider} does — keeps revocations in Redis and preserves the key layout
 * used before the split.
 */
public final class RedisRevokedTokenProvider implements RevokedTokenProvider {

  private final SingleUseObjectProvider delegate;

  public RedisRevokedTokenProvider(SingleUseObjectProvider delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public boolean put(String id, long lifespanSeconds) {
    return delegate.putIfAbsent(id + SingleUseObjectProvider.REVOKED_KEY, lifespanSeconds);
  }

  @Override
  public boolean contains(String id) {
    return delegate.contains(id + SingleUseObjectProvider.REVOKED_KEY);
  }

  @Override
  public void close() {}
}
