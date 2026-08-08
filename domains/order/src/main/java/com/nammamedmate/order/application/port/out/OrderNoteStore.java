package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.OrderNote;
import java.util.List;
import java.util.UUID;

public interface OrderNoteStore {

  OrderNote insert(OrderNote note);

  List<OrderNote> listByOrderId(UUID orderId);
}
