package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.port.out.LoyaltyCartPort;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord;
import com.nammamedmate.customer.domain.LoyaltyTiers;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeLoyaltyStore;
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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoyaltyServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeLoyaltyStore store;
  private LoyaltyCartPort carts;
  private WalletService wallets;
  private InMemoryOutboxStore outbox;
  private LoyaltyService service;
  private UUID customerId;
  private MedmatePrincipal principal;
  private MedmatePrincipal adminSuper;
  private MedmatePrincipal adminOps;

  @BeforeEach
  void setUp() {
    store = new FakeLoyaltyStore();
    carts = mock(LoyaltyCartPort.class);
    wallets = mock(WalletService.class);
    when(wallets.systemCredit(any(), anyLong(), anyString(), any(), anyString(), anyString()))
        .thenReturn(Map.of());
    outbox = new InMemoryOutboxStore();
    service =
        new LoyaltyService(
            store,
            carts,
            wallets,
            new InMemoryRateLimiter(CLOCK),
            CLOCK,
            new OutboxPublisher(outbox, new ObjectMapper()));
    customerId = Ids.newId();
    principal = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    adminSuper =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    adminOps =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  @Test
  void getMyStatus_lifetime50_isGoldTowardPlatinum() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, LoyaltyTiers.GOLD, 50, 50, NOW));

    Map<String, Object> data = service.getMyStatus(principal);

    assertThat(data).containsEntry("tier", "GOLD").containsEntry("points_earned_lifetime", 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> progress = (Map<String, Object>) data.get("tier_progress");
    assertThat(progress).containsEntry("next_tier", "PLATINUM").containsEntry("points_needed", 70);
  }

  @Test
  void awardForDeliveredOrder_rs350_creditsThreePoints() {
    UUID orderId = Ids.newId();

    var tx =
        service
            .awardForDeliveredOrder(customerId, orderId, "ORD-20260720-00123", 35_000L)
            .orElseThrow();

    assertThat(tx.type()).isEqualTo(LoyaltyTxType.EARN);
    assertThat(tx.points()).isEqualTo(3);
    assertThat(tx.remainingPoints()).isEqualTo(3);
    assertThat(store.findByCustomerId(customerId))
        .get()
        .extracting(LoyaltyRecord::pointsEarnedLifetime, LoyaltyRecord::pointsBalance)
        .containsExactly(3, 3);
    assertThat(store.syncedPoints(customerId)).isEqualTo(3);
  }

  @Test
  void ac1_rs580_earnsFivePoints() {
    var tx = service.awardForDeliveredOrder(customerId, Ids.newId(), "O", 58_000L).orElseThrow();
    assertThat(tx.points()).isEqualTo(5);
  }

  @Test
  void reverse_keepsLifetimeAndTierRatchet() {
    UUID refunded = Ids.newId();
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, LoyaltyTiers.GOLD, 80, 80, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            40,
            80,
            "seed earn",
            refunded,
            NOW,
            NOW.plus(365, ChronoUnit.DAYS),
            40,
            null));

    service.reverseForRefundedOrder(customerId, refunded, "ORD-REF");

    LoyaltyRecord after = store.findByCustomerId(customerId).orElseThrow();
    assertThat(after.pointsEarnedLifetime()).isEqualTo(80);
    assertThat(after.pointsBalance()).isEqualTo(40);
    assertThat(after.tier()).isEqualTo("GOLD");
  }

  @Test
  void award_idempotentOnOrderId() {
    UUID orderId = Ids.newId();
    service.awardForDeliveredOrder(customerId, orderId, "O1", 35_000L);
    service.awardForDeliveredOrder(customerId, orderId, "O1", 35_000L);
    assertThat(store.allTransactions()).hasSize(1);
  }

  @Test
  void listMyTransactions_filtersEarn() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, "NONE", 0, 0, NOW));
    UUID o1 = Ids.newId();
    service.awardForDeliveredOrder(customerId, o1, "A", 35_000L);
    service.reverseForRefundedOrder(customerId, o1, "A");

    LoyaltyService.TxPage page = service.listMyTransactions(principal, 1, 20, "desc", "EARN");
    assertThat(page.data()).hasSize(1);
    assertThat(page.data().getFirst()).containsEntry("type", "EARN");
  }

  @Test
  void getMyStatus_unauthorized() {
    assertThatThrownBy(
            () ->
                service.getMyStatus(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void award_publishesTierChangeOutbox() {
    UUID orderId = Ids.newId();
    service.awardForDeliveredOrder(customerId, orderId, "T", 120_000L);
    assertThat(store.findByCustomerId(customerId).orElseThrow().tier()).isEqualTo("SILVER");
    assertThat(outbox.all()).isNotEmpty();
  }

  @Test
  void reverse_withoutEarn_isEmpty() {
    assertThat(service.reverseForRefundedOrder(customerId, Ids.newId(), "x")).isEmpty();
  }

  @Test
  void redeem_successAndErrors() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, "SILVER", 50, 50, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            50,
            50,
            "seed",
            Ids.newId(),
            NOW,
            NOW.plus(365, ChronoUnit.DAYS),
            50,
            null));
    UUID cartId = Ids.newId();
    when(carts.findCartItemTotalPaise(customerId, cartId)).thenReturn(Optional.of(20_000L));

    Map<String, Object> ok = service.redeem(principal, 20, cartId);
    assertThat(ok)
        .containsEntry("points_redeemed", 20)
        .containsEntry("wallet_credit_applied_rs", 20L);
    assertThat(ok).containsEntry("points_balance_after", 30);

    assertThatThrownBy(() -> service.redeem(principal, 5, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BELOW_MINIMUM_REDEMPTION");

    assertThatThrownBy(() -> service.redeem(principal, 50, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXCEEDS_REDEMPTION_CAP");

    when(carts.findCartItemTotalPaise(eq(customerId), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.redeem(principal, 10, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");

    when(carts.findCartItemTotalPaise(customerId, cartId)).thenReturn(Optional.of(100_000L));
    assertThatThrownBy(() -> service.redeem(principal, 40, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_POINTS");

    assertThatThrownBy(() -> service.redeem(principal, 10, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminAdjustAndProgramAndOverview() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, "NONE", 10, 10, NOW));
    Map<String, Object> adj = service.adminAdjust(adminSuper, customerId, 50, "Compensation", null);
    assertThat(adj).containsEntry("points_adjusted", 50).containsEntry("points_balance_after", 60);
    assertThat(store.allTransactions().stream().anyMatch(t -> t.type() == LoyaltyTxType.ADJUST))
        .isTrue();

    assertThatThrownBy(() -> service.adminAdjust(adminOps, customerId, 1, "x", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.adminAdjust(adminSuper, customerId, -100, "x", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADJUSTMENT_WOULD_EXCEED_BALANCE");

    assertThat(service.getProgram(adminOps)).containsEntry("earn_rate_rs_per_point", 100);
    Map<String, Object> patched =
        service.patchProgram(
            adminSuper,
            new LoyaltyService.PatchProgramCommand(100, null, null, null, null, 25, null, null));
    assertThat(patched).containsKeys("updated_at", "updated_by");
    assertThat(service.getProgram(adminSuper)).containsEntry("max_redemption_pct_per_order", 25);

    assertThat(service.adminOverview(adminSuper))
        .containsKeys("total_points_outstanding", "points_liability_rs");
  }

  @Test
  void expirePoints_writesExpireTx() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, "SILVER", 12, 12, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            12,
            12,
            "old",
            Ids.newId(),
            NOW.minus(400, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.DAYS),
            12,
            null));
    assertThat(service.expirePoints()).isEqualTo(12);
    assertThat(store.findByCustomerId(customerId).orElseThrow().pointsBalance()).isZero();
    assertThat(store.allTransactions().stream().anyMatch(t -> t.type() == LoyaltyTxType.EXPIRE))
        .isTrue();
  }

  @Test
  void awardAndReverse_edgeBranches() {
    assertThatThrownBy(() -> service.awardForDeliveredOrder(null, Ids.newId(), "x", 1000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.awardForDeliveredOrder(customerId, null, "x", 1000))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.awardForDeliveredOrder(customerId, Ids.newId(), "x", 50)).isEmpty();

    UUID orderId = Ids.newId();
    service.awardForDeliveredOrder(customerId, orderId, null, 35_000L);
    assertThat(store.allTransactions().getFirst().description()).contains(orderId.toString());
    service.awardForDeliveredOrder(customerId, Ids.newId(), "  ", 35_000L);

    store.failNextInsertTx = true;
    UUID raceOrder = Ids.newId();
    store.revealAfterFailedInsert =
        new LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.EARN, 3, 3, "won", raceOrder, NOW);
    assertThat(service.awardForDeliveredOrder(customerId, raceOrder, "r", 35_000L)).isPresent();

    assertThatThrownBy(() -> service.reverseForRefundedOrder(null, orderId, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reverseForRefundedOrder(customerId, null, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.reverseForRefundedOrder(customerId, orderId, null);
    assertThat(service.reverseForRefundedOrder(customerId, orderId, "again")).isPresent();

    UUID blankRev = Ids.newId();
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            1,
            1,
            "e2",
            blankRev,
            NOW,
            NOW.plus(1, ChronoUnit.DAYS),
            1,
            null));
    service.reverseForRefundedOrder(customerId, blankRev, "  ");

    UUID revOrder = Ids.newId();
    store.insertTransaction(
        new LoyaltyTxRecord(Ids.newId(), customerId, LoyaltyTxType.EARN, 2, 2, "e", revOrder, NOW));
    store.failNextInsertTx = true;
    store.revealAfterFailedInsert =
        new LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.REVERSE, -2, 0, "won-rev", revOrder, NOW);
    assertThat(service.reverseForRefundedOrder(customerId, revOrder, "x")).isPresent();
  }

  @Test
  void createDefault_raceAndRateLimitAndNullPrincipal() {
    store.failNextInsert = true;
    store.revealAfterFailedLoyaltyInsert =
        new LoyaltyRecord(Ids.newId(), customerId, "NONE", 0, 0, NOW);
    assertThat(service.adminLoyaltySummary(customerId)).containsEntry("tier", "NONE");

    store.failNextInsert = true;
    UUID missing = Ids.newId();
    assertThatThrownBy(() -> service.adminLoyaltySummary(missing))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    assertThatThrownBy(() -> service.getMyStatus(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    InMemoryRateLimiter limited = new InMemoryRateLimiter(CLOCK);
    LoyaltyService tight =
        new LoyaltyService(
            store, carts, wallets, limited, CLOCK, new OutboxPublisher(outbox, new ObjectMapper()));
    for (int i = 0; i < 30; i++) {
      tight.getMyStatus(principal);
    }
    assertThatThrownBy(() -> tight.getMyStatus(principal))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");

    store.failSettings = true;
    assertThat(service.getProgram(adminSuper)).containsEntry("earn_rate_rs_per_point", 100);
  }

  @Test
  void coverageFill_remainingBranches() {
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, "NONE", 10, 10, NOW));
    service.listMyTransactions(principal, 1, 10, null, null);
    service.listMyTransactions(principal, 1, 10, "  ", null);

    UUID revZeroRem = Ids.newId();
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            2,
            10,
            "e",
            revZeroRem,
            NOW,
            NOW.plus(1, ChronoUnit.DAYS),
            0,
            null));
    service.reverseForRefundedOrder(customerId, revZeroRem, "shown");
    UUID revNullRem = Ids.newId();
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.EARN, 1, 8, "n", revNullRem, NOW));
    service.reverseForRefundedOrder(customerId, revNullRem, "nullrem");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    null,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, BigDecimal.ZERO, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    service.patchProgram(
        adminSuper,
        new LoyaltyService.PatchProgramCommand(
            null, Double.valueOf("1.25"), null, null, null, null, 15, 400));
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, 12, 50, 40, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, null, 0, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, null, null, -1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        0, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, -5, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, 101, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.adminAdjust(adminSuper, customerId, 1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.adminAdjust(adminSuper, customerId, 1, "   ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.adminAdjust(adminSuper, null, 1, "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.adminAdjust(adminSuper, customerId, 0, "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.adminAdjust(adminSuper, customerId, 3, "credit", null);
    // debit with no open earn batches (empty FIFO loop)
    UUID lonely = Ids.newId();
    store.insert(new LoyaltyRecord(Ids.newId(), lonely, "NONE", 5, 5, NOW));
    MedmatePrincipal lonelyPrincipal =
        new MedmatePrincipal(lonely, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    service.adminAdjust(adminSuper, lonely, -2, "debit-no-batches", null);

    LoyaltyRecord cur = store.findByCustomerId(customerId).orElseThrow();
    store.update(new LoyaltyRecord(cur.id(), customerId, "SILVER", 30, 30, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            10,
            10,
            "nullrem",
            Ids.newId(),
            NOW.minus(3, ChronoUnit.DAYS),
            NOW.plus(10, ChronoUnit.DAYS),
            null,
            null));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            20,
            20,
            "ok",
            Ids.newId(),
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(10, ChronoUnit.DAYS),
            20,
            null));
    service.adminAdjust(adminSuper, customerId, -8, "debit", Ids.newId());

    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThat(service.adminOverview(finance)).containsKey("total_points_outstanding");
    assertThatThrownBy(() -> service.getProgram(principal))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getProgram(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminOps,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, null, null, null, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminSuper,
                    new LoyaltyService.PatchProgramCommand(
                        null, null, 50, 12, 120, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // expire with positive remaining (non-null) and debit<=0 when balance already 0
    UUID other2 = Ids.newId();
    store.insert(new LoyaltyRecord(Ids.newId(), other2, "NONE", 0, 0, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            other2,
            LoyaltyTxType.EARN,
            3,
            3,
            "exp2",
            Ids.newId(),
            NOW.minus(10, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.DAYS),
            3,
            null));
    assertThat(service.expirePoints()).isZero();

    UUID other = Ids.newId();
    store.insert(new LoyaltyRecord(Ids.newId(), other, "NONE", 4, 4, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            other,
            LoyaltyTxType.EARN,
            4,
            4,
            "exp",
            Ids.newId(),
            NOW.minus(10, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.DAYS),
            null,
            null));
    assertThat(service.expirePoints()).isEqualTo(4);

    UUID cartId = Ids.newId();
    when(carts.findCartItemTotalPaise(customerId, cartId)).thenReturn(Optional.of(100_000L));
    cur = store.findByCustomerId(customerId).orElseThrow();
    store.update(new LoyaltyRecord(cur.id(), customerId, "SILVER", 25, 25, NOW));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            10,
            10,
            "a",
            Ids.newId(),
            NOW.minus(5, ChronoUnit.DAYS),
            NOW.plus(10, ChronoUnit.DAYS),
            10,
            null));
    store.insertTransaction(
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            20,
            20,
            "b",
            Ids.newId(),
            NOW.minus(4, ChronoUnit.DAYS),
            NOW.plus(10, ChronoUnit.DAYS),
            20,
            null));
    service.redeem(principal, 15, cartId);
    service.adminAdjust(adminSuper, customerId, 1, "r".repeat(300), null);
  }
}
