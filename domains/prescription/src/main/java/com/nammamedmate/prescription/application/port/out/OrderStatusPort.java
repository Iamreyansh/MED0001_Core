package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

public interface OrderStatusPort {

  void markReadyForPickup(UUID orderId);
}
