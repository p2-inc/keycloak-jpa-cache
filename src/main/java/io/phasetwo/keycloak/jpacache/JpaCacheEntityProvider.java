package io.phasetwo.keycloak.jpacache;

import java.util.Arrays;
import java.util.List;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import io.phasetwo.keycloak.jpacache.authSession.persistence.entities.*;
import io.phasetwo.keycloak.jpacache.loginFailure.persistence.entities.*;
import io.phasetwo.keycloak.jpacache.singleUseObject.persistence.entities.*;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.*;

/** */
public class JpaCacheEntityProvider implements JpaEntityProvider {

  private static Class<?>[] entities = {
    AuthenticationSession.class,
    RootAuthenticationSession.class,
    LoginFailure.class,
    SingleUseObject.class,
    AttributeToUserSessionMapping.class,
    AuthenticatedClientSessionValue.class,
    UserSession.class,
    UserSessionToAttributeMapping.class
  };

  @Override
  public List<Class<?>> getEntities() {
    return Arrays.<Class<?>>asList(entities);
  }

  @Override
  public String getChangelogLocation() {
    return "META-INF/jpa-changelog-jpacache-master.xml";
  }

  @Override
  public void close() {}

  @Override
  public String getFactoryId() {
    return OrganizationEntityProviderFactory.ID;
  }
}
