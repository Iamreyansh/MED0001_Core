package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** RazorpayX payout initiation for pharmacy settlements (live|stub). */
public interface RazorpayXPayoutPort {

  record PayoutRequest(
      UUID pharmacyId, UUID settlementId, long amountPaise, String accountLast4, String ifsc) {}

  record PayoutResult(String razorpayxPayoutId, int estimatedCreditHours) {}

  PayoutResult initiatePayout(PayoutRequest request);
}
