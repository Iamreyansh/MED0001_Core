package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.application.CustomerProfileService.UpdateProfileCommand;
import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.customer.support.FakeLoyaltyStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerProfileServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeCustomerProfileStore store;
  private InMemoryRateLimiter rateLimiter;
  private ActiveOrdersPort activeOrders;
  private CustomerProfileService service;
  private UUID customerId;
  private MedmatePrincipal customerPrincipal;

  @BeforeEach
  void setUp() {
    store = new FakeCustomerProfileStore();
    rateLimiter = new InMemoryRateLimiter(CLOCK);
    activeOrders = id -> false;
    service =
        new CustomerProfileService(store, activeOrders, new FakeLoyaltyStore(), rateLimiter, CLOCK);
    customerId = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customer(customerId));
    customerPrincipal =
        new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti");
  }

  @Test
  void getMe_includesPhoneNameSegmentWalletBalanceLoyaltyPointsLoyaltyTier() {
    Map<String, Object> data = service.getMe(customerPrincipal);

    assertThat(data)
        .containsEntry("phone", "+919876543210")
        .containsEntry("name", "Test User")
        .containsEntry("segment", "REGULAR")
        .containsEntry("wallet_balance", new BigDecimal("125.00"))
        .containsEntry("loyalty_points", 75)
        .containsEntry("loyalty_tier", "GOLD");
  }

  @Test
  void updateMe_withPreferredLanguageDe_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal, new UpdateProfileCommand(null, null, null, null, "de")))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void requestDeletion_withActiveOrders_returnsActiveOrdersExistAndDeletionNotSet() {
    activeOrders = id -> true;
    service =
        new CustomerProfileService(store, activeOrders, new FakeLoyaltyStore(), rateLimiter, CLOCK);

    assertThatThrownBy(() -> service.requestDeletion(customerPrincipal, "moving away"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACTIVE_ORDERS_EXIST");

    assertThat(store.findById(customerId).orElseThrow().deletionRequestedAt()).isNull();
  }

  @Test
  void requestDeletion_success_setsDeletionRequestedAt() {
    Map<String, Object> data = service.requestDeletion(customerPrincipal, "  privacy  ");

    assertThat(data).containsKey("deletion_scheduled_at");
    assertThat(store.findById(customerId).orElseThrow().deletionRequestedAt()).isEqualTo(NOW);
    assertThat(store.findById(customerId).orElseThrow().deletionReason()).isEqualTo("privacy");
  }

  @Test
  void requestDeletion_blankReason_storesNullReason() {
    service.requestDeletion(customerPrincipal, "   ");
    assertThat(store.findById(customerId).orElseThrow().deletionReason()).isNull();
  }

  @Test
  void requestDeletion_reasonTooLong_returnsValidationError() {
    assertThatThrownBy(() -> service.requestDeletion(customerPrincipal, "x".repeat(501)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void requestDeletion_alreadyRequested_returnsConflict() {
    service.requestDeletion(customerPrincipal, null);

    assertThatThrownBy(() -> service.requestDeletion(customerPrincipal, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DELETION_ALREADY_REQUESTED");
  }

  @Test
  void cancelDeletion_clearsPendingRequest() {
    service.requestDeletion(customerPrincipal, "bye");

    Map<String, Object> data = service.cancelDeletion(customerPrincipal);

    assertThat(data)
        .containsEntry("id", customerId)
        .containsEntry("deletion_requested_at", null)
        .containsEntry("message", "Account deletion request cancelled");
    assertThat(store.findById(customerId).orElseThrow().deletionRequestedAt()).isNull();
  }

  @Test
  void cancelDeletion_withoutPendingRequest_returnsValidationError() {
    assertThatThrownBy(() -> service.cancelDeletion(customerPrincipal))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void getMe_customerNotFound_returns404() {
    UUID missing = Ids.newId();
    MedmatePrincipal principal =
        new MedmatePrincipal(missing, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.getMe(principal))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void getMe_deletedCustomer_returns404() {
    CustomerProfileRecord deleted =
        new CustomerProfileRecord(
            customerId,
            "+919876543210",
            "Deleted",
            null,
            null,
            null,
            "en",
            "REGULAR",
            null,
            false,
            null,
            null,
            null,
            null,
            0L,
            0,
            0,
            0L,
            BigDecimal.ZERO,
            0,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW);
    store.saveProfile(deleted);

    assertThatThrownBy(() -> service.getMe(customerPrincipal))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void getMe_unauthorizedRole_returns401() {
    MedmatePrincipal admin =
        new MedmatePrincipal(customerId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.getMe(admin))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void getMe_nullPrincipal_returns401() {
    assertThatThrownBy(() -> service.getMe(null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void updateMe_nullCommand_returnsValidationError() {
    assertThatThrownBy(() -> service.updateMe(customerPrincipal, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_emptyName_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal, new UpdateProfileCommand("  ", null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_nameTooLong_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand("x".repeat(101), null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_invalidAvatarUrl_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(null, "http://insecure", null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(
                        null, "https://cdn.example.com/x.png", null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(
                        null, "https://cdn.namma-medmate.in/" + "a".repeat(500), null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_blankAvatarUrl_clearsAvatar() {
    Map<String, Object> data =
        service.updateMe(customerPrincipal, new UpdateProfileCommand(null, "  ", null, null, null));

    assertThat(data).containsEntry("avatar_url", null);
    assertThat(store.findById(customerId).orElseThrow().avatarUrl()).isNull();
  }

  @Test
  void updateMe_invalidDob_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(null, null, "not-a-date", null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_futureDob_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(null, null, "2099-01-01", null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_tooYoungDob_returnsValidationError() {
    LocalDate tooYoung = LocalDate.ofInstant(NOW, ZoneOffset.UTC).minusYears(12);

    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal,
                    new UpdateProfileCommand(null, null, tooYoung.toString(), null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_validDob_persists() {
    Map<String, Object> data =
        service.updateMe(
            customerPrincipal, new UpdateProfileCommand(null, null, "1995-06-01", null, null));

    assertThat(data).containsEntry("date_of_birth", LocalDate.of(1995, 6, 1));
  }

  @Test
  void updateMe_invalidGender_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal, new UpdateProfileCommand(null, null, null, "robot", null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_blankGender_returnsValidationError() {
    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal, new UpdateProfileCommand(null, null, null, "  ", null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateMe_validFields_persists() {
    Map<String, Object> data =
        service.updateMe(
            customerPrincipal, new UpdateProfileCommand("New Name", null, null, "female", "kn"));

    assertThat(data)
        .containsEntry("name", "New Name")
        .containsEntry("gender", "FEMALE")
        .containsEntry("preferred_language", "kn");
  }

  @Test
  void getMe_rateLimited_afterBurst() {
    for (int i = 0; i < 60; i++) {
      service.getMe(customerPrincipal);
    }

    assertThatThrownBy(() -> service.getMe(customerPrincipal))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void updateMe_validAvatarUrl_persists() {
    Map<String, Object> data =
        service.updateMe(
            customerPrincipal,
            new UpdateProfileCommand(
                null, "https://cdn.namma-medmate.in/avatars/new-avatar.png", null, null, null));

    assertThat(data)
        .containsEntry("avatar_url", "https://cdn.namma-medmate.in/avatars/new-avatar.png");
  }

  @Test
  void updateMe_patchRateLimited_afterBurst() {
    for (int i = 0; i < 20; i++) {
      service.updateMe(
          customerPrincipal, new UpdateProfileCommand("Name " + i, null, null, null, null));
    }

    assertThatThrownBy(
            () ->
                service.updateMe(
                    customerPrincipal, new UpdateProfileCommand("blocked", null, null, null, null)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void requestDeletion_rateLimited_afterBurst() {
    for (int i = 0; i < 3; i++) {
      service.requestDeletion(customerPrincipal, null);
      store.cancelDeletion(customerId);
    }

    assertThatThrownBy(() -> service.requestDeletion(customerPrincipal, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void toMeView_andPaiseToRupees_staticHelpers() {
    CustomerProfileRecord record = CustomerTestFixtures.customer(customerId);
    Map<String, Object> view = CustomerProfileService.toMeView(record);

    assertThat(view).containsEntry("wallet_balance", new BigDecimal("125.00"));
    assertThat(CustomerProfileService.paiseToRupees(1L)).isEqualByComparingTo("0.01");

    var loyalty =
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord(
            Ids.newId(), customerId, "PLATINUM", 120, 150, NOW);
    Map<String, Object> withLoyalty = CustomerProfileService.toMeView(record, loyalty);
    assertThat(withLoyalty)
        .containsEntry("loyalty_points", 120)
        .containsEntry("loyalty_tier", "PLATINUM");
  }
}
