package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CustomerContactPort {

  record Contact(String name, String phone) {}

  Optional<Contact> find(UUID customerId);

  int previousOrdersCount(UUID customerId, UUID pharmacyId);
}
