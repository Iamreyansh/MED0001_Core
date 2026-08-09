package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RazorpayXPayoutRecordStore {

  void insert(RazorpayXPayoutRecord record);

  void update(RazorpayXPayoutRecord record);

  Optional<RazorpayXPayoutRecord> findById(UUID id);

  Optional<RazorpayXPayoutRecord> findByRazorpayxPayoutId(String payoutId);

  Optional<RazorpayXPayoutRecord> findByReferenceId(String referenceId);

  /**
   * Failed payouts with retry_count=0 and initiated_at ≤ cutoff (eligible for first auto-retry).
   */
  List<RazorpayXPayoutRecord> findRetryEligible(Instant initiatedBefore, int limit);
}
