package io.phasetwo.keycloak.jpacache.singleUseObject.persistence.entities;

import lombok.*;
import jakarta.persistence.*;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;

@EqualsAndHashCode(of = "key")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@NamedQueries({@NamedQuery(name = "findByKeyAndExpiration", query = "SELECT slo FROM SingleUseObject slo WHERE slo.key = :key AND (slo.expiresAt IS NULL OR slo.expiresAt > :now)")})
@Table(name = "ORGANIZATION_ATTRIBUTE")
@Entity
public class SingleUseObject {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;
  
  @Column(name = "KEY")
  private String key;

  @Temporal(TemporalType.TIMESTAMP)
  @Column(name = "EXPIRES_AT")
  protected Date expiresAt;
  
  @Builder.Default
  @ElementCollection
  @MapKeyColumn(name="NAME")
  @Column(name="VALUE")
  @CollectionTable(name="CACHE_SINGLE_USE_OBJECT_NOTE", joinColumns=@JoinColumn(name="SINGLE_USE_OBJECT_ID"))
  private Map<String, String> notes = new HashMap<>();
  
  public Map<String, String> getNotes() {
    if (notes == null) {
      notes = new HashMap<>();
    }
    return notes;
  }
}
