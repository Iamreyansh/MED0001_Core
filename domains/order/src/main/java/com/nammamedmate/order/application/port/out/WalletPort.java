package com.nammamedmate.order.application.port.out;

import java.util.UUID;

/**
 * Wallet debit at checkout and credit on refund. Bridged from customer {@code WalletService} in
 * apps/api.
 */
public interface WalletPort {

  /**
   * Debits up to {@code orderTotalPaise} (pre-wallet payable). Returns amount actually debited in
   * paise.
   */
  long debitForOrder(UUID customerId, UUID orderId, long orderTotalPaise, String description);

  /**
   * Credits wallet for a refund. Returns wallet transaction id (null if stub/no-op).
   *
   * @param idempotencyKey required for system credit replay safety
   */
  UUID creditForRefund(
      UUID customerId, UUID orderId, long amountPaise, String description, String idempotencyKey);
}
