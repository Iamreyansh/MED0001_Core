package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashfreePayoutRecordStore {

  void insert(CashfreePayoutRecord record);

  void update(CashfreePayoutRecord record);

  Optional<CashfreePayoutRecord> findById(UUID id);

  Optional<CashfreePayoutRecord> findByCashfreexPayoutId(String payoutId);

  Optional<CashfreePayoutRecord> findByReferenceId(String referenceId);

  /**
   * Failed payouts with retry_count=0 and initiated_at ≤ cutoff (eligible for first auto-retry).
   */
  List<CashfreePayoutRecord> findRetryEligible(Instant initiatedBefore, int limit);
}
