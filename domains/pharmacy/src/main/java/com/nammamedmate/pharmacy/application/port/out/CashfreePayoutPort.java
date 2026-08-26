package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** CashfreePayout payout initiation (EPIC-022 stub until live integration). */
public interface CashfreePayoutPort {

  record PayoutRequest(
      UUID pharmacyId, UUID settlementId, long amountPaise, String accountLast4, String ifsc) {}

  record PayoutResult(String cashfreeTransferId, int estimatedCreditHours) {}

  PayoutResult initiatePayout(PayoutRequest request);
}
