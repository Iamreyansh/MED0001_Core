package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.PaymentMethod;
import java.util.UUID;

/** Test double for lifecycle unit tests without JDBC refund tables. */
public class StubRefundInitiatorAdapter implements RefundInitiatorPort {

  @Override
  public RefundPlan initiate(
      Order order, String reason, ActorType cancelledByType, UUID cancelledById) {
    if (order == null) {
      return new RefundPlan(false, 0L, null);
    }
    PaymentMethod method = order.paymentMethod();
    if (method == PaymentMethod.COD) {
      return new RefundPlan(false, 0L, null);
    }
    String to = method == PaymentMethod.WALLET ? "WALLET" : "SOURCE";
    return new RefundPlan(true, order.totalPayablePaise(), to);
  }
}
