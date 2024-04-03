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

package io.phasetwo.keycloak.mapstorage.keys;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.models.KeycloakSession;

public class MapPublicKeyStorageProvider implements PublicKeyStorageProvider {

  private static final Logger log = Logger.getLogger(MapPublicKeyStorageProvider.class);

  private final KeycloakSession session;

  private final Map<String, FutureTask<PublicKeysWrapper>> tasksInProgress;

  public MapPublicKeyStorageProvider(
      KeycloakSession session, Map<String, FutureTask<PublicKeysWrapper>> tasksInProgress) {
    this.session = session;
    this.tasksInProgress = tasksInProgress;
  }

  @Override
  public KeyWrapper getFirstPublicKey(String modelKey, String algorithm, PublicKeyLoader loader) {
    return getPublicKey(modelKey, null, algorithm, loader);
  }

  @Override
  public KeyWrapper getPublicKey(
      String modelKey, String kid, String algorithm, PublicKeyLoader loader) {

    PublicKeysWrapper currentKeys = getKey(modelKey, loader);

    if (currentKeys != null) {
      KeyWrapper publicKey = currentKeys.getKeyByKidAndAlg(kid, algorithm);
      if (publicKey != null) {
        return publicKey;
      }
    }

    List<String> availableKids =
        currentKeys == null ? Collections.emptyList() : currentKeys.getKids();
    log.warnf(
        "PublicKey wasn't found in the storage. Requested kid: '%s' . Available kids: '%s'",
        kid, availableKids);

    return null;
  }

  @Override
  public boolean reloadKeys(String modelKey, PublicKeyLoader loader) {
    return getKey(modelKey, loader) != null;
  }

  @Override
  public KeyWrapper getFirstPublicKey(
      String modelKey, Predicate<KeyWrapper> predicate, PublicKeyLoader loader) {
    PublicKeysWrapper currentKeys = getKey(modelKey, loader);
    if (currentKeys != null) {
      KeyWrapper key = currentKeys.getKeyByPredicate(predicate);
      if (key != null) {
        return key.cloneKey();
      }
    }
    return null;
  }

  @Override
  public List<KeyWrapper> getKeys(String modelKey, PublicKeyLoader loader) {
    PublicKeysWrapper currentKeys = getKey(modelKey, loader);

    return currentKeys == null
        ? Collections.emptyList()
        : currentKeys.getKeys().stream().map(KeyWrapper::cloneKey).collect(Collectors.toList());
  }

  private PublicKeysWrapper getKey(String modelKey, PublicKeyLoader loader) {
    WrapperCallable wrapperCallable = new WrapperCallable(modelKey, loader);
    FutureTask<PublicKeysWrapper> task = new FutureTask<>(wrapperCallable);
    FutureTask<PublicKeysWrapper> existing = tasksInProgress.putIfAbsent(modelKey, task);
    PublicKeysWrapper currentKeys;

    if (existing == null) {
      task.run();
    } else {
      task = existing;
    }

    try {
      return task.get();

    } catch (ExecutionException ee) {
      throw new RuntimeException("Error when loading public keys: " + ee.getMessage(), ee);
    } catch (InterruptedException ie) {
      throw new RuntimeException("Error. Interrupted when loading public keys", ie);
    } finally {
      // Our thread inserted the task. Let's clean
      if (existing == null) {
        tasksInProgress.remove(modelKey);
      }
    }
  }

  private class WrapperCallable implements Callable<PublicKeysWrapper> {

    private final String modelKey;
    private final PublicKeyLoader delegate;

    public WrapperCallable(String modelKey, PublicKeyLoader delegate) {
      this.modelKey = modelKey;
      this.delegate = delegate;
    }

    @Override
    public PublicKeysWrapper call() throws Exception {
      PublicKeysWrapper publicKeys = delegate.loadKeys();

      if (log.isDebugEnabled()) {
        log.debugf(
            "Public keys retrieved successfully for model %s. New kids: %s",
            modelKey, publicKeys.getKids());
      }

      return publicKeys;
    }
  }

  @Override
  public void close() {}
}
