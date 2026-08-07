package io.phasetwo.keycloak.redis.testsuite;

import jakarta.ws.rs.core.HttpHeaders;
import java.util.Optional;
import org.keycloak.http.HttpRequest;
import org.keycloak.http.HttpResponse;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.DefaultKeycloakContext;

/**
 * A {@link DefaultKeycloakContext} for the embedded model tests, which run outside of any HTTP
 * request.
 *
 * <p>Keycloak 26.7 changed {@code createHttpRequest()} to return an {@link Optional} and made
 * {@code getHttpRequest()} throw {@code ContextNotActiveException} when it is empty, where it
 * previously returned {@code null}. Callers such as {@code DeviceRepresentationProviderImpl} still
 * probe for a {@code null} from {@link #getRequestHeaders()}, so this restores that contract for
 * tests.
 */
public class TestKeycloakContext extends DefaultKeycloakContext {

  public TestKeycloakContext(KeycloakSession session) {
    super(session);
  }

  @Override
  public HttpHeaders getRequestHeaders() {
    return null;
  }

  @Override
  protected Optional<HttpRequest> createHttpRequest() {
    return Optional.empty();
  }

  @Override
  protected Optional<HttpResponse> createHttpResponse() {
    return Optional.empty();
  }
}
