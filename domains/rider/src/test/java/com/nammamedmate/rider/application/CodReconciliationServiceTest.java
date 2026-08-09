package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.CodReconciliationService.BoardResult;
import com.nammamedmate.rider.application.port.out.CodCollectionStore;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionRecord;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionView;
import com.nammamedmate.rider.application.port.out.CodDepositStore;
import com.nammamedmate.rider.application.port.out.CodDepositStore.BoardPage;
import com.nammamedmate.rider.application.port.out.CodDepositStore.CodBoardRow;
import com.nammamedmate.rider.application.port.out.CodDepositStore.DepositRecord;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetPage;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodReconciliationServiceTest {

  private static final Instant T0 = Instant.parse("2026-07-24T09:30:00Z");

  private FakeRiders riders;
  private FakeCollections collections;
  private FakeDeposits deposits;
  private InMemoryOutboxStore outbox;
  private CodReconciliationService service;
  private UUID riderId;
  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    collections = new FakeCollections();
    deposits = new FakeDeposits(riders);
    outbox = new InMemoryOutboxStore();
    service =
        new CodReconciliationService(
            riders,
            collections,
            deposits,
            cfg("200000"),
            new FakeFleet(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
    riderId = Ids.newId();
    riders.insert(rider(riderId, 0L));
  }

  @Test
  void ac001_recordCollectionCrossesLimit_flagsFloatRiskAndNotifies() {
    service.recordCollection(riderId, Ids.newId(), 150_000L, T0);
    service.recordCollection(riderId, Ids.newId(), 60_000L, T0);
    assertThat(riders.findById(riderId).orElseThrow().codInHandPaise()).isEqualTo(210_000L);
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("cod_float_risk"));
  }

  @Test
  void ac002_assertCanAcceptCod_blocksAtOrAboveLimit() {
    riders.adjustCodInHand(riderId, 200_000L, T0);
    assertThatThrownBy(() -> service.assertCanAcceptCod(riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COD_LIMIT_EXCEEDED");
    riders.adjustCodInHand(riderId, -1L, T0);
    service.assertCanAcceptCod(riderId);
  }

  @Test
  void ac003_markDeposited_reducesFloatAndClearsRisk() {
    riders.adjustCodInHand(riderId, 260_000L, T0);
    Map<String, Object> data =
        service.markDeposited(
            finance(), riderId, 1000.00, "2026-07-24T15:00:00Z", "UPI-OK-1", "notes");
    assertThat(data.get("risk_status_after")).isEqualTo("SAFE");
    assertThat(data.get("cod_in_hand_after")).isEqualTo(CodFloatLimits.paiseToRupees(160_000L));
    assertThat(riders.findById(riderId).orElseThrow().codInHandPaise()).isEqualTo(160_000L);
  }

  @Test
  void ac004_riderSummary_reflectsLimitRemainingAndCanAccept() {
    riders.adjustCodInHand(riderId, 200_000L, T0);
    Map<String, Object> data = service.riderSummary(rider());
    assertThat(data.get("can_accept_cod_orders")).isEqualTo(false);
    assertThat(data.get("limit_remaining")).isEqualTo(CodFloatLimits.paiseToRupees(0));
    assertThat(data.get("in_hand")).isEqualTo(CodFloatLimits.paiseToRupees(200_000L));
  }

  @Test
  void ac005_duplicateReference_returns409() {
    riders.adjustCodInHand(riderId, 50_000L, T0);
    service.depositRequest(rider(), 100.00, "UPI", "REF-DUP", null);
    assertThatThrownBy(() -> service.depositRequest(rider(), 50.00, "UPI", "REF-DUP", "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DUPLICATE_REFERENCE");
  }

  @Test
  void ac006_riskOnlyBoard_filtersAndSortsDescending() {
    UUID safe = Ids.newId();
    UUID risk = Ids.newId();
    riders.insert(rider(safe, 50_000L));
    riders.insert(rider(risk, 300_000L));
    BoardResult board = service.adminBoard(ops(), null, true, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) board.data().get("riders");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("rider_id")).isEqualTo(risk.toString());
    assertThat(rows.get(0).get("risk_status")).isEqualTo("FLOAT_RISK");
  }

  @Test
  void ac007_dailyReport_publishesOutboxForFinance() {
    riders.adjustCodInHand(riderId, 10_000L, T0);
    service.publishDailyReport();
    assertThat(outbox.all())
        .anyMatch(m -> "finance.cod.daily_reconciliation_report".equals(m.type()));
  }

  @Test
  void depositRequest_staysPendingUntilAdminConfirms() {
    riders.adjustCodInHand(riderId, 80_000L, T0);
    Map<String, Object> req = service.depositRequest(rider(), 500.00, "BRANCH", "BR-1", "n");
    assertThat(req.get("status")).isEqualTo("PENDING_CONFIRMATION");
    assertThat(riders.findById(riderId).orElseThrow().codInHandPaise()).isEqualTo(80_000L);
    Map<String, Object> confirmed =
        service.markDeposited(finance(), riderId, 500.00, null, "BR-1", "ok");
    assertThat(confirmed.get("amount_confirmed")).isEqualTo(CodFloatLimits.paiseToRupees(50_000L));
    assertThat(riders.findById(riderId).orElseThrow().codInHandPaise()).isEqualTo(30_000L);
  }

  @Test
  void remind_andValidationBranches() {
    riders.adjustCodInHand(riderId, 10_000L, T0);
    Map<String, Object> rem = service.remind(ops(), riderId, null);
    assertThat(rem.get("notification_sent")).isEqualTo(true);
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("cod_deposit_reminder"));

    assertThatThrownBy(() -> service.depositRequest(rider(), 0, "UPI", "Z", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_AMOUNT");
    assertThatThrownBy(() -> service.depositRequest(rider(), 10, "CASH", "Z2", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DEPOSIT_MODE");
    assertThatThrownBy(() -> service.depositRequest(rider(), 9999.00, "UPI", "Z3", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AMOUNT_EXCEEDS_IN_HAND");
    assertThatThrownBy(() -> service.markDeposited(finance(), riderId, 9999.00, null, "X", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AMOUNT_EXCEEDS_IN_HAND");
    assertThatThrownBy(() -> service.markDeposited(finance(), Ids.newId(), 1, null, "Y", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.markDeposited(ops(), riderId, 1, null, "Y", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.adminBoard(rider(), null, false, 0, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    new CodDailyReportScheduler(service).generateDailyReport();
  }

  @Test
  void financePorts_wiredDepositHookAndDailyBridge() {
    java.util.concurrent.atomic.AtomicBoolean depositHook =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<java.time.LocalDate> daily =
        new java.util.concurrent.atomic.AtomicReference<>();
    CodReconciliationService wired =
        new CodReconciliationService(
            riders,
            collections,
            deposits,
            cfg("200000"),
            new FakeFleet(),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock,
            (depositId, rider, amount) -> depositHook.set(true),
            daily::set);
    riders.adjustCodInHand(riderId, 50_000L, T0);
    wired.markDeposited(finance(), riderId, 100.00, null, "HOOK-REF-" + Ids.newId(), null);
    assertThat(depositHook).isTrue();
    wired.publishDailyReport();
    assertThat(daily.get()).isEqualTo(java.time.LocalDate.parse("2026-07-24"));
    assertThat(outbox.all()).isEmpty();
  }

  @Test
  void recordCollection_idempotentPerOrder() {
    UUID orderId = Ids.newId();
    service.recordCollection(riderId, orderId, 1000L, T0);
    service.recordCollection(riderId, orderId, 1000L, T0);
    assertThat(riders.findById(riderId).orElseThrow().codInHandPaise()).isEqualTo(1000L);
    service.recordCollection(null, orderId, 1L, T0);
    service.recordCollection(riderId, null, 1L, T0);
    service.recordCollection(riderId, Ids.newId(), 0L, T0);
  }

  private MedmatePrincipal rider() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal ops() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal finance() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  private static PlatformPricingConfigStore cfg(String value) {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        return Optional.ofNullable(value);
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(String key, String v, String description, UUID updatedBy, Instant now) {}
    };
  }

  private static RiderRecord rider(UUID id, long cod) {
    Instant now = T0;
    return new RiderRecord(
        id,
        "Ravi",
        "+919999000001",
        null,
        "BIKE",
        "KA01AB1234",
        null,
        "ONLINE",
        "APPROVED",
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        0,
        null,
        0L,
        cod,
        0,
        null,
        null,
        null,
        now,
        now);
  }

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return byId.values().stream().filter(r -> r.phone().equals(phone)).findFirst();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return findByPhone(phone).isPresent();
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.copyOf(byId.values()), byId.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}

    @Override
    public long adjustCodInHand(UUID id, long deltaPaise, Instant updatedAt) {
      RiderRecord r = byId.get(id);
      long next = r.codInHandPaise() + deltaPaise;
      if (next < 0) {
        throw new IllegalStateException("neg");
      }
      RiderRecord u =
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
              next,
              r.dailyStreakDays(),
              r.blockedReason(),
              r.blockedBy(),
              r.blockedAt(),
              r.createdAt(),
              updatedAt);
      byId.put(id, u);
      return next;
    }
  }

  static final class FakeCollections implements CodCollectionStore {
    private final List<CollectionRecord> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(CollectionRecord row) {
      rows.add(row);
    }

    @Override
    public Optional<CollectionRecord> findByOrderId(UUID orderId) {
      return rows.stream().filter(r -> r.orderId().equals(orderId)).findFirst();
    }

    @Override
    public List<CollectionView> recentForRider(UUID riderId, int limit) {
      return rows.stream()
          .filter(r -> r.riderId().equals(riderId))
          .sorted(Comparator.comparing(CollectionRecord::collectedAt).reversed())
          .limit(limit)
          .map(
              r ->
                  new CollectionView(
                      r.orderId(), "MED-1", r.codAmountPaise(), r.collectedAt(), r.deposited()))
          .toList();
    }

    @Override
    public long sumCollectedToday(UUID riderId, Instant dayStart, Instant dayEnd) {
      return rows.stream()
          .filter(r -> r.riderId().equals(riderId))
          .filter(r -> !r.collectedAt().isBefore(dayStart) && r.collectedAt().isBefore(dayEnd))
          .mapToLong(CollectionRecord::codAmountPaise)
          .sum();
    }

    @Override
    public long sumCollectedTodayAll(Instant dayStart, Instant dayEnd) {
      return rows.stream()
          .filter(r -> !r.collectedAt().isBefore(dayStart) && r.collectedAt().isBefore(dayEnd))
          .mapToLong(CollectionRecord::codAmountPaise)
          .sum();
    }

    @Override
    public long markDepositedFifo(UUID riderId, UUID depositId, long amountPaise) {
      long remaining = amountPaise;
      long applied = 0;
      List<CollectionRecord> next = new ArrayList<>();
      for (CollectionRecord c : rows) {
        if (!c.riderId().equals(riderId) || c.deposited() || c.codAmountPaise() > remaining) {
          next.add(c);
          continue;
        }
        next.add(
            new CollectionRecord(
                c.id(),
                c.riderId(),
                c.orderId(),
                c.codAmountPaise(),
                c.collectedAt(),
                depositId,
                true,
                c.createdAt()));
        remaining -= c.codAmountPaise();
        applied += c.codAmountPaise();
      }
      rows.clear();
      rows.addAll(next);
      return applied;
    }
  }

  static final class FakeDeposits implements CodDepositStore {
    private final List<DepositRecord> rows = new CopyOnWriteArrayList<>();
    private final FakeRiders riders;

    FakeDeposits(FakeRiders riders) {
      this.riders = riders;
    }

    @Override
    public void insert(DepositRecord row) {
      rows.add(row);
    }

    @Override
    public void update(DepositRecord row) {
      rows.removeIf(r -> r.id().equals(row.id()));
      rows.add(row);
    }

    @Override
    public Optional<DepositRecord> findById(UUID id) {
      return rows.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public Optional<DepositRecord> findByReference(String referenceNumber) {
      return rows.stream().filter(r -> r.referenceNumber().equals(referenceNumber)).findFirst();
    }

    @Override
    public Optional<DepositRecord> findPendingByReference(UUID riderId, String referenceNumber) {
      return rows.stream()
          .filter(
              r ->
                  r.riderId().equals(riderId)
                      && r.referenceNumber().equals(referenceNumber)
                      && "PENDING_CONFIRMATION".equals(r.status()))
          .findFirst();
    }

    @Override
    public boolean referenceExists(String referenceNumber) {
      return findByReference(referenceNumber).isPresent();
    }

    @Override
    public long sumDepositedToday(UUID riderId, Instant dayStart, Instant dayEnd) {
      return rows.stream()
          .filter(r -> r.riderId().equals(riderId) && "CONFIRMED".equals(r.status()))
          .mapToLong(DepositRecord::amountPaise)
          .sum();
    }

    @Override
    public long sumDepositedTodayAll(Instant dayStart, Instant dayEnd) {
      return rows.stream()
          .filter(r -> "CONFIRMED".equals(r.status()))
          .mapToLong(DepositRecord::amountPaise)
          .sum();
    }

    @Override
    public long sumPendingDepositRequests(Instant dayStart, Instant dayEnd) {
      return rows.stream()
          .filter(r -> "PENDING_CONFIRMATION".equals(r.status()))
          .mapToLong(DepositRecord::amountPaise)
          .sum();
    }

    @Override
    public int countFloatRiskRiders(long limitPaise) {
      return (int)
          riders.byId.values().stream().filter(r -> r.codInHandPaise() > limitPaise).count();
    }

    @Override
    public long sumCodInHandAll() {
      return riders.byId.values().stream().mapToLong(RiderRecord::codInHandPaise).sum();
    }

    @Override
    public Instant lastConfirmedDepositAt(UUID riderId) {
      return rows.stream()
          .filter(r -> r.riderId().equals(riderId) && "CONFIRMED".equals(r.status()))
          .map(DepositRecord::confirmedAt)
          .filter(t -> t != null)
          .max(Comparator.naturalOrder())
          .orElse(null);
    }

    @Override
    public BoardPage listBoard(
        UUID zoneId, boolean riskOnly, long limitPaise, int page, int limit) {
      List<CodBoardRow> all =
          riders.byId.values().stream()
              .filter(r -> !riskOnly || r.codInHandPaise() > limitPaise)
              .filter(r -> r.codInHandPaise() > 0 || !rows.isEmpty() || riskOnly)
              .sorted(Comparator.comparingLong(RiderRecord::codInHandPaise).reversed())
              .map(
                  r ->
                      new CodBoardRow(
                          r.id(),
                          r.name(),
                          r.primaryZoneId(),
                          "Z",
                          r.codInHandPaise(),
                          0,
                          0,
                          0,
                          null))
              .toList();
      if (riskOnly) {
        all = all.stream().filter(r -> r.codInHandPaise() > limitPaise).toList();
      }
      int from = Math.max(0, (page - 1) * limit);
      List<CodBoardRow> pageRows =
          from >= all.size() ? List.of() : all.subList(from, Math.min(from + limit, all.size()));
      return new BoardPage(pageRows, all.size());
    }

    @Override
    public List<CodBoardRow> allForReport(long limitPaise) {
      return riders.byId.values().stream()
          .filter(r -> r.codInHandPaise() > 0)
          .map(
              r ->
                  new CodBoardRow(
                      r.id(), r.name(), r.primaryZoneId(), "Z", r.codInHandPaise(), 0, 0, 0, null))
          .toList();
    }
  }

  static final class FakeFleet implements RiderFleetStore {
    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(List.of(), 0);
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return List.of();
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 2;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0L;
    }
  }
}
