package com.nammamedmate.payment.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridge to customer {@code WalletService}. Implemented in apps/api — no domain→domain compile dep.
 */
public interface CustomerWalletPort {

  Map<String, Object> debit(
      UUID customerId, UUID orderId, long amountPaise, String idempotencyKey, String note);

  Map<String, Object> systemCredit(
      UUID customerId,
      long amountPaise,
      String reason,
      String referenceId,
      String note,
      String idempotencyKey);

  Map<String, Object> adminCredit(
      UUID adminId,
      UUID customerId,
      long amountPaise,
      String reason,
      String note,
      String referenceId,
      String idempotencyKey);

  Map<String, Object> balance(UUID customerId);

  TransactionsPage transactions(UUID customerId, Integer page, Integer limit, String type);

  record TransactionsPage(List<Map<String, Object>> transactions, long total, int page, int limit) {
    public TransactionsPage {
      transactions = List.copyOf(transactions);
    }
  }
}
