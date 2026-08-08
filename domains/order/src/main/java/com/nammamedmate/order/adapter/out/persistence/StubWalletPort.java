package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.WalletPort;
import java.util.UUID;

/** ponytail: no-op until apps/api OrderCustomerBridgeConfig wires WalletService. */
public final class StubWalletPort implements WalletPort {

  @Override
  public long debitForOrder(
      UUID customerId, UUID orderId, long orderTotalPaise, String description) {
    return 0L;
  }

  @Override
  public UUID creditForRefund(
      UUID customerId, UUID orderId, long amountPaise, String description, String idempotencyKey) {
    return null;
  }
}
