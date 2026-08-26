package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.Order;

/** Creates the ONLINE pharmacy invoice + sales-ledger row on DELIVERED (D12). */
public interface DeliveryInvoicePort {

  default void onDelivered(Order order) {}
}
