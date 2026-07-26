package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoyaltyServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeLoyaltyStore store;
  private InMemoryOutboxStore outbox;
  private LoyaltyService service;
  private UUID customerId;
  private MedmatePrincipal principal;

  @BeforeEach
  void setUp() {
    store = new FakeLoyaltyStore();
    outbox = new InMemoryOutboxStore();
    service =
        new LoyaltyService(
            store,
            new InMemoryRateLimiter(CLOCK),
            CLOCK,
            new OutboxPublisher(outbox, new ObjectMapper()));
    customerId = Ids.newId();
    principal = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
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
    assertThat(store.findByCustomerId(customerId))
        .get()
        .extracting(LoyaltyRecord::pointsEarnedLifetime, LoyaltyRecord::pointsBalance)
        .containsExactly(3, 3);
    assertThat(store.syncedPoints(customerId)).isEqualTo(3);
  }

  @Test
  void reverse_keepsLifetimeAndTierRatchet() {
    UUID refunded = Ids.newId();
    store.insert(new LoyaltyRecord(Ids.newId(), customerId, LoyaltyTiers.GOLD, 80, 80, NOW));
    store.insertTransaction(
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.EARN, 40, 80, "seed earn", refunded, NOW));

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
    // 12 pts need Rs 1,200 → 120_000 paise
    service.awardForDeliveredOrder(customerId, orderId, "T", 120_000L);
    assertThat(store.findByCustomerId(customerId).orElseThrow().tier()).isEqualTo("SILVER");
    assertThat(outbox.all()).isNotEmpty();
  }

  @Test
  void reverse_withoutEarn_isEmpty() {
    assertThat(service.reverseForRefundedOrder(customerId, Ids.newId(), "x")).isEmpty();
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
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord(
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
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.EARN, 1, 1, "e2", blankRev, NOW));
    service.reverseForRefundedOrder(customerId, blankRev, "  ");

    UUID revOrder = Ids.newId();
    store.insertTransaction(
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord(
            Ids.newId(), customerId, LoyaltyTxType.EARN, 2, 2, "e", revOrder, NOW));
    store.failNextInsertTx = true;
    store.revealAfterFailedInsert =
        new com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord(
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

    for (int i = 0; i < 30; i++) {
      service.getMyStatus(principal);
    }
    assertThatThrownBy(() -> service.getMyStatus(principal))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");

    service.listMyTransactions(principal, 1, 20, null, null);
    service.listMyTransactions(principal, 1, 20, "  ", null);
  }

  @Test
  void truncate_longDescription() {
    String longId = "X".repeat(300);
    service.awardForDeliveredOrder(customerId, Ids.newId(), longId, 35_000L);
    assertThat(store.allTransactions().getFirst().description()).hasSize(255);
  }
}
