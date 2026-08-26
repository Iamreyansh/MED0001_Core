package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.adapter.out.cashfree.StubCashfreeVpaClient;
import com.nammamedmate.customer.application.PaymentMethodService.CardCommand;
import com.nammamedmate.customer.application.PaymentMethodService.UpiCommand;
import com.nammamedmate.customer.application.port.out.PaymentMethodInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.PaymentMethodStore.PaymentMethodRecord;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.customer.support.FakePaymentMethodStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentMethodServiceTest {

  private static final Clock CLOCK = Clock.fixed(CustomerTestFixtures.NOW, ZoneOffset.UTC);
  private static final byte[] AES_KEY = new byte[32];

  private FakePaymentMethodStore methods;
  private FakeCustomerProfileStore profiles;
  private PaymentMethodInActiveOrderPort activeOrders;
  private AesGcmCipher cipher;
  private InMemoryRateLimiter rateLimiter;
  private PaymentMethodService service;
  private UUID customerId;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    methods = new FakePaymentMethodStore();
    profiles = new FakeCustomerProfileStore();
    customerId = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(customerId));
    activeOrders = id -> false;
    cipher = new AesGcmCipher(AES_KEY, new SecureRandom(new byte[] {1}));
    rateLimiter = new InMemoryRateLimiter(CLOCK);
    service =
        new PaymentMethodService(
            methods,
            profiles,
            activeOrders,
            new StubCashfreeVpaClient(),
            cipher,
            rateLimiter,
            CLOCK,
            noopTx());
    principal = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti");
  }

  private static PlatformTransactionManager noopTx() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  @Test
  void saveUpi_validatesEncryptsAndMasks() {
    Map<String, Object> created =
        service.saveUpi(principal, new UpiCommand("Ramesh@okaxis", "GPay"), null);

    assertThat(created.get("type")).isEqualTo("UPI");
    assertThat(created.get("upi_handle")).isEqualTo("***@okaxis");
    assertThat(created.get("nickname")).isEqualTo("GPay");
    assertThat(created).doesNotContainKey("upi_id");
    assertThat(created.get("is_default")).isEqualTo(false);

    PaymentMethodRecord stored =
        methods.findByIdForCustomer((UUID) created.get("id"), customerId).orElseThrow();
    assertThat(cipher.decrypt(stored.upiIdEncrypted())).isEqualTo("ramesh@okaxis");
    assertThat(stored.upiIdEncrypted()).doesNotContain("ramesh");
  }

  @Test
  void saveUpi_sixth_limitReached() {
    for (int i = 0; i < 5; i++) {
      service.saveUpi(principal, new UpiCommand("user" + i + "@okaxis", null), null);
    }

    assertThatThrownBy(() -> service.saveUpi(principal, new UpiCommand("user5@okaxis", null), null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("PAYMENT_METHOD_LIMIT_REACHED");
              assertThat(app.httpStatus()).isEqualTo(422);
              assertThat(app.getMessage()).contains("UPI");
            });
  }

  @Test
  void saveUpi_duplicate_conflict() {
    service.saveUpi(principal, new UpiCommand("same@ybl", null), null);

    assertThatThrownBy(() -> service.saveUpi(principal, new UpiCommand("SAME@ybl", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UPI_ALREADY_SAVED");
  }

  @Test
  void saveUpi_invalidVpaFromCashfree() {
    assertThatThrownBy(() -> service.saveUpi(principal, new UpiCommand("foo@invalid", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_UPI_VPA");
  }

  @Test
  void saveUpi_timeoutFromCashfree() {
    assertThatThrownBy(
            () -> service.saveUpi(principal, new UpiCommand("timeout@okaxis", null), null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("VPA_VALIDATION_TIMEOUT");
              assertThat(app.httpStatus()).isEqualTo(503);
            });
  }

  @Test
  void saveUpi_badFormat() {
    assertThatThrownBy(() -> service.saveUpi(principal, new UpiCommand("not-a-vpa", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void saveCard_missingLast4() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("token_abc123", null, "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(app.getMessage()).contains("card_last4");
            });
  }

  @Test
  void saveCard_encryptsTokenAndOmitsFromResponse() {
    Map<String, Object> created =
        service.saveCard(
            principal,
            new CardCommand("token_abc123", "4242", "VISA", "CREDIT", "Axis Flipkart"),
            null);

    assertThat(created.get("type")).isEqualTo("CARD");
    assertThat(created.get("card_last4")).isEqualTo("4242");
    assertThat(created.get("card_network")).isEqualTo("VISA");
    assertThat(created.get("card_type")).isEqualTo("CREDIT");
    assertThat(created).doesNotContainKey("gateway_token_id");

    PaymentMethodRecord stored =
        methods.findByIdForCustomer((UUID) created.get("id"), customerId).orElseThrow();
    assertThat(cipher.decrypt(stored.cashfreeTokenEncrypted())).isEqualTo("token_abc123");
  }

  @Test
  void saveCard_badTokenFormat() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal,
                    new CardCommand("not_a_token", "4242", "VISA", "CREDIT", null),
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CASHFREE_TOKEN");
  }

  @Test
  void saveCard_sixth_limitReached() {
    for (int i = 0; i < 5; i++) {
      service.saveCard(
          principal, new CardCommand("token_card" + i, "100" + i, "VISA", "DEBIT", null), null);
    }

    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("token_card5", "9999", "VISA", "DEBIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_LIMIT_REACHED");
  }

  @Test
  void list_masksUpiAndOmitsToken() {
    service.saveUpi(principal, new UpiCommand("ramesh@okicici", "GPay"), null);
    service.saveCard(
        principal, new CardCommand("token_xyz", "4242", "MASTERCARD", "DEBIT", "SBI"), null);

    Map<String, Object> listed = service.list(principal);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upi = (List<Map<String, Object>>) listed.get("upi");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cards = (List<Map<String, Object>>) listed.get("cards");
    assertThat(upi).hasSize(1);
    assertThat(upi.get(0).get("upi_handle")).isEqualTo("***@okicici");
    assertThat(upi.get(0)).doesNotContainKey("upi_id");
    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).get("card_last4")).isEqualTo("4242");
    assertThat(cards.get(0)).doesNotContainKey("gateway_token_id");
  }

  @Test
  void delete_blockedWhenInActiveOrder() {
    Map<String, Object> created =
        service.saveCard(
            principal, new CardCommand("token_del", "1111", "VISA", "CREDIT", null), null);
    UUID id = (UUID) created.get("id");
    service =
        new PaymentMethodService(
            methods,
            profiles,
            methodId -> methodId.equals(id),
            new StubCashfreeVpaClient(),
            cipher,
            rateLimiter,
            CLOCK,
            noopTx());

    assertThatThrownBy(() -> service.delete(principal, id))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_IN_ACTIVE_ORDER");
  }

  @Test
  void delete_softDeletes() {
    Map<String, Object> created =
        service.saveUpi(principal, new UpiCommand("gone@ybl", null), null);
    UUID id = (UUID) created.get("id");

    Map<String, Object> result = service.delete(principal, id);

    assertThat(result.get("message")).asString().contains("removed");
    assertThat(methods.findByIdForCustomer(id, customerId)).isEmpty();
  }

  @Test
  void setDefault_switchesAtomically() {
    Map<String, Object> a = service.saveUpi(principal, new UpiCommand("aa@okaxis", null), null);
    Map<String, Object> b =
        service.saveCard(
            principal, new CardCommand("token_b", "2222", "VISA", "CREDIT", null), null);

    service.setDefault(principal, (UUID) a.get("id"));
    Map<String, Object> result = service.setDefault(principal, (UUID) b.get("id"));

    assertThat(result.get("is_default")).isEqualTo(true);
    assertThat(result.get("type")).isEqualTo("CARD");
    assertThat(result.get("previous_default_id")).isEqualTo(a.get("id"));
    assertThat(
            methods.findByIdForCustomer((UUID) a.get("id"), customerId).orElseThrow().isDefault())
        .isFalse();
    assertThat(
            methods.findByIdForCustomer((UUID) b.get("id"), customerId).orElseThrow().isDefault())
        .isTrue();
  }

  @Test
  void setDefault_alreadyDefault_conflict() {
    Map<String, Object> a = service.saveUpi(principal, new UpiCommand("aa@okaxis", null), null);
    service.setDefault(principal, (UUID) a.get("id"));

    assertThatThrownBy(() -> service.setDefault(principal, (UUID) a.get("id")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_DEFAULT");
  }

  @Test
  void delete_notFound() {
    assertThatThrownBy(() -> service.delete(principal, Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
  }

  @Test
  void unauthorized_nullPrincipal() {
    assertThatThrownBy(() -> service.list(null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void saveUpi_nullBody() {
    assertThatThrownBy(() -> service.saveUpi(principal, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void saveCard_nullBody() {
    assertThatThrownBy(() -> service.saveCard(principal, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void nicknameTooLong() {
    assertThatThrownBy(
            () -> service.saveUpi(principal, new UpiCommand("aa@okaxis", "x".repeat(51)), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
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
    service =
        new PaymentMethodService(
            methods,
            profiles,
            activeOrders,
            new StubCashfreeVpaClient(),
            cipher,
            tight,
            CLOCK,
            noopTx());
    for (int i = 0; i < 30; i++) {
      service.list(principal);
    }
    assertThatThrownBy(() -> service.list(principal))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void saveCard_invalidNetwork() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal,
                    new CardCommand("token_x", "4242", "DISCOVER", "CREDIT", null),
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void saveCard_invalidLast4() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("token_x", "42", "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void saveCard_tokenTooLong() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal,
                    new CardCommand("token_" + "x".repeat(100), "4242", "VISA", "CREDIT", null),
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void saveCard_missingToken() {
    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand(null, "4242", "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void wrongRole_unauthorized() {
    MedmatePrincipal admin =
        new MedmatePrincipal(customerId, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(admin))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void setDefault_notFound() {
    assertThatThrownBy(() -> service.setDefault(principal, Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
  }

  @Test
  void softDeletedCustomer_notFound() {
    var base = CustomerTestFixtures.customer(customerId);
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

    assertThatThrownBy(() -> service.list(principal))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void blankNickname_andBlankTokenLast4() {
    Map<String, Object> upi =
        service.saveUpi(principal, new UpiCommand("blanknick@okaxis", "   "), null);
    assertThat(upi.get("nickname")).isNull();

    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("   ", "4242", "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("token_ok", "   ", "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void delete_otherCustomersMethod_notFound() {
    Map<String, Object> created =
        service.saveUpi(principal, new UpiCommand("mine@okaxis", null), null);
    UUID methodId = (UUID) created.get("id");

    UUID otherCustomer = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(otherCustomer));
    MedmatePrincipal other =
        new MedmatePrincipal(otherCustomer, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");

    assertThatThrownBy(() -> service.delete(other, methodId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    assertThat(methods.findByIdForCustomer(methodId, customerId)).isPresent();
  }

  @Test
  void setDefault_otherCustomersMethod_notFound() {
    Map<String, Object> created =
        service.saveUpi(principal, new UpiCommand("mine@okaxis", null), null);
    UUID methodId = (UUID) created.get("id");

    UUID otherCustomer = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(otherCustomer));
    MedmatePrincipal other =
        new MedmatePrincipal(otherCustomer, AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");

    assertThatThrownBy(() -> service.setDefault(other, methodId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
  }

  @Test
  void saveCard_duplicateToken_conflict() {
    service.saveCard(
        principal, new CardCommand("token_same", "4242", "VISA", "CREDIT", null), null);

    assertThatThrownBy(
            () ->
                service.saveCard(
                    principal, new CardCommand("token_same", "4242", "VISA", "CREDIT", null), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CARD_ALREADY_SAVED");
  }

  @Test
  void saveCard_idempotencyKey_replays() {
    Map<String, Object> first =
        service.saveCard(
            principal,
            new CardCommand("token_idem", "1111", "VISA", "CREDIT", null),
            "idem-card-1");
    Map<String, Object> second =
        service.saveCard(
            principal,
            new CardCommand("token_other", "2222", "VISA", "CREDIT", null),
            "idem-card-1");

    assertThat(second.get("id")).isEqualTo(first.get("id"));
    assertThat(second.get("card_last4")).isEqualTo("1111");
    assertThat(methods.countByCustomerAndType(customerId, "CARD")).isEqualTo(1);
  }

  @Test
  void saveUpi_idempotencyKey_replays() {
    Map<String, Object> first =
        service.saveUpi(principal, new UpiCommand("idem@okaxis", null), "idem-upi-1");
    Map<String, Object> second =
        service.saveUpi(principal, new UpiCommand("other@okaxis", null), "idem-upi-1");

    assertThat(second.get("id")).isEqualTo(first.get("id"));
    assertThat(methods.countByCustomerAndType(customerId, "UPI")).isEqualTo(1);
  }

  @Test
  void listForAdmin_returnsMasked() {
    service.saveUpi(principal, new UpiCommand("adminview@okaxis", null), null);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "a");

    Map<String, Object> listed = service.listForAdmin(admin, customerId);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> upi = (List<Map<String, Object>>) listed.get("upi");
    assertThat(upi).hasSize(1);
    assertThat(upi.get(0).get("upi_handle")).isEqualTo("***@okaxis");
    assertThat(upi.get(0)).doesNotContainKey("upi_id");
  }

  @Test
  void listForAdmin_customerRole_unauthorized() {
    assertThatThrownBy(() -> service.listForAdmin(principal, customerId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void listForAdmin_nullPrincipal_unauthorized() {
    assertThatThrownBy(() -> service.listForAdmin(null, customerId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void listForAdmin_deletedCustomer_notFound() {
    var base = CustomerTestFixtures.customer(customerId);
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
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "a");

    assertThatThrownBy(() -> service.listForAdmin(admin, customerId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void idempotencyKey_blank_treatedAsAbsent() {
    Map<String, Object> created =
        service.saveUpi(principal, new UpiCommand("blankidem@okaxis", null), "   ");
    assertThat(created.get("type")).isEqualTo("UPI");
    assertThat(
            methods
                .findByIdForCustomer((UUID) created.get("id"), customerId)
                .orElseThrow()
                .idempotencyKey())
        .isNull();
  }

  @Test
  void idempotencyKey_tooLong() {
    assertThatThrownBy(
            () -> service.saveUpi(principal, new UpiCommand("aa@okaxis", null), "x".repeat(256)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
