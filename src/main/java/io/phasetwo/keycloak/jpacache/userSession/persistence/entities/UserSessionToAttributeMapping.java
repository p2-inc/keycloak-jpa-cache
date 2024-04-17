package io.phasetwo.keycloak.jpacache.userSession.persistence.entities;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@EqualsAndHashCode(of = {"userSession", "attributeName"})
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "CACHE_USER_SESSION_ATTRIBUTES")
@Entity
public class UserSessionToAttributeMapping {

  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "USER_SESSION_ID")
  private UserSession userSession;

  @Column(name = "NAME")
  private String attributeName;

  @Column(name = "VALUE")
  private List<String> attributeValues;
}
