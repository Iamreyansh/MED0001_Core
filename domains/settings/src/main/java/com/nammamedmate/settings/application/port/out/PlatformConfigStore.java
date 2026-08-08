package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformConfigStore {

  record ConfigRow(
      String key,
      String value,
      String type,
      String unit,
      String domain,
      boolean immutable,
      String description,
      UUID updatedBy,
      Instant updatedAt) {}

  record HistoryRow(
      UUID id,
      String key,
      String oldValue,
      String newValue,
      UUID changedBy,
      String changedByName,
      Instant changedAt,
      String notes) {}

  List<ConfigRow> listAll();

  List<ConfigRow> listByDomain(String domain);

  Optional<ConfigRow> findByKey(String key);

  void updateValue(String key, String value, UUID updatedBy, Instant updatedAt);

  void insertHistory(
      UUID id,
      String key,
      String oldValue,
      String newValue,
      UUID changedBy,
      Instant changedAt,
      String notes);

  List<HistoryRow> listHistory(String key);
}
