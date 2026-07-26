package com.nammamedmate.auth.application.port.out;

import java.util.List;
import java.util.Optional;

public interface PermissionCatalogStore {

  List<PermissionRecord> listByDomain(String domain);

  List<PermissionRecord> listByDomainAndResource(String domain, String resource);

  Optional<PermissionRecord> find(String domain, String resource, String action);
}
