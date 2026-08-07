package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CataloguePauseStore {

  record CataloguePauseRow(
      UUID id,
      UUID pharmacyId,
      String reason,
      Instant pausedAt,
      Instant autoResumeAt,
      Instant resumedAt,
      int itemsHiddenCount,
      UUID pausedBy) {}

  Optional<CataloguePauseRow> findActivePause(UUID pharmacyId);

  void insert(CataloguePauseRow row);

  void markResumed(UUID id, Instant resumedAt);

  List<CataloguePauseRow> findDueForResume(Instant asOf);
}
