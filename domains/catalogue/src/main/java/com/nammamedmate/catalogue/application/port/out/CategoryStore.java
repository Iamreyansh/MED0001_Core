package com.nammamedmate.catalogue.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryStore {

  record CategoryRow(
      UUID id,
      String name,
      String slug,
      String iconUrl,
      boolean visible,
      int displayOrder,
      Instant deletedAt,
      Instant createdAt,
      Instant updatedAt,
      int medicineCount) {}

  record ReorderItem(UUID id, int displayOrder) {}

  List<CategoryRow> list(boolean includeHidden, boolean includeDeleted);

  Optional<CategoryRow> findById(UUID id);

  boolean existsBySlug(String slug);

  boolean existsByName(String name);

  boolean existsByNameExcluding(String name, UUID excludeId);

  int nextDisplayOrder();

  void insert(CategoryRow row);

  void update(
      UUID id,
      String name,
      String iconUrl,
      Boolean visible,
      Integer displayOrder,
      Instant updatedAt);

  void softDelete(UUID id, Instant deletedAt);

  int countExistingIds(List<UUID> ids);

  void reorder(List<ReorderItem> items, Instant updatedAt);
}
