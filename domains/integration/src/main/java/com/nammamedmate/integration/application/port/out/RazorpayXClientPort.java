package com.nammamedmate.integration.application.port.out;

import java.util.Map;

public interface RazorpayXClientPort {

  FundAccountResult createFundAccount(CreateFundAccountRequest request);

  PayoutResult createPayout(CreatePayoutRequest request);

  record CreateFundAccountRequest(
      String entityType,
      String entityId,
      String bankName,
      String accountNumber,
      String ifsc,
      String accountHolderName) {}

  record FundAccountResult(String contactId, String fundAccountId) {}

  record CreatePayoutRequest(
      String fundAccountId,
      long amountPaise,
      String mode,
      String purpose,
      String referenceId,
      Map<String, String> notes) {
    public CreatePayoutRequest {
      notes = notes == null ? null : Map.copyOf(notes);
    }
  }

  record PayoutResult(String payoutId, String status) {}
}
