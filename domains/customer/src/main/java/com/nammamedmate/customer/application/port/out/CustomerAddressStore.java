package com.nammamedmate.customer.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAddressStore {

  List<AddressRecord> listByCustomer(UUID customerId);

  int countByCustomer(UUID customerId);

  Optional<AddressRecord> findByIdForCustomer(UUID addressId, UUID customerId);

  AddressRecord insert(AddressRecord address);

  AddressRecord update(AddressRecord address);

  void softDelete(UUID addressId, Instant deletedAt);

  void clearDefaultFlags(UUID customerId);

  void setDefault(UUID addressId, UUID customerId);

  void setCustomerDefaultAddressId(UUID customerId, UUID addressIdOrNull);

  Optional<UUID> findDefaultAddressId(UUID customerId);

  record AddressRecord(
      UUID id,
      UUID customerId,
      String label,
      String flatBuilding,
      String areaLocality,
      String city,
      String state,
      String pincode,
      BigDecimal latitude,
      BigDecimal longitude,
      boolean isDefault,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}
}
