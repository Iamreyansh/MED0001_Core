package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** SMS / finance-alert side effects for rider payout release (bridged to outbox in apps/api). */
public interface RiderPayoutNotificationPort {

  void payoutReleased(UUID riderId, UUID payoutId, long netPaise, String cashfreeTransferId);

  void payoutFailed(UUID riderId, UUID payoutId, String error);
}
