package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.AdminCustomerService.AdminListResult;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminCustomerServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeCustomerProfileStore store;
  private InMemoryRateLimiter rateLimiter;
  private InMemoryOutboxStore outboxStore;
  private AdminCustomerService service;
  private MedmatePrincipal admin;
  private UUID vipId;
  private UUID regularId;

  @BeforeEach
  void setUp() {
    store = new FakeCustomerProfileStore();
    rateLimiter = new InMemoryRateLimiter(CLOCK);
    outboxStore = new InMemoryOutboxStore();
    service =
        new AdminCustomerService(
            store, rateLimiter, new OutboxPublisher(outboxStore, new ObjectMapper()), CLOCK);
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

    vipId = Ids.newId();
    regularId = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customerWith(vipId, "VIP", 55, 3_000_000L, false));
    store.saveProfile(CustomerTestFixtures.customerWith(regularId, "REGULAR", 5, 100_000L, true));
  }

  @Test
  void adminList_withSegmentVipAndIsFlaggedFalse_filtersCorrectly() {
    AdminListResult result =
        service.list(admin, 1, 20, null, null, null, "VIP", false, null, false);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.data();
    assertThat(items).hasSize(1);
    assertThat(items.getFirst()).containsEntry("segment", "VIP").containsEntry("is_flagged", false);
    assertThat(result.meta()).isNotNull();
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void flag_withReasonOtherAndNoNote_returnsValidationErrorMentioningNote() {
    assertThatThrownBy(() -> service.flag(admin, vipId, "OTHER", null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(app.getMessage()).contains("note");
            });
  }

  @Test
  void notify_fourthTimeWithin24h_returnsNotificationRateLimited() {
    Instant since = NOW.minusSeconds(3600);
    for (int i = 0; i < 3; i++) {
      store.insertNotification(
          Ids.newId(),
          vipId,
          "SMS",
          null,
          "body " + i,
          null,
          admin.subject(),
          since.plusSeconds(i));
    }

    assertThatThrownBy(() -> service.notify(admin, vipId, "SMS", null, "fourth", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTIFICATION_RATE_LIMITED");
  }

  @Test
  void list_exportTrue_returnsStubExportUrl() {
    AdminListResult result =
        service.list(admin, null, null, null, null, null, null, null, null, true);

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) result.data();
    assertThat(data.get("export_url").toString()).contains(admin.subject().toString());
    assertThat(data).containsKey("expires_at");
    assertThat(result.meta()).isNull();
  }

  @Test
  void notify_smsWithoutTitle_succeeds() {
    Map<String, Object> data = service.notify(admin, vipId, "SMS", null, "hello", null);

    assertThat(data).containsEntry("channel", "SMS").containsEntry("delivered", false);
    assertThat(outboxStore.all()).hasSize(1);
    assertThat(outboxStore.all().getFirst().type()).isEqualTo("customer.notification.requested");
    assertThat(outboxStore.all().getFirst().payloadJson()).doesNotContain("phone");
    assertThat(outboxStore.all().getFirst().payloadJson()).contains("customer_id");
  }

  @Test
  void notify_pushWithoutTitle_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "PUSH", null, "hello", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_bothWithoutTitle_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "BOTH", " ", "hello", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void flag_success_marksCustomerFlagged() {
    Map<String, Object> data = service.flag(admin, vipId, "FRAUD_SUSPICION", "suspicious");

    assertThat(data)
        .containsEntry("is_flagged", true)
        .containsEntry("flag_reason", "FRAUD_SUSPICION");
    assertThat(store.findById(vipId).orElseThrow().isFlagged()).isTrue();
  }

  @Test
  void flag_alreadyFlagged_returnsConflict() {
    assertThatThrownBy(() -> service.flag(admin, regularId, "OTHER", "note"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_FLAGGED");
  }

  @Test
  void unflag_success_clearsFlag() {
    Map<String, Object> data = service.unflag(admin, regularId);

    assertThat(data).containsEntry("is_flagged", false);
    assertThat(store.findById(regularId).orElseThrow().isFlagged()).isFalse();
  }

  @Test
  void unflag_notFlagged_returnsConflict() {
    assertThatThrownBy(() -> service.unflag(admin, vipId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_FLAGGED");
  }

  @Test
  void get_customerNotFound_returns404() {
    assertThatThrownBy(() -> service.get(admin, Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void get_detail_includesOrderStatsWalletAndLoyalty() {
    Map<String, Object> detail = service.get(admin, vipId);

    assertThat(detail).containsKeys("order_stats", "wallet", "loyalty");
    @SuppressWarnings("unchecked")
    Map<String, Object> orderStats = (Map<String, Object>) detail.get("order_stats");
    assertThat(orderStats).containsEntry("total_orders", 55);
    @SuppressWarnings("unchecked")
    Map<String, Object> wallet = (Map<String, Object>) detail.get("wallet");
    assertThat(wallet.get("balance")).isEqualTo(new BigDecimal("125.00"));
  }

  @Test
  void get_detail_withNullCancelRateAndZeroOrders() {
    UUID zeroId = Ids.newId();
    CustomerProfileRecord zero =
        new CustomerProfileRecord(
            zeroId,
            "+910000000001",
            "Zero",
            null,
            null,
            null,
            "en",
            "NEW",
            null,
            false,
            null,
            null,
            null,
            null,
            0L,
            10,
            0,
            0L,
            null,
            0,
            null,
            null,
            null,
            NOW,
            NOW,
            null);
    store.saveProfile(zero);

    Map<String, Object> detail = service.get(admin, zeroId);
    @SuppressWarnings("unchecked")
    Map<String, Object> orderStats = (Map<String, Object>) detail.get("order_stats");
    assertThat(orderStats)
        .containsEntry("cancelled_orders", 0)
        .containsEntry("completed_orders", 0)
        .containsEntry("avg_order_value", new BigDecimal("0.00"));
  }

  @Test
  void list_invalidSort_returnsValidationError() {
    assertThatThrownBy(() -> service.list(admin, 1, 20, "bad", null, null, null, null, null, false))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void list_nullPrincipal_returnsUnauthorized() {
    assertThatThrownBy(() -> service.list(null, 1, 20, null, null, null, null, null, null, false))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void flag_invalidReason_returnsValidationError() {
    assertThatThrownBy(() -> service.flag(admin, vipId, "bogus", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_nullBody_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "SMS", null, null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_bothWithTitleAndDeepLink_succeeds() {
    Map<String, Object> data =
        service.notify(admin, vipId, "BOTH", "  Promo  ", "Body", "medmate://promo");

    assertThat(data).containsEntry("channel", "BOTH").containsEntry("delivered", false);
  }

  @Test
  void flag_nonOtherReason_longNoteStillValidated() {
    assertThatThrownBy(() -> service.flag(admin, vipId, "FRAUD_SUSPICION", "n".repeat(501)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void flag_noteTooLong_returnsValidationError() {
    assertThatThrownBy(() -> service.flag(admin, vipId, "OTHER", "n".repeat(501)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_invalidChannel_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "email", "t", "body", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_blankBody_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "SMS", null, "  ", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_deepLinkInvalidScheme_returnsValidationError() {
    assertThatThrownBy(
            () -> service.notify(admin, vipId, "SMS", null, "ok", "https://evil.example/"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_deepLinkTooLong_returnsValidationError() {
    assertThatThrownBy(
            () -> service.notify(admin, vipId, "SMS", null, "ok", "medmate://x/" + "a".repeat(505)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_fourthCallViaService_hitsDbCap() {
    for (int i = 0; i < 3; i++) {
      service.notify(admin, vipId, "SMS", null, "msg " + i, null);
    }

    assertThatThrownBy(() -> service.notify(admin, vipId, "SMS", null, "blocked", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTIFICATION_RATE_LIMITED");
  }

  @Test
  void list_defaultOrderIsDesc() {
    AdminListResult result = service.list(admin, 1, 20, null, null, null, null, null, null, false);

    assertThat(result.meta()).isNotNull();
  }

  @Test
  void list_blankOrder_defaultsToDesc() {
    AdminListResult result = service.list(admin, 1, 20, null, "  ", null, null, null, null, false);

    assertThat(result.meta()).isNotNull();
  }

  @Test
  void list_explicitAscOrder_accepted() {
    AdminListResult result = service.list(admin, 1, 20, null, "asc", null, null, null, null, false);

    assertThat(result.meta()).isNotNull();
  }

  @Test
  void notify_blankDeepLink_treatedAsAbsent() {
    Map<String, Object> data = service.notify(admin, vipId, "SMS", null, "hello", "   ");

    assertThat(data).containsEntry("delivered", false);
  }

  @Test
  void get_complianceRole_returnsForbidden() {
    MedmatePrincipal compliance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.get(compliance, vipId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void toListItem_staticHelper() {
    Map<String, Object> item =
        AdminCustomerService.toListItem(CustomerTestFixtures.customer(vipId));
    assertThat(item).containsEntry("id", vipId).containsKey("total_ltv");
  }

  @Test
  void list_withBlankSort_defaultsCreatedAt() {
    AdminListResult result = service.list(admin, 1, 20, "  ", null, "  ", null, null, "  ", false);

    assertThat(result.meta()).isNotNull();
  }

  @Test
  void list_rateLimited_afterBurst() {
    for (int i = 0; i < 30; i++) {
      service.list(admin, 1, 20, null, null, null, null, null, null, false);
    }

    assertThatThrownBy(() -> service.list(admin, 1, 20, null, null, null, null, null, null, false))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void flag_otherWithBlankNoteAfterTrim_returnsValidationError() {
    assertThatThrownBy(() -> service.flag(admin, vipId, "OTHER", "   "))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void flag_otherWithNote_succeeds() {
    Map<String, Object> data = service.flag(admin, vipId, "OTHER", "manual review");

    assertThat(data).containsEntry("flag_reason", "OTHER");
  }

  @Test
  void notify_bodyTooLong_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "SMS", null, "x".repeat(256), null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_pushTitleTooLong_returnsValidationError() {
    assertThatThrownBy(() -> service.notify(admin, vipId, "PUSH", "t".repeat(66), "body", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void notify_smsWithEmptyTitle_storesNullTitle() {
    Map<String, Object> data = service.notify(admin, vipId, "SMS", "", "hello", null);

    assertThat(data).containsEntry("channel", "SMS");
  }

  @Test
  void notify_pushWithWhitespaceTitle_trimsForPayload() {
    Map<String, Object> data =
        service.notify(admin, vipId, "PUSH", "  Hi  ", "Body", "medmate://home");

    assertThat(data).containsEntry("channel", "PUSH").containsEntry("delivered", false);
    assertThat(outboxStore.all()).hasSize(1);
  }

  @Test
  void flag_noteAtMaxLength_succeeds() {
    Map<String, Object> data = service.flag(admin, vipId, "OTHER", "n".repeat(500));

    assertThat(data).containsEntry("is_flagged", true);
  }

  @Test
  void flag_nullNoteOnNonOtherReason_skipsLengthCheck() {
    Map<String, Object> data = service.flag(admin, vipId, "FRAUD_SUSPICION", null);

    assertThat(data).containsEntry("is_flagged", true);
  }

  @Test
  void get_deletedCustomer_returns404() {
    UUID deletedId = Ids.newId();
    store.saveProfile(
        new CustomerProfileRecord(
            deletedId,
            "+910000000099",
            "Gone",
            null,
            null,
            null,
            "en",
            "NEW",
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
            null,
            0,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW));

    assertThatThrownBy(() -> service.get(admin, deletedId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void get_detail_withCancelRate_computesOrderStats() {
    Map<String, Object> detail = service.get(admin, vipId);
    @SuppressWarnings("unchecked")
    Map<String, Object> orderStats = (Map<String, Object>) detail.get("order_stats");
    assertThat(orderStats.get("cancelled_orders")).isEqualTo(6);
    assertThat(orderStats.get("completed_orders")).isEqualTo(49);
    assertThat(orderStats.get("avg_order_value")).isEqualTo(new BigDecimal("545.45"));
  }
}
