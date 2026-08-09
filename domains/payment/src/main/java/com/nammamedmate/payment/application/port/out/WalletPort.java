package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Wallet debit at payment initiate. Bridged from customer {@code WalletService} in apps/api. */
public interface WalletPort {

  /**
   * Debits up to {@code amountPaise} from the customer wallet. Returns amount actually debited in
   * paise (0 if no balance).
   */
  long debitForOrder(UUID customerId, UUID orderId, long amountPaise, String description);
}
