package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "permissions")
@IdClass(PermissionEntity.Pk.class)
public class PermissionEntity {

  @Id
  @Column(name = "resource", nullable = false, length = 50)
  private String resource;

  @Id
  @Column(name = "action", nullable = false, length = 50)
  private String action;

  @Id
  @Column(name = "domain", nullable = false, length = 20)
  private String domain;

  @Column(name = "description", nullable = false)
  private String description;

  protected PermissionEntity() {}

  PermissionEntity(String resource, String action, String domain, String description) {
    this.resource = resource;
    this.action = action;
    this.domain = domain;
    this.description = description;
  }

  public String getResource() {
    return resource;
  }

  public String getAction() {
    return action;
  }

  public String getDomain() {
    return domain;
  }

  public String getDescription() {
    return description;
  }

  public static final class Pk implements Serializable {
    private String resource;
    private String action;
    private String domain;

    public Pk() {}

    public Pk(String resource, String action, String domain) {
      this.resource = resource;
      this.action = action;
      this.domain = domain;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Pk pk)) {
        return false;
      }
      return Objects.equals(resource, pk.resource)
          && Objects.equals(action, pk.action)
          && Objects.equals(domain, pk.domain);
    }

    @Override
    public int hashCode() {
      return Objects.hash(resource, action, domain);
    }
  }
}
