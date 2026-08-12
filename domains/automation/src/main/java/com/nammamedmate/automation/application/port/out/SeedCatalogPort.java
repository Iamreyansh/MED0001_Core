package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.SeedCatalogEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeedCatalogPort {

  Optional<SeedCatalogEntry> findByKey(String seedRuleKey);

  Optional<SeedCatalogEntry> findByRuleId(UUID ruleId);

  List<SeedCatalogEntry> listAll();

  void insert(SeedCatalogEntry entry);
}
