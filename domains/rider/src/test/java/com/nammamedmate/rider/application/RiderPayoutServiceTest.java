package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.rider.adapter.out.client.StubRazorpayRouteAdapter;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore.PayoutRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.LifetimeTotals;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.PeriodTotals;
import com.nammamedmate.rider.domain.PayoutCycle;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderPayoutServiceTest {

  /** Monday 2026-07-27 00:30 IST = 2026-07-26 19:00 UTC */
  private static final Instant MONDAY_MORNING = Instant.parse("2026-07-26T19:00:00Z");

  private FakeRiders riders;
  private FakeEarnings earnings;
  private FakePayouts payouts;
  private StubRazorpayRouteAdapter razorpay;
  private InMemoryOutboxStore outbox;
  private RiderPayoutService service;
  private UUID riderId;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    earnings = new FakeEarnings();
    payouts = new FakePayouts();
    razorpay = new StubRazorpayRouteAdapter();
    outbox = new InMemoryOutboxStore();
    Clock clock = Clock.fixed(MONDAY_MORNING, ZoneOffset.UTC);
    service =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            razorpay,
            cfg(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
    riderId = Ids.newId();
    riders.insert(rider(riderId, 0L, 0));
  }

  @Test
  void ac003_weeklyComputeCreatesPayoutForPreviousCycle() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 15_000L, 0L, 0L);
    int n = service.computeWeeklyPayouts();
    assertThat(n).isEqualTo(1);
    PayoutRecord p = payouts.byRider.values().iterator().next();
    assertThat(p.cycleFrom()).isEqualTo(prev.from());
    assertThat(p.cycleTo()).isEqualTo(prev.to());
    assertThat(p.status()).isEqualTo("RELEASED");
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("payout_released"));
  }

  @Test
  void ac002_streakBonusIncludedWhenPending() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 20_000L, 0L, 0L);
    riders.streakPending.put(riderId, true);
    service.computeWeeklyPayouts();
    PayoutRecord p = payouts.byRider.values().iterator().next();
    assertThat(p.streakBonusPaise()).isEqualTo(10_000L);
    assertThat(p.netPayoutPaise()).isEqualTo(30_000L);
  }

  @Test
  void ac004_codFloatRiskHoldsPayout() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 50_000L, 0L, 0L);
    riders.cod.put(riderId, 210_000L);
    riders.updateCod(riderId, 210_000L);
    service.computeWeeklyPayouts();
    PayoutRecord p = payouts.byRider.values().iterator().next();
    assertThat(p.status()).isEqualTo("HELD");
    assertThat(p.holdReason()).contains("COD");
  }

  @Test
  void ac005_belowThresholdCarriedForward() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 5_000L, 0L, 0L);
    service.computeWeeklyPayouts();
    PayoutRecord p = payouts.byRider.values().iterator().next();
    assertThat(p.status()).isEqualTo("BELOW_THRESHOLD_CARRIED_FORWARD");
    assertThat(riders.carry.getOrDefault(riderId, 0L)).isEqualTo(5_000L);
  }

  @Test
  void ac006_manualReleaseTriggersRazorpayAndSms() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    // Gross 250000 - COD 210000 = net 40000 (≥ ₹100) while float still HELD.
    seedTrip(prev.from(), 250_000L, 0L, 0L);
    riders.updateCod(riderId, 210_000L);
    service.computeWeeklyPayouts();
    PayoutRecord held = payouts.byRider.values().iterator().next();
    assertThat(held.status()).isEqualTo("HELD");
    assertThat(held.netPayoutPaise()).isEqualTo(40_000L);
    riders.updateCod(riderId, 0L);
    Map<String, Object> data =
        service.release(finance(), riderId, held.id(), "COD cleared", "idem-ac006");
    assertThat(data.get("payout_status")).isEqualTo("RELEASED");
    assertThat(data.get("razorpay_payout_id")).isNotNull();
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("payout_released"));
  }

  @Test
  void ac007_failedPayoutRetriedOnceThenFailed() {
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 50_000L, 0L, 0L);
    razorpay.failNext(true);
    service.computeWeeklyPayouts();
    PayoutRecord pending = payouts.byRider.values().iterator().next();
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.nextRetryAt()).isNotNull();
    Instant later = pending.nextRetryAt().plusSeconds(1);
    RiderPayoutService retrySvc =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            razorpay,
            cfg(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            Clock.fixed(later, ZoneOffset.UTC));
    razorpay.failNext(true);
    retrySvc.retryDuePayouts();
    PayoutRecord failed = payouts.byId.get(pending.id());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("payout_failed"));
  }

  @Test
  void releaseErrors() {
    assertThatThrownBy(() -> service.release(finance(), riderId, null, null, "   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.release(finance(), riderId, null, null, "idem-missing-payout"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");
    UUID id = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            id,
            riderId,
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19),
            5_000,
            0,
            0,
            0,
            0,
            0,
            5_000,
            "BELOW_THRESHOLD_CARRIED_FORWARD",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    assertThatThrownBy(() -> service.release(finance(), riderId, id, "x", "idem-below"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");
  }

  @Test
  void releaseRequiresIdempotencyKeyAndReplays() {
    assertThatThrownBy(() -> service.release(finance(), riderId, Ids.newId(), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.release(finance(), riderId, Ids.newId(), null, "x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 250_000L, 0L, 0L);
    riders.updateCod(riderId, 210_000L);
    service.computeWeeklyPayouts();
    PayoutRecord held = payouts.byRider.values().iterator().next();
    riders.updateCod(riderId, 0L);

    Map<String, Object> first = service.release(finance(), riderId, held.id(), "ok", "idem-replay");
    assertThat(first.get("payout_status")).isEqualTo("RELEASED");
    long walletAfter = riders.wallet.getOrDefault(riderId, 0L);
    Map<String, Object> replay =
        service.release(finance(), riderId, held.id(), "ok", "idem-replay");
    assertThat(replay.get("payout_status")).isEqualTo("RELEASED");
    assertThat(riders.wallet.getOrDefault(riderId, 0L)).isEqualTo(walletAfter);

    UUID other = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            other,
            riderId,
            LocalDate.of(2026, 7, 6),
            LocalDate.of(2026, 7, 12),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    assertThatThrownBy(() -> service.release(finance(), riderId, other, "n", "idem-replay"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThatThrownBy(() -> service.release(finance(), Ids.newId(), held.id(), "n", "idem-replay"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

    payouts.claimed.add(other);
    assertThatThrownBy(() -> service.release(finance(), riderId, other, "n", "idem-claim-conflict"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_CONFLICT");
  }

  @Test
  void releaseClaimThenMissingRow() {
    UUID id = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            id,
            riderId,
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    payouts.vanishAfterClaim = true;
    assertThatThrownBy(() -> service.release(finance(), riderId, id, "n", "idem-vanish"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");
  }

  @Test
  void releaseClaimRaceReplaysExistingKey() {
    UUID id = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            id,
            riderId,
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    // Key already claimed elsewhere; first lookup misses (TOCTOU), claim fails, second lookup
    // replays.
    payouts.byIdempotency.put("idem-race", id);
    payouts.raceClaimMissOnce = true;
    Map<String, Object> data = service.release(finance(), riderId, id, "n", "idem-race");
    assertThat(data.get("payout_status")).isEqualTo("HELD");
  }

  @Test
  void alreadyReleasedAndCodUnresolved() {
    UUID id = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            id,
            riderId,
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "RELEASED",
            null,
            "pout_x",
            "RPX",
            null,
            finance().subject(),
            MONDAY_MORNING,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    assertThatThrownBy(() -> service.release(finance(), riderId, id, null, "idem-released"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_ALREADY_RELEASED");

    UUID heldId = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            heldId,
            riderId,
            LocalDate.of(2026, 7, 6),
            LocalDate.of(2026, 7, 12),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            MONDAY_MORNING,
            MONDAY_MORNING));
    riders.updateCod(riderId, 250_000L);
    assertThatThrownBy(() -> service.release(finance(), riderId, heldId, "no", "idem-cod"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COD_UNRESOLVED");
  }

  @Test
  void attemptDisburseUsesProviderOpsLedger() {
    ProviderOperationStore ops = mock(ProviderOperationStore.class);
    when(ops.find(eq("PAYOUT"), anyString())).thenReturn(Optional.empty());
    RiderPayoutService withOps =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            razorpay,
            cfg(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            Clock.fixed(MONDAY_MORNING, ZoneOffset.UTC),
            ops);
    PayoutCycle.Window prev = PayoutCycle.previous(MONDAY_MORNING);
    seedTrip(prev.from(), 15_000L, 0L, 0L);
    withOps.computeWeeklyPayouts();
    PayoutRecord released = payouts.byRider.values().iterator().next();
    assertThat(released.status()).isEqualTo("RELEASED");
    verify(ops).ensurePending(eq("PAYOUT"), anyString(), eq("razorpay"));
    verify(ops).markSent(eq("PAYOUT"), anyString(), anyString());

    payouts = new FakePayouts();
    earnings = new FakeEarnings();
    seedTrip(prev.from(), 18_000L, 0L, 0L);
    when(ops.find(eq("PAYOUT"), anyString()))
        .thenReturn(
            Optional.of(
                new ProviderOperationStore.Operation("PAYOUT", "k", "pout_replay", "SENT")));
    razorpay.failNext(true);
    RiderPayoutService replaySvc =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            razorpay,
            cfg(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            Clock.fixed(MONDAY_MORNING, ZoneOffset.UTC),
            ops);
    replaySvc.computeWeeklyPayouts();
    PayoutRecord replayed = payouts.byRider.values().iterator().next();
    assertThat(replayed.status()).isEqualTo("RELEASED");
    assertThat(replayed.razorpayPayoutId()).isEqualTo("pout_replay");

    payouts = new FakePayouts();
    earnings = new FakeEarnings();
    seedTrip(prev.from(), 20_000L, 0L, 0L);
    when(ops.find(eq("PAYOUT"), anyString())).thenReturn(Optional.empty());
    razorpay.failNext(true);
    RiderPayoutService failSvc =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            razorpay,
            cfg(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            Clock.fixed(MONDAY_MORNING, ZoneOffset.UTC),
            ops);
    failSvc.computeWeeklyPayouts();
    assertThat(payouts.byRider.values().iterator().next().status()).isEqualTo("PENDING");
    verify(ops, never()).markSent(eq("PAYOUT"), eq("unused"), anyString());
  }

  private void seedTrip(LocalDate date, long base, long tip, long incentive) {
    earnings.insert(
        new EarningsRecord(
            Ids.newId(),
            riderId,
            Ids.newId(),
            Ids.newId(),
            date,
            base,
            tip,
            incentive,
            base + tip + incentive,
            true,
            5,
            BigDecimal.valueOf(2.0),
            10,
            MONDAY_MORNING));
    riders.wallet.put(riderId, base + tip + incentive);
  }

  private MedmatePrincipal finance() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  private static RiderRecord rider(UUID id, long cod, int streak) {
    Instant t = MONDAY_MORNING;
    return new RiderRecord(
        id,
        "Ravi",
        "9000000001",
        null,
        "BIKE",
        "KA01AB1234",
        null,
        "ACTIVE",
        "APPROVED",
        null,
        null,
        null,
        null,
        null,
        true,
        BigDecimal.valueOf(4.7),
        10,
        BigDecimal.valueOf(90),
        0,
        cod,
        streak,
        null,
        null,
        null,
        t,
        t);
  }

  private static PlatformPricingConfigStore cfg() {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        return switch (key) {
          case "cod_float_limit_default" -> Optional.of("200000");
          default -> Optional.empty();
        };
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(
          String key, String value, String description, UUID updatedBy, Instant now) {}
    };
  }

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();
    final Map<UUID, Long> carry = new ConcurrentHashMap<>();
    final Map<UUID, Long> cod = new ConcurrentHashMap<>();
    final Map<UUID, Long> wallet = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> streakPending = new ConcurrentHashMap<>();
    final Map<UUID, LocalDate> lastDelivery = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
      cod.put(rider.id(), rider.codInHandPaise());
    }

    void updateCod(UUID id, long paise) {
      cod.put(id, paise);
      RiderRecord r = byId.get(id);
      byId.put(
          id,
          new RiderRecord(
              r.id(),
              r.name(),
              r.phone(),
              r.email(),
              r.vehicleType(),
              r.vehiclePlateNumber(),
              r.primaryZoneId(),
              r.status(),
              r.kycStatus(),
              r.kycSubmittedAt(),
              r.kycReviewedAt(),
              r.kycReviewedBy(),
              r.kycRejectionReason(),
              r.kycRejectionNotes(),
              r.aadhaarVerified(),
              r.avgRating(),
              r.totalTrips(),
              r.onTimePct(),
              r.earningsWalletBalancePaise(),
              paise,
              r.dailyStreakDays(),
              r.blockedReason(),
              r.blockedBy(),
              r.blockedAt(),
              r.createdAt(),
              r.updatedAt()));
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return Optional.empty();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return false;
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}

    @Override
    public long adjustEarningsWallet(UUID id, long deltaPaise, Instant updatedAt) {
      long v = wallet.getOrDefault(id, 0L) + deltaPaise;
      wallet.put(id, v);
      return v;
    }

    @Override
    public long payoutCarryForwardPaise(UUID id) {
      return carry.getOrDefault(id, 0L);
    }

    @Override
    public void setPayoutCarryForward(UUID id, long paise, Instant updatedAt) {
      carry.put(id, paise);
    }

    @Override
    public boolean streakBonusPending(UUID id) {
      return streakPending.getOrDefault(id, false);
    }

    @Override
    public void clearStreakBonusPending(UUID id, Instant updatedAt) {
      streakPending.put(id, false);
    }

    @Override
    public List<UUID> listIdsForPayoutCompute() {
      return new ArrayList<>(byId.keySet());
    }
  }

  static final class FakeEarnings implements RiderTripEarningsStore {
    final List<EarningsRecord> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(EarningsRecord row) {
      rows.add(row);
    }

    @Override
    public PeriodTotals sumForRider(UUID riderId, LocalDate from, LocalDate to) {
      long base = 0, tip = 0, inc = 0, total = 0;
      int trips = 0;
      for (EarningsRecord r : rows) {
        if (!r.riderId().equals(riderId)) {
          continue;
        }
        if (r.deliveryDate().isBefore(from) || r.deliveryDate().isAfter(to)) {
          continue;
        }
        base += r.basePayPaise();
        tip += r.tipPaise();
        inc += r.incentiveBonusPaise();
        total += r.totalPaise();
        trips++;
      }
      return new PeriodTotals(base, inc, tip, total, trips);
    }

    @Override
    public LifetimeTotals lifetime(UUID riderId) {
      return new LifetimeTotals(0, 0);
    }

    @Override
    public List<UUID> distinctRidersWithEarnings(LocalDate from, LocalDate to) {
      return rows.stream().map(EarningsRecord::riderId).distinct().toList();
    }
  }

  static final class FakePayouts implements RiderPayoutStore {
    final Map<UUID, PayoutRecord> byId = new ConcurrentHashMap<>();
    final Map<UUID, PayoutRecord> byRider = new ConcurrentHashMap<>();
    final Map<String, UUID> byIdempotency = new ConcurrentHashMap<>();
    final java.util.Set<UUID> claimed = ConcurrentHashMap.newKeySet();
    boolean raceClaimMissOnce;
    boolean vanishAfterClaim;

    @Override
    public void insert(PayoutRecord row) {
      byId.put(row.id(), row);
      byRider.put(row.riderId(), row);
    }

    @Override
    public void update(PayoutRecord row) {
      byId.put(row.id(), row);
      byRider.put(row.riderId(), row);
    }

    @Override
    public Optional<PayoutRecord> findById(UUID id) {
      if (vanishAfterClaim && claimed.contains(id)) {
        return Optional.empty();
      }
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        return Optional.empty();
      }
      if (raceClaimMissOnce) {
        raceClaimMissOnce = false;
        return Optional.empty();
      }
      UUID id = byIdempotency.get(idempotencyKey);
      return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean claimForRelease(
        UUID payoutId, UUID riderId, String idempotencyKey, Instant updatedAt) {
      PayoutRecord p = byId.get(payoutId);
      if (p == null || !p.riderId().equals(riderId)) {
        return false;
      }
      if (byIdempotency.containsKey(idempotencyKey)) {
        return false;
      }
      if (claimed.contains(payoutId)) {
        return false;
      }
      if (!java.util.Set.of("HELD", "FAILED", "PENDING").contains(p.status())) {
        return false;
      }
      claimed.add(payoutId);
      byIdempotency.put(idempotencyKey, payoutId);
      return true;
    }

    @Override
    public Optional<PayoutRecord> findByRiderAndCycle(
        UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
      return byId.values().stream()
          .filter(
              p ->
                  p.riderId().equals(riderId)
                      && p.cycleFrom().equals(cycleFrom)
                      && p.cycleTo().equals(cycleTo))
          .findFirst();
    }

    @Override
    public List<PayoutRecord> listForRider(
        UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
      return byId.values().stream().filter(p -> p.riderId().equals(riderId)).toList();
    }

    @Override
    public long countForRider(UUID riderId, LocalDate from, LocalDate to) {
      return listForRider(riderId, from, to, 0, 100).size();
    }

    @Override
    public List<PayoutRecord> findDueForRetry(Instant now, int limit) {
      return byId.values().stream()
          .filter(p -> "PENDING".equals(p.status()))
          .filter(p -> p.retryCount() == 0)
          .filter(p -> p.nextRetryAt() != null && !p.nextRetryAt().isAfter(now))
          .limit(limit)
          .toList();
    }
  }
}
