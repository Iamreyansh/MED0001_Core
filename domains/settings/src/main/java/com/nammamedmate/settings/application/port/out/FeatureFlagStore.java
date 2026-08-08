package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureFlagStore {

  record FeatureFlagRow(
      UUID id,
      String name,
      String description,
      String environment,
      boolean enabled,
      int rolloutPercentage,
      String notes,
      UUID updatedBy,
      String updatedByName,
      Instant createdAt,
      Instant updatedAt) {}

  record EnvCounts(String environment, long total, long enabled) {}

  List<FeatureFlagRow> listByEnvironment(String environment);

  Optional<FeatureFlagRow> findByNameAndEnvironment(String name, String environment);

  FeatureFlagRow update(
      UUID id,
      boolean enabled,
      int rolloutPercentage,
      String notes,
      UUID updatedBy,
      Instant updatedAt);

  List<FeatureFlagRow> listAll();

  List<EnvCounts> countByEnvironment();
}
