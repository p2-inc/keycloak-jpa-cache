/*
 * Copyright 2023 IT-Systemhaus der Bundesagentur fuer Arbeit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.phasetwo.keycloak.redis.userSession.expiration;

// package de.arbeitsagentur.opdt.keycloak.cassandra.userSession.expiration;

import io.phasetwo.keycloak.common.TimeAdapter;
import io.phasetwo.keycloak.redis.userSession.RedisAuthenticatedClientSessionAdapter;
import io.phasetwo.keycloak.redis.userSession.RedisUserSessionAdapter;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.protocol.oidc.OIDCConfigAttributes;

public class RedisSessionExpiration {
  /**
   * @param client the client this session belongs to, or {@code null} when it has been deleted. The
   *     client only ever supplies per-client <em>overrides</em> of the realm defaults, so a missing
   *     client means "no overrides" — not "no expiration". Skipping the write instead would strand
   *     the hash without a Redis TTL (issue #81).
   */
  public static void setClientSessionExpiration(
      RedisAuthenticatedClientSessionAdapter entity,
      SessionExpirationData expirationData,
      ClientModel client,
      Boolean offline) {
    long timestampMillis = entity.getTimestamp() * 1000L;
    // The remember-me lifespans belong to remember-me logins only. setUserSessionExpiration()
    // checks this; without the same check here every client session on a realm with remember-me
    // timeouts configured outlives its parent user session and becomes an orphan (issue #81).
    boolean rememberMe =
        Boolean.parseBoolean(
            entity.getNote(AuthenticatedClientSessionModel.USER_SESSION_REMEMBER_ME_NOTE));
    if (Boolean.TRUE.equals(offline)) {
      long sessionExpires =
          timestampMillis
              + TimeAdapter.fromSecondsToMilliseconds(
                  expirationData.getOfflineSessionIdleTimeout());
      if (expirationData.isOfflineSessionMaxLifespanEnabled()) {
        sessionExpires =
            timestampMillis
                + TimeAdapter.fromSecondsToMilliseconds(
                    expirationData.getOfflineSessionMaxLifespan());

        long clientOfflineSessionMaxLifespan;
        String clientOfflineSessionMaxLifespanPerClient =
            clientAttribute(client, OIDCConfigAttributes.CLIENT_OFFLINE_SESSION_MAX_LIFESPAN);
        if (clientOfflineSessionMaxLifespanPerClient != null
            && !clientOfflineSessionMaxLifespanPerClient.trim().isEmpty()) {
          clientOfflineSessionMaxLifespan =
              TimeAdapter.fromSecondsToMilliseconds(
                  Long.parseLong(clientOfflineSessionMaxLifespanPerClient));
        } else {
          clientOfflineSessionMaxLifespan =
              TimeAdapter.fromSecondsToMilliseconds(
                  expirationData.getClientOfflineSessionMaxLifespan());
        }

        if (clientOfflineSessionMaxLifespan > 0) {
          long clientOfflineSessionMaxExpiration =
              timestampMillis + clientOfflineSessionMaxLifespan;
          sessionExpires = Math.min(sessionExpires, clientOfflineSessionMaxExpiration);
        }
      }

      long expiration =
          timestampMillis
              + TimeAdapter.fromSecondsToMilliseconds(
                  expirationData.getOfflineSessionIdleTimeout());

      long clientOfflineSessionIdleTimeout;
      String clientOfflineSessionIdleTimeoutPerClient =
          clientAttribute(client, OIDCConfigAttributes.CLIENT_OFFLINE_SESSION_IDLE_TIMEOUT);
      if (clientOfflineSessionIdleTimeoutPerClient != null
          && !clientOfflineSessionIdleTimeoutPerClient.trim().isEmpty()) {
        clientOfflineSessionIdleTimeout =
            TimeAdapter.fromSecondsToMilliseconds(
                Long.parseLong(clientOfflineSessionIdleTimeoutPerClient));
      } else {
        clientOfflineSessionIdleTimeout =
            TimeAdapter.fromSecondsToMilliseconds(
                expirationData.getClientOfflineSessionIdleTimeout());
      }

      if (clientOfflineSessionIdleTimeout > 0) {
        long clientOfflineSessionIdleExpiration = timestampMillis + clientOfflineSessionIdleTimeout;
        expiration = Math.min(expiration, clientOfflineSessionIdleExpiration);
      }

      entity.setExpiration(Math.min(expiration, sessionExpires));
    } else {
      long sessionExpires =
          timestampMillis
              + (rememberMe && expirationData.getSsoSessionMaxLifespanRememberMe() > 0
                  ? TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionMaxLifespanRememberMe())
                  : TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionMaxLifespan()));

      long clientSessionMaxLifespan;
      String clientSessionMaxLifespanPerClient =
          clientAttribute(client, OIDCConfigAttributes.CLIENT_SESSION_MAX_LIFESPAN);
      if (clientSessionMaxLifespanPerClient != null
          && !clientSessionMaxLifespanPerClient.trim().isEmpty()) {
        clientSessionMaxLifespan =
            TimeAdapter.fromSecondsToMilliseconds(
                Long.parseLong(clientSessionMaxLifespanPerClient));
      } else {
        clientSessionMaxLifespan =
            TimeAdapter.fromSecondsToMilliseconds(expirationData.getClientSessionMaxLifespan());
      }

      if (clientSessionMaxLifespan > 0) {
        long clientSessionMaxExpiration = timestampMillis + clientSessionMaxLifespan;
        sessionExpires = Math.min(sessionExpires, clientSessionMaxExpiration);
      }

      long expiration =
          timestampMillis
              + (rememberMe && expirationData.getSsoSessionIdleTimeoutRememberMe() > 0
                  ? TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionIdleTimeoutRememberMe())
                  : TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionIdleTimeout()));

      long clientSessionIdleTimeout;
      String clientSessionIdleTimeoutPerClient =
          clientAttribute(client, OIDCConfigAttributes.CLIENT_SESSION_IDLE_TIMEOUT);
      if (clientSessionIdleTimeoutPerClient != null
          && !clientSessionIdleTimeoutPerClient.trim().isEmpty()) {
        clientSessionIdleTimeout =
            TimeAdapter.fromSecondsToMilliseconds(
                Long.parseLong(clientSessionIdleTimeoutPerClient));
      } else {
        clientSessionIdleTimeout =
            TimeAdapter.fromSecondsToMilliseconds(expirationData.getClientSessionIdleTimeout());
      }

      if (clientSessionIdleTimeout > 0) {
        long clientSessionIdleExpiration = timestampMillis + clientSessionIdleTimeout;
        expiration = Math.min(expiration, clientSessionIdleExpiration);
      }

      entity.setExpiration(Math.min(expiration, sessionExpires));
    }
  }

  private static String clientAttribute(ClientModel client, String name) {
    return client == null ? null : client.getAttribute(name);
  }

  public static void setUserSessionExpiration(
      RedisUserSessionAdapter entity, SessionExpirationData expirationData) {
    long timestampMillis = entity.getTimestamp() * 1000L;
    long lastSessionRefreshMillis = entity.getLastSessionRefresh() * 1000L;
    if (Boolean.TRUE.equals(entity.isOffline())) {
      long sessionExpires =
          lastSessionRefreshMillis
              + TimeAdapter.fromSecondsToMilliseconds(
                  expirationData.getOfflineSessionIdleTimeout());
      if (expirationData.isOfflineSessionMaxLifespanEnabled()) {
        sessionExpires =
            timestampMillis
                + TimeAdapter.fromSecondsToMilliseconds(
                    expirationData.getOfflineSessionMaxLifespan());

        long clientOfflineSessionMaxLifespan =
            TimeAdapter.fromSecondsToMilliseconds(
                expirationData.getClientOfflineSessionMaxLifespan());

        if (clientOfflineSessionMaxLifespan > 0) {
          long clientOfflineSessionMaxExpiration =
              timestampMillis + clientOfflineSessionMaxLifespan;
          sessionExpires = Math.min(sessionExpires, clientOfflineSessionMaxExpiration);
        }
      }

      long expiration =
          lastSessionRefreshMillis
              + TimeAdapter.fromSecondsToMilliseconds(
                  expirationData.getOfflineSessionIdleTimeout());

      long clientOfflineSessionIdleTimeout =
          TimeAdapter.fromSecondsToMilliseconds(
              expirationData.getClientOfflineSessionIdleTimeout());

      if (clientOfflineSessionIdleTimeout > 0) {
        long clientOfflineSessionIdleExpiration =
            Time.currentTimeMillis() + clientOfflineSessionIdleTimeout;
        expiration = Math.min(expiration, clientOfflineSessionIdleExpiration);
      }

      entity.setExpiration(Math.min(expiration, sessionExpires));
    } else {
      long sessionExpires =
          timestampMillis
              + (Boolean.TRUE.equals(entity.isRememberMe())
                      && expirationData.getSsoSessionMaxLifespanRememberMe() > 0
                  ? TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionMaxLifespanRememberMe())
                  : TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionMaxLifespan()));

      long clientSessionMaxLifespan =
          TimeAdapter.fromSecondsToMilliseconds(expirationData.getClientSessionMaxLifespan());

      if (clientSessionMaxLifespan > 0) {
        long clientSessionMaxExpiration = timestampMillis + clientSessionMaxLifespan;
        sessionExpires = Math.min(sessionExpires, clientSessionMaxExpiration);
      }

      long expiration =
          lastSessionRefreshMillis
              + (Boolean.TRUE.equals(entity.isRememberMe())
                      && expirationData.getSsoSessionIdleTimeoutRememberMe() > 0
                  ? TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionIdleTimeoutRememberMe())
                  : TimeAdapter.fromSecondsToMilliseconds(
                      expirationData.getSsoSessionIdleTimeout()));

      long clientSessionIdleTimeout =
          TimeAdapter.fromSecondsToMilliseconds(expirationData.getClientSessionIdleTimeout());

      if (clientSessionIdleTimeout > 0) {
        long clientSessionIdleExpiration = lastSessionRefreshMillis + clientSessionIdleTimeout;
        expiration = Math.min(expiration, clientSessionIdleExpiration);
      }

      entity.setExpiration(Math.min(expiration, sessionExpires));
    }
  }
}
