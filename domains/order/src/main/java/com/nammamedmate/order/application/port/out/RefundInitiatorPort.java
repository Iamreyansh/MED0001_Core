package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import java.util.UUID;

/** Auto-refund + cancellation record for lifecycle cancel paths. */
public interface RefundInitiatorPort {

  record RefundPlan(boolean initiated, long amountPaise, String refundTo) {}

  RefundPlan initiate(Order order, String reason, ActorType cancelledByType, UUID cancelledById);
}
