package com.nammamedmate.auth.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PermissionJpaRepository extends JpaRepository<PermissionEntity, PermissionEntity.Pk> {

  List<PermissionEntity> findByDomainOrderByResourceAscActionAsc(String domain);

  List<PermissionEntity> findByDomainAndResourceOrderByActionAsc(String domain, String resource);

  Optional<PermissionEntity> findByDomainAndResourceAndAction(
      String domain, String resource, String action);
}
