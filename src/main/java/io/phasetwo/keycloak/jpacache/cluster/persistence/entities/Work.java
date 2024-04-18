package io.phasetwo.keycloak.jpacache.cluster.persistence.entities;

import jakarta.persistence.*;
import java.util.HashMap;
import java.util.Map;
import lombok.*;

@EqualsAndHashCode(of = "id")
@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@NamedQueries({
  @NamedQuery(
      name = "getNextEntries",
      query =
          "SELECT w FROM Work w WHERE w.timestamp > :lastCheckTimestamp"),
  @NamedQuery(
      name = "deleteExpiredEntries",
      query = "DELETE FROM Work w WHERE w.timestamp > :expirationTimestamp")
})
@Table(name = "CACHE_WORK")
@Entity
public class Work {
  @Id
  @Column(name = "ID", length = 36)
  @Access(AccessType.PROPERTY)
  protected String id;

  @Column(name = "IGNORE_SENDER")
  private Boolean ignoreSender;

  @Column(name = "SENDER")
  private String sender;

  @Column(name = "TASK_KEY")
  private String taskKey;

  @Column(name = "CLUSTER_EVENT")
  private String clusterEvent;

  @Column(name = "TIMESTAMP")
  private Long timestamp;
}
