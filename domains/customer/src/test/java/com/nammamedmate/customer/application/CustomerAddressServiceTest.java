package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.adapter.out.geocode.StubGeocodeClient;
import com.nammamedmate.customer.application.CustomerAddressService.AddressCommand;
import com.nammamedmate.customer.application.port.out.AddressInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.CustomerAddressStore.AddressRecord;
import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerAddressStore;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerAddressServiceTest {

  private static final Clock CLOCK = Clock.fixed(CustomerTestFixtures.NOW, ZoneOffset.UTC);

  private FakeCustomerAddressStore addresses;
  private FakeCustomerProfileStore profiles;
  private AddressInActiveOrderPort activeOrders;
  private GeocodePort geocode;
  private InMemoryRateLimiter rateLimiter;
  private CustomerAddressService service;
  private UUID customerId;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    addresses = new FakeCustomerAddressStore();
    profiles = new FakeCustomerProfileStore();
    customerId = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(customerId));
    activeOrders = id -> false;
    geocode = new StubGeocodeClient();
    rateLimiter = new InMemoryRateLimiter(CLOCK);
    service =
        new CustomerAddressService(addresses, profiles, activeOrders, geocode, rateLimiter, CLOCK);
    principal = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti");
  }

  @Test
  void create_firstAddress_isDefault() {
    Map<String, Object> created = service.create(principal, validCommand(null));

    assertThat(created.get("is_default")).isEqualTo(true);
    assertThat(addresses.customerDefault(customerId)).contains((UUID) created.get("id"));
  }

  @Test
  void create_secondAddress_notDefaultUnlessRequested() {
    service.create(principal, validCommand(null));
    Map<String, Object> second = service.create(principal, validCommand(false));

    assertThat(second.get("is_default")).isEqualTo(false);
  }

  @Test
  void create_withIsDefault_switchesDefault() {
    Map<String, Object> first = service.create(principal, validCommand(null));
    Map<String, Object> second = service.create(principal, validCommand(true));

    assertThat(second.get("is_default")).isEqualTo(true);
    assertThat(
            addresses
                .findByIdForCustomer((UUID) first.get("id"), customerId)
                .orElseThrow()
                .isDefault())
        .isFalse();
  }

  @Test
  void create_eleventhAddress_returnsLimitReached() {
    for (int i = 0; i < 10; i++) {
      service.create(principal, validCommand(i == 0 ? true : false));
    }

    assertThatThrownBy(() -> service.create(principal, validCommand(false)))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("ADDRESS_LIMIT_REACHED");
              assertThat(app.httpStatus()).isEqualTo(422);
            });
  }

  @Test
  void setDefault_switchesAtomically() {
    Map<String, Object> a = service.create(principal, validCommand(true));
    Map<String, Object> b = service.create(principal, validCommand(false));

    Map<String, Object> result = service.setDefault(principal, (UUID) b.get("id"));

    assertThat(result.get("is_default")).isEqualTo(true);
    assertThat(result.get("previous_default_id")).isEqualTo(a.get("id"));
    assertThat(
            addresses.findByIdForCustomer((UUID) a.get("id"), customerId).orElseThrow().isDefault())
        .isFalse();
    assertThat(
            addresses.findByIdForCustomer((UUID) b.get("id"), customerId).orElseThrow().isDefault())
        .isTrue();
  }

  @Test
  void setDefault_alreadyDefault_conflict() {
    Map<String, Object> a = service.create(principal, validCommand(true));

    assertThatThrownBy(() -> service.setDefault(principal, (UUID) a.get("id")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_DEFAULT");
  }

  @Test
  void delete_addressInActiveOrder_conflict() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    UUID addressId = (UUID) created.get("id");
    service =
        new CustomerAddressService(
            addresses, profiles, id -> id.equals(addressId), geocode, rateLimiter, CLOCK);

    assertThatThrownBy(() -> service.delete(principal, addressId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_IN_ACTIVE_ORDER");
  }

  @Test
  void delete_onlyAddress_clearsCustomerDefault() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    UUID addressId = (UUID) created.get("id");

    Map<String, Object> result = service.delete(principal, addressId);

    assertThat(result.get("message")).isEqualTo("Address deleted successfully.");
    assertThat(addresses.customerDefault(customerId)).isEmpty();
    assertThat(addresses.findByIdForCustomer(addressId, customerId)).isEmpty();
  }

  @Test
  void delete_otherCustomersAddress_notFound() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    UUID otherCustomer = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(otherCustomer));
    MedmatePrincipal other =
        new MedmatePrincipal(otherCustomer, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");

    assertThatThrownBy(() -> service.delete(other, (UUID) created.get("id")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_NOT_FOUND");
  }

  @Test
  void update_invalidPincode_validationError() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    AddressCommand bad =
        new AddressCommand(
            "HOME", "Flat 1", "Area", "Bengaluru", "Karnataka", "56006", 12.97, 77.59, null);

    assertThatThrownBy(() -> service.update(principal, (UUID) created.get("id"), bad))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(app.getMessage()).contains("pincode must be exactly 6 digits");
            });
  }

  @Test
  void update_happyPath() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    Map<String, Object> updated =
        service.update(
            principal,
            (UUID) created.get("id"),
            new AddressCommand(
                "WORK",
                "Tower 2",
                "Whitefield",
                "Bengaluru",
                "Karnataka",
                "560066",
                12.9693,
                77.7499,
                null));

    assertThat(updated.get("label")).isEqualTo("WORK");
    assertThat(updated.get("flat_building")).isEqualTo("Tower 2");
    assertThat(updated).containsKey("updated_at").doesNotContainKey("created_at");
  }

  @Test
  void geocode_bengaluruCoords_returnsCityAndPincode() {
    Map<String, Object> data = service.geocode(principal, 12.9716, 77.5946);

    @SuppressWarnings("unchecked")
    Map<String, Object> suggested = (Map<String, Object>) data.get("suggested_address");
    assertThat(suggested.get("city")).isEqualTo("Bengaluru");
    assertThat(suggested.get("pincode")).isNotNull();
  }

  @Test
  void geocode_invalidCoords_validationError() {
    assertThatThrownBy(() -> service.geocode(principal, null, 77.0))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void list_returnsAddresses() {
    service.create(principal, validCommand(true));
    assertThat(service.list(principal)).hasSize(1);
  }

  @Test
  void create_nullBody_validationError() {
    assertThatThrownBy(() -> service.create(principal, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void create_missingFields_validationError() {
    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new AddressCommand(
                        "HOME", " ", "Area", "City", "State", "560066", 1.0, 1.0, null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void create_nullField_validationError() {
    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new AddressCommand(
                        "HOME", null, "Area", "City", "State", "560066", 1.0, 1.0, null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void unauthorized_nullPrincipal() {
    assertThatThrownBy(() -> service.list(null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void deletedCustomer_notFound() {
    UUID deletedId = Ids.newId();
    var base = CustomerTestFixtures.customer(deletedId);
    profiles.saveProfile(
        new com.nammamedmate.customer.application.port.out.CustomerProfileStore
            .CustomerProfileRecord(
            base.id(),
            base.phone(),
            base.name(),
            base.avatarUrl(),
            base.dateOfBirth(),
            base.gender(),
            base.preferredLanguage(),
            base.segment(),
            base.city(),
            base.isFlagged(),
            base.flagReason(),
            base.flagNote(),
            base.flaggedBy(),
            base.flaggedAt(),
            base.walletBalancePaise(),
            base.loyaltyPoints(),
            base.totalOrders(),
            base.totalLtvPaise(),
            base.cancelRate(),
            base.disputeCount(),
            base.lastOrderAt(),
            base.deletionRequestedAt(),
            base.deletionReason(),
            base.createdAt(),
            base.updatedAt(),
            CustomerTestFixtures.NOW));
    MedmatePrincipal deleted =
        new MedmatePrincipal(deletedId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.list(deleted))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void unauthorized_nonCustomer() {
    MedmatePrincipal admin =
        new MedmatePrincipal(customerId, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.list(admin))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void customerNotFound() {
    MedmatePrincipal missing =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.list(missing))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void rateLimited() {
    InMemoryRateLimiter tight = new InMemoryRateLimiter(CLOCK);
    service = new CustomerAddressService(addresses, profiles, activeOrders, geocode, tight, CLOCK);
    for (int i = 0; i < 30; i++) {
      service.list(principal);
    }
    assertThatThrownBy(() -> service.list(principal))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void delete_nonDefault_leavesCustomerDefault() {
    Map<String, Object> first = service.create(principal, validCommand(true));
    Map<String, Object> second = service.create(principal, validCommand(false));

    service.delete(principal, (UUID) second.get("id"));

    assertThat(addresses.customerDefault(customerId)).contains((UUID) first.get("id"));
  }

  @Test
  void update_fieldTooLong_validationError() {
    Map<String, Object> created = service.create(principal, validCommand(true));
    String tooLong = "x".repeat(201);

    assertThatThrownBy(
            () ->
                service.update(
                    principal,
                    (UUID) created.get("id"),
                    new AddressCommand(
                        "HOME", tooLong, "Area", "City", "State", "560066", 1.0, 1.0, null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void seedHelper_coversFindDefault() {
    UUID id = Ids.newId();
    addresses.seed(
        new AddressRecord(
            id,
            customerId,
            "HOME",
            "F",
            "A",
            "C",
            "S",
            "560066",
            BigDecimal.ONE,
            BigDecimal.ONE,
            true,
            CustomerTestFixtures.NOW,
            CustomerTestFixtures.NOW,
            null));
    assertThat(addresses.findDefaultAddressId(customerId)).contains(id);
  }

  private static AddressCommand validCommand(Boolean isDefault) {
    return new AddressCommand(
        "HOME",
        "Flat 4B",
        "Whitefield",
        "Bengaluru",
        "Karnataka",
        "560066",
        12.9693,
        77.7499,
        isDefault);
  }
}
