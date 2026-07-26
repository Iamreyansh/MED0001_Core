package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.CustomerAddressStore;
import com.nammamedmate.customer.application.port.out.CustomerAddressStore.AddressRecord;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.customer.application.port.out.GeocodePort.SuggestedAddress;
import com.nammamedmate.customer.domain.AddressLabel;
import com.nammamedmate.customer.domain.GeoCoordinates;
import com.nammamedmate.customer.domain.IndianPincode;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAddressService {

  private static final int MAX_ADDRESSES = 10;
  private static final int LIST_LIMIT = 30;
  private static final int MUTATE_LIMIT = 20;
  private static final int GEOCODE_LIMIT = 10;
  private static final int MINUTE = 60;

  private final CustomerAddressStore addresses;
  private final CustomerProfileStore profiles;
  private final AddressInActiveOrderPort activeOrders;
  private final GeocodePort geocode;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public CustomerAddressService(
      CustomerAddressStore addresses,
      CustomerProfileStore profiles,
      AddressInActiveOrderPort activeOrders,
      GeocodePort geocode,
      RateLimiter rateLimiter,
      Clock clock) {
    this.addresses = addresses;
    this.profiles = profiles;
    this.activeOrders = activeOrders;
    this.geocode = geocode;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:list:" + customerId, LIST_LIMIT, MINUTE);
    return addresses.listByCustomer(customerId).stream()
        .map(CustomerAddressService::toListView)
        .toList();
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, AddressCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:create:" + customerId, MUTATE_LIMIT, MINUTE);
    ValidatedAddress validated = validate(cmd);

    int count = addresses.countByCustomer(customerId);
    if (count >= MAX_ADDRESSES) {
      throw new AppException(
          "ADDRESS_LIMIT_REACHED", "Customer already has the maximum of 10 saved addresses", 422);
    }

    boolean makeDefault = count == 0 || Boolean.TRUE.equals(cmd.isDefault());
    Instant now = clock.instant();
    UUID id = Ids.newId();

    if (makeDefault) {
      addresses.clearDefaultFlags(customerId);
    }

    AddressRecord saved =
        addresses.insert(
            new AddressRecord(
                id,
                customerId,
                validated.label().name(),
                validated.flatBuilding(),
                validated.areaLocality(),
                validated.city(),
                validated.state(),
                validated.pincode(),
                validated.latitude(),
                validated.longitude(),
                makeDefault,
                now,
                now,
                null));

    if (makeDefault) {
      addresses.setCustomerDefaultAddressId(customerId, id);
    }

    return toFullView(saved);
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal, UUID addressId, AddressCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:update:" + customerId, MUTATE_LIMIT, MINUTE);
    AddressRecord existing = requireAddress(addressId, customerId);
    ValidatedAddress validated = validate(cmd);
    Instant now = clock.instant();

    AddressRecord updated =
        addresses.update(
            new AddressRecord(
                existing.id(),
                existing.customerId(),
                validated.label().name(),
                validated.flatBuilding(),
                validated.areaLocality(),
                validated.city(),
                validated.state(),
                validated.pincode(),
                validated.latitude(),
                validated.longitude(),
                existing.isDefault(),
                existing.createdAt(),
                now,
                null));

    return toUpdateView(updated);
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID addressId) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:delete:" + customerId, MUTATE_LIMIT, MINUTE);
    AddressRecord existing = requireAddress(addressId, customerId);

    if (activeOrders.isAddressInActiveOrder(addressId)) {
      throw new AppException(
          "ADDRESS_IN_ACTIVE_ORDER",
          "Address is used by an order in PENDING, CONFIRMED, PACKED, or OUT_FOR_DELIVERY",
          409);
    }

    Instant now = clock.instant();
    addresses.softDelete(addressId, now);

    if (existing.isDefault()) {
      addresses.setCustomerDefaultAddressId(customerId, null);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "Address deleted successfully.");
    return data;
  }

  @Transactional
  public Map<String, Object> setDefault(MedmatePrincipal principal, UUID addressId) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:set-default:" + customerId, MUTATE_LIMIT, MINUTE);
    requireAddress(addressId, customerId);

    UUID previous = addresses.findDefaultAddressId(customerId).orElse(null);
    if (addressId.equals(previous)) {
      throw new AppException("ALREADY_DEFAULT", "This address is already the default", 409);
    }

    addresses.clearDefaultFlags(customerId);
    addresses.setDefault(addressId, customerId);
    addresses.setCustomerDefaultAddressId(customerId, addressId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", addressId);
    data.put("is_default", true);
    data.put("previous_default_id", previous);
    data.put("message", "Default address updated.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> geocode(
      MedmatePrincipal principal, Double latitude, Double longitude) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:addresses:geocode:" + customerId, GEOCODE_LIMIT, MINUTE);
    double lat;
    double lng;
    try {
      lat = GeoCoordinates.requireLatitude(latitude);
      lng = GeoCoordinates.requireLongitude(longitude);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }

    SuggestedAddress suggested = geocode.reverseGeocode(lat, lng);
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("flat_building", suggested.flatBuilding());
    address.put("area_locality", suggested.areaLocality());
    address.put("city", suggested.city());
    address.put("state", suggested.state());
    address.put("pincode", suggested.pincode());
    address.put("formatted_address", suggested.formattedAddress());
    address.put("latitude", suggested.latitude());
    address.put("longitude", suggested.longitude());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("suggested_address", address);
    return data;
  }

  private ValidatedAddress validate(AddressCommand cmd) {
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }
    try {
      AddressLabel label = AddressLabel.parse(cmd.label());
      String flat = requireText(cmd.flatBuilding(), "flat_building", 200);
      String area = requireText(cmd.areaLocality(), "area_locality", 200);
      String city = requireText(cmd.city(), "city", 100);
      String state = requireText(cmd.state(), "state", 100);
      String pincode = IndianPincode.requireValid(cmd.pincode());
      double lat = GeoCoordinates.requireLatitude(cmd.latitude());
      double lng = GeoCoordinates.requireLongitude(cmd.longitude());
      return new ValidatedAddress(
          label,
          flat,
          area,
          city,
          state,
          pincode,
          BigDecimal.valueOf(lat).setScale(7, RoundingMode.HALF_UP),
          BigDecimal.valueOf(lng).setScale(7, RoundingMode.HALF_UP));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }
  }

  private static String requireText(String raw, String field, int max) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    String trimmed = raw.trim();
    if (trimmed.length() > max) {
      throw new IllegalArgumentException(field + " max length is " + max);
    }
    return trimmed;
  }

  private AddressRecord requireAddress(UUID addressId, UUID customerId) {
    return addresses
        .findByIdForCustomer(addressId, customerId)
        .orElseThrow(() -> new AppException("ADDRESS_NOT_FOUND", "Address not found", 404));
  }

  private UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    UUID id = principal.subject();
    profiles
        .findById(id)
        .filter(c -> c.deletedAt() == null)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    return id;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  static Map<String, Object> toListView(AddressRecord a) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", a.id());
    data.put("label", a.label());
    data.put("flat_building", a.flatBuilding());
    data.put("area_locality", a.areaLocality());
    data.put("city", a.city());
    data.put("state", a.state());
    data.put("pincode", a.pincode());
    data.put("latitude", a.latitude().doubleValue());
    data.put("longitude", a.longitude().doubleValue());
    data.put("is_default", a.isDefault());
    data.put("created_at", a.createdAt());
    data.put("updated_at", a.updatedAt());
    return data;
  }

  static Map<String, Object> toFullView(AddressRecord a) {
    return toListView(a);
  }

  static Map<String, Object> toUpdateView(AddressRecord a) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", a.id());
    data.put("label", a.label());
    data.put("flat_building", a.flatBuilding());
    data.put("area_locality", a.areaLocality());
    data.put("city", a.city());
    data.put("state", a.state());
    data.put("pincode", a.pincode());
    data.put("latitude", a.latitude().doubleValue());
    data.put("longitude", a.longitude().doubleValue());
    data.put("is_default", a.isDefault());
    data.put("updated_at", a.updatedAt());
    return data;
  }

  public record AddressCommand(
      String label,
      String flatBuilding,
      String areaLocality,
      String city,
      String state,
      String pincode,
      Double latitude,
      Double longitude,
      Boolean isDefault) {}

  private record ValidatedAddress(
      AddressLabel label,
      String flatBuilding,
      String areaLocality,
      String city,
      String state,
      String pincode,
      BigDecimal latitude,
      BigDecimal longitude) {}
}
