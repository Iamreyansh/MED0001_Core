package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PermissionCatalogStore;
import com.nammamedmate.auth.application.port.out.PermissionRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaPermissionCatalogStore implements PermissionCatalogStore {

  private final PermissionJpaRepository repository;

  public JpaPermissionCatalogStore(PermissionJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<PermissionRecord> listByDomain(String domain) {
    return repository.findByDomainOrderByResourceAscActionAsc(domain).stream()
        .map(JpaPermissionCatalogStore::toRecord)
        .toList();
  }

  @Override
  public List<PermissionRecord> listByDomainAndResource(String domain, String resource) {
    return repository.findByDomainAndResourceOrderByActionAsc(domain, resource).stream()
        .map(JpaPermissionCatalogStore::toRecord)
        .toList();
  }

  @Override
  public Optional<PermissionRecord> find(String domain, String resource, String action) {
    return repository
        .findByDomainAndResourceAndAction(domain, resource, action)
        .map(JpaPermissionCatalogStore::toRecord);
  }

  static PermissionRecord toRecord(PermissionEntity e) {
    return new PermissionRecord(e.getResource(), e.getAction(), e.getDescription(), e.getDomain());
  }
}
