package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** CashfreePayout payout initiation for pharmacy settlements (live|stub). */
public interface CashfreePayoutPort {

  record PayoutRequest(
      UUID pharmacyId, UUID settlementId, long amountPaise, String accountLast4, String ifsc) {}

  record PayoutResult(String cashfreeTransferId, int estimatedCreditHours) {}

  PayoutResult initiatePayout(PayoutRequest request);
}
