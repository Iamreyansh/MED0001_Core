package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.CustomerAddressStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link CustomerAddressStore} for unit tests. */
public final class FakeCustomerAddressStore implements CustomerAddressStore {

  private final Map<UUID, AddressRecord> addresses = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> customerDefaults = new ConcurrentHashMap<>();

  public void seed(AddressRecord address) {
    addresses.put(address.id(), address);
    if (address.isDefault() && address.deletedAt() == null) {
      customerDefaults.put(address.customerId(), address.id());
    }
  }

  public Optional<UUID> customerDefault(UUID customerId) {
    return Optional.ofNullable(customerDefaults.get(customerId));
  }

  @Override
  public List<AddressRecord> listByCustomer(UUID customerId) {
    List<AddressRecord> rows = new ArrayList<>();
    for (AddressRecord a : addresses.values()) {
      if (a.customerId().equals(customerId) && a.deletedAt() == null) {
        rows.add(a);
      }
    }
    rows.sort(
        Comparator.comparing(AddressRecord::isDefault)
            .reversed()
            .thenComparing(AddressRecord::createdAt));
    return rows;
  }

  @Override
  public int countByCustomer(UUID customerId) {
    return (int)
        addresses.values().stream()
            .filter(a -> a.customerId().equals(customerId) && a.deletedAt() == null)
            .count();
  }

  @Override
  public Optional<AddressRecord> findByIdForCustomer(UUID addressId, UUID customerId) {
    AddressRecord a = addresses.get(addressId);
    if (a == null || a.deletedAt() != null || !a.customerId().equals(customerId)) {
      return Optional.empty();
    }
    return Optional.of(a);
  }

  @Override
  public AddressRecord insert(AddressRecord address) {
    addresses.put(address.id(), address);
    return address;
  }

  @Override
  public AddressRecord update(AddressRecord address) {
    addresses.put(address.id(), address);
    return address;
  }

  @Override
  public void softDelete(UUID addressId, Instant deletedAt) {
    AddressRecord a = addresses.get(addressId);
    if (a == null || a.deletedAt() != null) {
      return;
    }
    addresses.put(
        addressId,
        new AddressRecord(
            a.id(),
            a.customerId(),
            a.label(),
            a.flatBuilding(),
            a.areaLocality(),
            a.city(),
            a.state(),
            a.pincode(),
            a.latitude(),
            a.longitude(),
            false,
            a.createdAt(),
            deletedAt,
            deletedAt));
  }

  @Override
  public void clearDefaultFlags(UUID customerId) {
    for (AddressRecord a : List.copyOf(addresses.values())) {
      if (a.customerId().equals(customerId) && a.deletedAt() == null && a.isDefault()) {
        addresses.put(
            a.id(),
            new AddressRecord(
                a.id(),
                a.customerId(),
                a.label(),
                a.flatBuilding(),
                a.areaLocality(),
                a.city(),
                a.state(),
                a.pincode(),
                a.latitude(),
                a.longitude(),
                false,
                a.createdAt(),
                a.updatedAt(),
                null));
      }
    }
  }

  @Override
  public void setDefault(UUID addressId, UUID customerId) {
    AddressRecord a = addresses.get(addressId);
    if (a == null || a.deletedAt() != null || !a.customerId().equals(customerId)) {
      return;
    }
    addresses.put(
        addressId,
        new AddressRecord(
            a.id(),
            a.customerId(),
            a.label(),
            a.flatBuilding(),
            a.areaLocality(),
            a.city(),
            a.state(),
            a.pincode(),
            a.latitude(),
            a.longitude(),
            true,
            a.createdAt(),
            a.updatedAt(),
            null));
  }

  @Override
  public void setCustomerDefaultAddressId(UUID customerId, UUID addressIdOrNull) {
    if (addressIdOrNull == null) {
      customerDefaults.remove(customerId);
    } else {
      customerDefaults.put(customerId, addressIdOrNull);
    }
  }

  @Override
  public Optional<UUID> findDefaultAddressId(UUID customerId) {
    return addresses.values().stream()
        .filter(a -> a.customerId().equals(customerId) && a.deletedAt() == null && a.isDefault())
        .map(AddressRecord::id)
        .findFirst();
  }
}
