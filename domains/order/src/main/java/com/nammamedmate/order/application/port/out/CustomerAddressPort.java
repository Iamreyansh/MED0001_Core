package com.nammamedmate.order.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Order-owned address read (no domain→domain dep on customer). */
public interface CustomerAddressPort {

  record AddressRow(
      UUID id, UUID customerId, String label, String fullAddress, double lat, double lng) {}

  Optional<AddressRow> findForCustomer(UUID addressId, UUID customerId);

  Optional<AddressRow> findDefault(UUID customerId);
}
