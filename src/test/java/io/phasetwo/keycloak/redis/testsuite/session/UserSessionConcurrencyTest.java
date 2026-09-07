/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.phasetwo.keycloak.redis.testsuite.session;

import static io.phasetwo.keycloak.redis.testsuite.LockObjectsForModification.lockUserSessionsForModification;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import io.phasetwo.keycloak.redis.testsuite.KeycloakModelTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Test;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;

public class UserSessionConcurrencyTest extends KeycloakModelTest {

  private String realmId;
  private static final int CLIENTS_COUNT = 10;

  private static final ThreadLocal<Boolean> wasWriting = ThreadLocal.withInitial(() -> false);

  @Override
  public void createEnvironment(KeycloakSession s) {
    RealmModel realm = createRealm(s, "test");
    s.getContext().setRealm(realm);
    realm.setDefaultRole(
        s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
    realm.setSsoSessionIdleTimeout(1800);
    realm.setSsoSessionMaxLifespan(36000);
    realm.setClientSessionIdleTimeout(500);
    this.realmId = realm.getId();

    s.users().addUser(realm, "user1").setEmail("user1@localhost");
    s.users().addUser(realm, "user2").setEmail("user2@localhost");

    for (int i = 0; i < CLIENTS_COUNT; i++) {
      s.clients().addClient(realm, "client" + i);
    }
  }

  @Override
  protected boolean isUseSameKeycloakSessionFactoryForAllThreads() {
    return true;
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testConcurrentNotesChange() throws InterruptedException {
    // Create user session
    String uId =
        withRealm(
                this.realmId,
                (session, realm) ->
                    session
                        .sessions()
                        .createUserSession(
                            realm,
                            session.users().getUserByUsername(realm, "user1"),
                            "user1",
                            "127.0.0.1",
                            "form",
                            true,
                            null,
                            null))
            .getId();

    // Create/Update client session's notes concurrently
    CountDownLatch cdl = new CountDownLatch(200 * CLIENTS_COUNT);
    IntStream.range(0, 200 * CLIENTS_COUNT)
        .parallel()
        .forEach(
            i ->
                inComittedTransaction(
                    i,
                    (session, n) -> {
                      try {
                        RealmModel realm = session.realms().getRealm(realmId);
                        ClientModel client =
                            realm.getClientByClientId("client" + (n % CLIENTS_COUNT));

                        UserSessionModel uSession =
                            lockUserSessionsForModification(
                                session, () -> session.sessions().getUserSession(realm, uId));
                        AuthenticatedClientSessionModel cSession =
                            uSession.getAuthenticatedClientSessionByClient(client.getId());
                        if (cSession == null) {
                          wasWriting.set(true);
                          cSession =
                              session.sessions().createClientSession(realm, client, uSession);
                        }

                        cSession.setNote(OIDCLoginProtocol.STATE_PARAM, "state-" + n);

                        return null;
                      } finally {
                        cdl.countDown();
                      }
                    }));

    cdl.await(10, TimeUnit.SECONDS);
    withRealm(
        this.realmId,
        (session, realm) -> {
          UserSessionModel uSession = session.sessions().getUserSession(realm, uId);
          assertThat(uSession.getAuthenticatedClientSessions(), aMapWithSize(CLIENTS_COUNT));

          for (int i = 0; i < CLIENTS_COUNT; i++) {
            ClientModel client = realm.getClientByClientId("client" + (i % CLIENTS_COUNT));
            AuthenticatedClientSessionModel cSession =
                uSession.getAuthenticatedClientSessionByClient(client.getId());

            assertThat(cSession.getNote(OIDCLoginProtocol.STATE_PARAM), startsWith("state-"));
          }

          return null;
        });

    inComittedTransaction(
        session -> {
          RealmModel realm = session.realms().getRealm(realmId);
          session.getContext().setRealm(realm);
          session.realms().removeRealm(realmId);
        });
  }

  /**
   * Regression for issue #82: many writers targeting the <em>same</em> key concurrently must
   * converge instead of exhausting the Redis CAS retry budget. Each writer opens its own committed
   * transaction, reads the same client session at base version N, then writes a distinct note — so
   * their CAS writes collide. With a fixed budget of 4 attempts and no backoff, the tail writer
   * throws {@link IllegalStateException} ("Redis CAS failed ... after 4 attempts"). A higher,
   * backoff-tolerant retry budget lets all writers converge.
   */
  @Test
  public void testConcurrentSameKeyCasWritesConverge() throws Exception {
    int writers = 12;

    String userSessionId =
        withRealm(
                this.realmId,
                (session, realm) -> {
                  UserSessionModel userSession =
                      session
                          .sessions()
                          .createUserSession(
                              realm,
                              session.users().getUserByUsername(realm, "user1"),
                              "user1",
                              "127.0.0.1",
                              "form",
                              true,
                              null,
                              null);
                  ClientModel client = realm.getClientByClientId("client0");
                  session.sessions().createClientSession(realm, client, userSession);
                  return userSession.getId();
                })
            .toString();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    List<Future<Void>> futures = new ArrayList<>();
    for (int w = 0; w < writers; w++) {
      final int n = w;
      futures.add(
          executor.submit(
              (Callable<Void>)
                  () -> {
                    start.await(10, TimeUnit.SECONDS);
                    withRealm(
                        realmId,
                        (session, realm) -> {
                          ClientModel client = realm.getClientByClientId("client0");
                          UserSessionModel userSession =
                              session.sessions().getUserSession(realm, userSessionId);
                          AuthenticatedClientSessionModel clientSession =
                              userSession.getAuthenticatedClientSessionByClient(client.getId());
                          clientSession.setNote("note-" + n, "n" + n);
                          return null;
                        });
                    return null;
                  }));
    }

    start.countDown();
    try {
      for (Future<Void> f : futures) {
        // A failing writer surfaces here if the underlying transaction throws; the note
        // assertions below are the authoritative check that every write landed.
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    withRealm(
        this.realmId,
        (session, realm) -> {
          UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
          AuthenticatedClientSessionModel clientSession =
              userSession.getAuthenticatedClientSessionByClient(
                  realm.getClientByClientId("client0").getId());
          for (int n = 0; n < writers; n++) {
            assertThat(clientSession.getNote("note-" + n), is("n" + n));
          }
          return null;
        });
  }

  @Test
  public void testStaleConcurrentNoteUpdatesAreRebased() throws Exception {
    String userSessionId =
        withRealm(
                this.realmId,
                (session, realm) -> {
                  UserSessionModel userSession =
                      session
                          .sessions()
                          .createUserSession(
                              realm,
                              session.users().getUserByUsername(realm, "user1"),
                              "user1",
                              "127.0.0.1",
                              "form",
                              true,
                              null,
                              null);
                  ClientModel client = realm.getClientByClientId("client0");
                  AuthenticatedClientSessionModel clientSession =
                      session.sessions().createClientSession(realm, client, userSession);
                  clientSession.setNote("base", "value");
                  return userSession.getId();
                })
            .toString();

    CountDownLatch bothRead = new CountDownLatch(2);
    CountDownLatch releaseWrites = new CountDownLatch(1);

    CompletableFuture<Void> tx1 =
        CompletableFuture.runAsync(
            () ->
                withRealm(
                    realmId,
                    (session, realm) -> {
                      ClientModel client = realm.getClientByClientId("client0");
                      UserSessionModel userSession =
                          session.sessions().getUserSession(realm, userSessionId);
                      AuthenticatedClientSessionModel clientSession =
                          userSession.getAuthenticatedClientSessionByClient(client.getId());
                      bothRead.countDown();
                      try {
                        bothRead.await(10, TimeUnit.SECONDS);
                        releaseWrites.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                      }
                      clientSession.setNote("note-a", "A");
                      return null;
                    }));

    CompletableFuture<Void> tx2 =
        CompletableFuture.runAsync(
            () ->
                withRealm(
                    realmId,
                    (session, realm) -> {
                      ClientModel client = realm.getClientByClientId("client0");
                      UserSessionModel userSession =
                          session.sessions().getUserSession(realm, userSessionId);
                      AuthenticatedClientSessionModel clientSession =
                          userSession.getAuthenticatedClientSessionByClient(client.getId());
                      bothRead.countDown();
                      try {
                        bothRead.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                      }
                      clientSession.setNote("note-b", "B");
                      releaseWrites.countDown();
                      return null;
                    }));

    CompletableFuture.allOf(tx1, tx2).get(30, TimeUnit.SECONDS);

    withRealm(
        this.realmId,
        (session, realm) -> {
          ClientModel client = realm.getClientByClientId("client0");
          UserSessionModel userSession = session.sessions().getUserSession(realm, userSessionId);
          AuthenticatedClientSessionModel clientSession =
              userSession.getAuthenticatedClientSessionByClient(client.getId());

          assertThat(clientSession.getNote("base"), is("value"));
          assertThat(clientSession.getNote("note-a"), is("A"));
          assertThat(clientSession.getNote("note-b"), is("B"));
          return null;
        });
  }
}
