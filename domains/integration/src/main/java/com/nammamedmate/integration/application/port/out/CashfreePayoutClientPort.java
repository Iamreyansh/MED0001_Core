package com.nammamedmate.integration.application.port.out;

import java.util.Map;

public interface CashfreePayoutClientPort {

  BeneficiaryResult createBeneficiary(CreateBeneficiaryRequest request);

  PayoutResult createPayout(CreatePayoutRequest request);

  record CreateBeneficiaryRequest(
      String entityType,
      String entityId,
      String bankName,
      String accountNumber,
      String ifsc,
      String accountHolderName) {}

  record BeneficiaryResult(String contactId, String beneficiaryId) {}

  record CreatePayoutRequest(
      String beneficiaryId,
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
