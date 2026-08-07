package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** RazorpayX payout initiation (EPIC-022 stub until live integration). */
public interface RazorpayXPayoutPort {

  record PayoutRequest(
      UUID pharmacyId, UUID settlementId, long amountPaise, String accountLast4, String ifsc) {}

  record PayoutResult(String razorpayxPayoutId, int estimatedCreditHours) {}

  PayoutResult initiatePayout(PayoutRequest request);
}
