package io.phasetwo.keycloak.jpacache.loginFailure;

import io.phasetwo.keycloak.jpacache.loginFailure.persistence.LoginFailureRepository;
import io.phasetwo.keycloak.jpacache.loginFailure.persistence.entities.LoginFailure;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import io.phasetwo.keycloak.mapstorage.common.TimeAdapter;

@EqualsAndHashCode(of = "entity")
@RequiredArgsConstructor
public class JpaCacheLoginFailureAdapter implements UserLoginFailureModel {
  private final RealmModel realm;
  private final LoginFailure entity;

  @Override
  public String getId() {
    return entity.getId();
  }

  @Override
  public String getUserId() {
    return entity.getUserId();
  }

  @Override
  public int getFailedLoginNotBefore() {
    Long failedLoginNotBefore = entity.getFailedLoginNotBefore();
    return failedLoginNotBefore == null ? 0 : TimeAdapter.fromLongWithTimeInSecondsToIntegerWithTimeInSeconds(failedLoginNotBefore);
  }

  @Override
  public void setFailedLoginNotBefore(int notBefore) {
    entity.setFailedLoginNotBefore(TimeAdapter.fromIntegerWithTimeInSecondsToLongWithTimeAsInSeconds(notBefore));
  }

  @Override
  public int getNumFailures() {
    Integer numFailures = entity.getNumFailures();
    return numFailures == null ? 0 : numFailures;
  }

  @Override
  public void incrementFailures() {
    entity.setNumFailures(getNumFailures() + 1);
  }

  @Override
  public void clearFailures() {
    entity.setFailedLoginNotBefore(null);
    entity.setNumFailures(null);
    entity.setLastFailure(null);
    entity.setLastIpFailure(null);
  }

  @Override
  public long getLastFailure() {
    Long lastFailure = entity.getLastFailure();
    return lastFailure == null ? 0l : lastFailure;
  }

  @Override
  public void setLastFailure(long lastFailure) {
    entity.setLastFailure(lastFailure);
  }

  @Override
  public String getLastIPFailure() {
    return entity.getLastIpFailure();
  }

  @Override
  public void setLastIPFailure(String ip) {
    entity.setLastIpFailure(ip);
  }
}
