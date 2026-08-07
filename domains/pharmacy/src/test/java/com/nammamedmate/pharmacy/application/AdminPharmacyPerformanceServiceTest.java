package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.messaging.StubNotificationDispatchClient;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore.AlertRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.AdminOrderDetail;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.OrderListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PeriodMetrics;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PharmacyRating;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RatingListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore.SnapshotRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AdminPharmacyPerformanceServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");

  private FakePharmacyStore pharmacies;
  private FakeSnapshotStore snapshots;
  private FakeAlertStore alertStore;
  private SeedableOrderMetrics orderMetrics;
  private InMemoryOutboxStore outboxStore;
  private RateLimiter rateLimiter;
  private AdminPharmacyPerformanceService service;

  @BeforeEach
  void setUp() {
    pharmacies = new FakePharmacyStore();
    snapshots = new FakeSnapshotStore();
    alertStore = new FakeAlertStore();
    orderMetrics = new SeedableOrderMetrics();
    outboxStore = new InMemoryOutboxStore();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    service = newService(null);
    pharmacies.put(samplePharmacy());
  }

  private AdminPharmacyPerformanceService newService(ObjectProvider<StringRedisTemplate> redis) {
    ObjectMapper mapper = new ObjectMapper();
    OutboxPublisher publisher = new OutboxPublisher(outboxStore, mapper);
    return new AdminPharmacyPerformanceService(
        pharmacies,
        snapshots,
        alertStore,
        orderMetrics,
        new StubNotificationDispatchClient(publisher),
        rateLimiter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        mapper,
        redis);
  }

  private static AdminDetailRow samplePharmacy() {
    return new AdminDetailRow(
        PID,
        "PHM-0042",
        "Sharma Medical Store",
        "Owner",
        "+919811100001",
        "a@t.com",
        "RETAIL",
        Map.of(),
        null,
        null,
        null,
        null,
        "ACTIVE",
        "GROWTH",
        new BigDecimal("8.00"),
        null,
        null,
        true,
        false,
        NOW,
        NOW,
        NOW,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static SnapshotRow snapshot30d() {
    return new SnapshotRow(
        Ids.newId(),
        PID,
        "30D",
        LocalDate.parse("2026-06-24"),
        LocalDate.parse("2026-07-23"),
        842,
        768,
        26,
        new BigDecimal("91.20"),
        new BigDecimal("88.50"),
        new BigDecimal("3.10"),
        new BigDecimal("6.30"),
        new BigDecimal("14.2"),
        4,
        new BigDecimal("4.30"),
        128,
        48_500_000L,
        (short) 0,
        "IMPROVING",
        "STABLE",
        Instant.parse("2026-07-24T02:00:00Z"));
  }

  private MedmatePrincipal ops() {
    return new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  /** AC-1: GET performance?period=30d returns computed metrics with computed_at. */
  @Test
  void ac1_performance30dIncludesMetricsAndComputedAt() {
    snapshots.put(snapshot30d());
    Map<String, Object> data = service.performance(ops(), PID, "30d");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
    assertThat(metrics.get("fill_rate_pct")).isEqualTo(new BigDecimal("91.20"));
    assertThat(metrics.get("on_time_prep_pct")).isEqualTo(new BigDecimal("88.50"));
    assertThat(metrics.get("cancel_rate_pct")).isEqualTo(new BigDecimal("3.10"));
    assertThat(metrics.get("out_of_stock_rate_pct")).isEqualTo(new BigDecimal("6.30"));
    assertThat(metrics.get("avg_prep_minutes")).isEqualTo(new BigDecimal("14.2"));
    assertThat(metrics.get("avg_rating")).isEqualTo(new BigDecimal("4.30"));
    assertThat(metrics.get("review_count")).isEqualTo(128);
    assertThat(data.get("computed_at")).isEqualTo(Instant.parse("2026-07-24T02:00:00Z"));
  }

  /** AC-2: fill_rate below warning threshold triggers auto_warning_triggered. */
  @Test
  void ac2_lowFillRateTriggersAutoWarning() {
    SnapshotRow lowFill =
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            100,
            78,
            5,
            new BigDecimal("78.50"),
            new BigDecimal("80.00"),
            new BigDecimal("5.00"),
            new BigDecimal("4.00"),
            new BigDecimal("12.0"),
            0,
            new BigDecimal("4.00"),
            10,
            0L,
            (short) 0,
            "STABLE",
            "STABLE",
            NOW);
    snapshots.put(lowFill);
    @SuppressWarnings("unchecked")
    Map<String, Object> alerts =
        (Map<String, Object>) service.performance(ops(), PID, "30d").get("alerts");
    assertThat(alerts.get("auto_warning_triggered")).isEqualTo(true);
  }

  /** AC-3: 3 consecutive low-fill days triggers suspension risk. */
  @Test
  void ac3_consecutiveLowFillDaysTriggersSuspensionRisk() {
    SnapshotRow risk =
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            100,
            60,
            10,
            new BigDecimal("65.00"),
            new BigDecimal("70.00"),
            new BigDecimal("8.00"),
            new BigDecimal("5.00"),
            new BigDecimal("18.0"),
            1,
            new BigDecimal("3.50"),
            5,
            0L,
            (short) 3,
            "DECLINING",
            "STABLE",
            NOW);
    snapshots.put(risk);
    @SuppressWarnings("unchecked")
    Map<String, Object> alerts =
        (Map<String, Object>) service.performance(ops(), PID, "30d").get("alerts");
    assertThat(alerts.get("auto_suspension_risk")).isEqualTo(true);
    assertThat(alerts.get("consecutive_low_fill_rate_days")).isEqualTo(3);
  }

  /** AC-4: POST alert stores record, notifies via outbox, sets next_alert_allowed_at. */
  @Test
  void ac4_sendAlertStoresAndNotifies() {
    Map<String, Object> data =
        service.sendAlert(ops(), PID, "LOW_FILL_RATE", new BigDecimal("78.5"), null);
    assertThat(data.get("alert_type")).isEqualTo("LOW_FILL_RATE");
    assertThat(data.get("channels_notified")).isEqualTo(List.of("WHATSAPP", "IN_APP"));
    assertThat(data.get("next_alert_allowed_at")).isEqualTo(NOW.plusSeconds(86_400));
    assertThat(alertStore.inserted).hasSize(1);
    assertThat(outboxStore.all()).hasSize(1);
    assertThat(outboxStore.all().getFirst().type())
        .isEqualTo("pharmacy.notification.performance_alert");
  }

  /** AC-5: duplicate alert within 24h returns ALERT_THROTTLED. */
  @Test
  void ac5_duplicateAlertThrottled() {
    service.sendAlert(ops(), PID, "LOW_FILL_RATE", new BigDecimal("78.5"), "first");
    assertThatThrownBy(
            () -> service.sendAlert(ops(), PID, "LOW_FILL_RATE", new BigDecimal("75.0"), "again"))
        .isInstanceOfSatisfying(
            AppException.class,
            ex -> {
              assertThat(ex.code()).isEqualTo("ALERT_THROTTLED");
              assertThat(ex.httpStatus()).isEqualTo(429);
              assertThat(ex.details()).containsKey("next_alert_allowed_at");
            });
  }

  /** AC-6: GET ratings?rating=1 returns masked 1-star only. */
  @Test
  void ac6_ratingsFilterOneStarWithMaskedName() {
    orderMetrics.ratingsResult =
        new RatingListResult(
            new BigDecimal("2.00"),
            1,
            Map.of(1, 1, 2, 0, 3, 0, 4, 0, 5, 0),
            List.of(
                new PharmacyRating(
                    Ids.newId(), Ids.newId(), "ORD-001", "Priya Sharma", 1, "Late delivery", NOW)),
            1L);
    var result = service.ratings(ops(), PID, 1, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ratings = (List<Map<String, Object>>) result.data().get("ratings");
    assertThat(ratings).hasSize(1);
    assertThat(ratings.getFirst().get("rating")).isEqualTo(1);
    assertThat(ratings.getFirst().get("customer_name")).isEqualTo("Priya S.");
  }

  /** AC-7: GET orders date range returns prep_on_time per order. */
  @Test
  void ac7_ordersDateRangeWithPrepOnTime() {
    LocalDate from = LocalDate.parse("2026-07-01");
    LocalDate to = LocalDate.parse("2026-07-24");
    orderMetrics.lastFrom = null;
    orderMetrics.lastTo = null;
    orderMetrics.ordersResult =
        new OrderListResult(
            List.of(
                new AdminOrderDetail(
                    Ids.newId(),
                    "ORD-20260724-0042",
                    "DELIVERED",
                    "Arun Menon",
                    3,
                    45_000L,
                    12,
                    true,
                    false,
                    NOW,
                    NOW.plusSeconds(2100))),
            1L);
    var result = service.orders(ops(), PID, null, from, to, 1, 20);
    assertThat(orderMetrics.lastFrom).isEqualTo(from);
    assertThat(orderMetrics.lastTo).isEqualTo(to);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orders = (List<Map<String, Object>>) result.data().get("orders");
    assertThat(orders.getFirst().get("prep_on_time")).isEqualTo(true);
  }

  /** AC-8: new pharmacy with no snapshot returns zeros and computed_at null. */
  @Test
  void ac8_noSnapshotReturnsZerosAndNullComputedAt() {
    Map<String, Object> data = service.performance(ops(), PID, "30d");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
    assertThat(metrics.get("fill_rate_pct")).isEqualTo(new BigDecimal("0.00"));
    assertThat(metrics.get("review_count")).isEqualTo(0);
    assertThat(data.get("computed_at")).isNull();
  }

  @Test
  void invalidPeriodRejected() {
    assertThatThrownBy(() -> service.performance(ops(), PID, "14d"))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_PERIOD"));
  }

  @Test
  void invalidRatingFilterRejected() {
    assertThatThrownBy(() -> service.ratings(ops(), PID, 6, null, null, 1, 20))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_RATING_FILTER"));
  }

  @Test
  void complianceCanReadPerformanceButNotSendAlert() {
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    snapshots.put(snapshot30d());
    Map<String, Object> data = service.performance(compliance, PID, "30d");
    assertThat(data)
        .containsOnlyKeys(
            "pharmacy_id", "period", "period_start", "period_end", "metrics", "computed_at");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
    assertThat(metrics)
        .containsOnlyKeys("fill_rate_pct", "out_of_stock_rate_pct")
        .containsEntry("fill_rate_pct", new BigDecimal("91.20"))
        .containsEntry("out_of_stock_rate_pct", new BigDecimal("6.30"));
    assertThatThrownBy(
            () -> service.sendAlert(compliance, PID, "LOW_FILL_RATE", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void compliancePerformanceOmitsRestrictedFieldsWhenNoSnapshot() {
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    Map<String, Object> data = service.performance(compliance, PID, "30d");
    assertThat(data).doesNotContainKeys("alerts", "thresholds", "trend", "business_name");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
    assertThat(metrics)
        .containsOnlyKeys("fill_rate_pct", "out_of_stock_rate_pct")
        .containsEntry(
            "fill_rate_pct", AdminPharmacyPerformanceService.emptyMetrics().get("fill_rate_pct"))
        .containsEntry(
            "out_of_stock_rate_pct",
            AdminPharmacyPerformanceService.emptyMetrics().get("out_of_stock_rate_pct"));
    assertThat(data.get("computed_at")).isNull();
  }

  @Test
  void opsAndSuperGetFullPerformancePayload() {
    snapshots.put(snapshot30d());
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    for (MedmatePrincipal principal : List.of(ops(), superAdmin)) {
      Map<String, Object> data = service.performance(principal, PID, "30d");
      assertThat(data)
          .containsKeys(
              "pharmacy_id",
              "business_name",
              "period",
              "period_start",
              "period_end",
              "metrics",
              "alerts",
              "thresholds",
              "trend",
              "computed_at");
      @SuppressWarnings("unchecked")
      Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
      assertThat(metrics)
          .containsKeys(
              "fill_rate_pct",
              "on_time_prep_pct",
              "cancel_rate_pct",
              "out_of_stock_rate_pct",
              "avg_prep_minutes",
              "complaint_count",
              "avg_rating",
              "review_count",
              "orders_received",
              "orders_fulfilled",
              "orders_cancelled",
              "gmv_period");
    }
  }

  @Test
  void canSeeFullPerformanceRoleGate() {
    assertThat(AdminPharmacyPerformanceService.canSeeFullPerformance(AuthRole.ADMIN_COMPLIANCE))
        .isFalse();
    assertThat(AdminPharmacyPerformanceService.canSeeFullPerformance(AuthRole.ADMIN_OPERATIONS))
        .isTrue();
    assertThat(AdminPharmacyPerformanceService.canSeeFullPerformance(AuthRole.ADMIN_SUPER))
        .isTrue();
    assertThat(AdminPharmacyPerformanceService.canSeeFullPerformance(AuthRole.ADMIN_SUPPORT))
        .isTrue();
  }

  @Test
  void redisCacheHitAvoidsDb() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    SnapshotRow row = snapshot30d();
    try {
      ObjectMapper mapper = new ObjectMapper();
      String json = mapper.writeValueAsString(AdminPharmacyPerformanceServiceTest.cacheMap(row));
      when(valueOps.get(AdminPharmacyPerformanceService.cacheKey(PID, "30D"))).thenReturn(json);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    AdminPharmacyPerformanceService cached = newService(provider);
    Map<String, Object> data = cached.performance(ops(), PID, "30d");
    assertThat(data.get("computed_at")).isEqualTo(row.computedAt());
    assertThat(snapshots.findCalls).isZero();
  }

  @Test
  void computeTrendBranches() {
    assertThat(
            AdminPharmacyPerformanceService.computeTrend(
                new BigDecimal("90"), new BigDecimal("85")))
        .isEqualTo("IMPROVING");
    assertThat(
            AdminPharmacyPerformanceService.computeTrend(
                new BigDecimal("80"), new BigDecimal("85")))
        .isEqualTo("DECLINING");
    assertThat(
            AdminPharmacyPerformanceService.computeTrend(
                new BigDecimal("86"), new BigDecimal("85")))
        .isEqualTo("STABLE");
    assertThat(AdminPharmacyPerformanceService.computeTrend(null, new BigDecimal("1")))
        .isEqualTo("STABLE");
    assertThat(AdminPharmacyPerformanceService.computeTrend(null, null)).isEqualTo("STABLE");
  }

  @Test
  void aggregatorSchedulerDelegates() {
    PharmacyPerformanceAggregatorService aggregator =
        mock(PharmacyPerformanceAggregatorService.class);
    new PharmacyPerformanceAggregatorScheduler(aggregator).runNightlyAggregation();
    verify(aggregator).aggregateAll();
  }

  @Test
  void stubOrderMetricsClientReturnsZeros() {
    var stub = new com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyOrderMetricsClient();
    UUID id = Ids.newId();
    assertThat(stub.periodMetrics(id, LocalDate.now(), 30).ordersReceived()).isZero();
    assertThat(stub.listRatings(id, null, "created_at", "desc", 20, 0).total()).isZero();
    assertThat(stub.listOrders(id, "ALL", LocalDate.now(), LocalDate.now(), 20, 0).total())
        .isZero();
  }

  @Test
  void sendAlertValidationAndNotFound() {
    assertThatThrownBy(() -> service.sendAlert(ops(), PID, "BAD_TYPE", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_ALERT_TYPE"));
    assertThatThrownBy(() -> service.sendAlert(ops(), PID, "LOW_FILL_RATE", null, null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("THRESHOLD_VALUE_REQUIRED"));
    assertThatThrownBy(
            () ->
                service.sendAlert(
                    ops(), PID, "LOW_FILL_RATE", new BigDecimal("1"), "x".repeat(501)))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
    UUID missing = Ids.newId();
    assertThatThrownBy(
            () -> service.sendAlert(ops(), missing, "LOW_FILL_RATE", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("PHARMACY_NOT_FOUND"));
  }

  @Test
  void ordersInvalidDateRangeRejected() {
    assertThatThrownBy(
            () ->
                service.orders(
                    ops(),
                    PID,
                    null,
                    LocalDate.parse("2026-07-24"),
                    LocalDate.parse("2026-07-01"),
                    1,
                    20))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
  }

  @Test
  void rateLimitExceededOnPerformance() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.performance(ops(), PID, "30d"))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("RATE_LIMIT_EXCEEDED"));
  }

  @Test
  void unauthorizedPrincipalRejected() {
    assertThatThrownBy(() -> service.performance(null, PID, "30d"))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("UNAUTHORIZED"));
  }

  @Test
  void period7dAnd90dAccepted() {
    snapshots.put(
        new SnapshotRow(
            Ids.newId(),
            PID,
            "7D",
            LocalDate.parse("2026-07-17"),
            LocalDate.parse("2026-07-23"),
            10,
            9,
            1,
            new BigDecimal("90.00"),
            new BigDecimal("85.00"),
            new BigDecimal("5.00"),
            new BigDecimal("2.00"),
            new BigDecimal("14.0"),
            0,
            new BigDecimal("4.50"),
            20,
            100_000L,
            (short) 0,
            "STABLE",
            "STABLE",
            NOW));
    assertThat(service.performance(ops(), PID, "7d").get("period")).isEqualTo("7d");
    assertThat(service.performance(ops(), PID, "90d").get("period")).isEqualTo("90d");
  }

  @Test
  void highCancelRateTriggersAutoWarning() {
    snapshots.put(
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            100,
            80,
            20,
            new BigDecimal("90.00"),
            new BigDecimal("85.00"),
            new BigDecimal("20.00"),
            new BigDecimal("2.00"),
            new BigDecimal("14.0"),
            0,
            new BigDecimal("4.00"),
            10,
            0L,
            (short) 0,
            "STABLE",
            "STABLE",
            NOW));
    @SuppressWarnings("unchecked")
    Map<String, Object> alerts =
        (Map<String, Object>) service.performance(ops(), PID, "30d").get("alerts");
    assertThat(alerts.get("auto_warning_triggered")).isEqualTo(true);
  }

  @Test
  void dbSnapshotPopulatesLocalCache() {
    snapshots.put(snapshot30d());
    AdminPharmacyPerformanceService localOnly = newService(null);
    assertThat(localOnly.performance(ops(), PID, "30d").get("computed_at")).isNotNull();
    assertThat(localOnly.performance(ops(), PID, "30d").get("computed_at")).isNotNull();
    assertThat(snapshots.findCalls).isEqualTo(1);
  }

  @Test
  void ratingsSortFallbackAndSupportForbidden() {
    orderMetrics.ratingsResult = new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
    service.ratings(ops(), PID, null, "invalid", "asc", 1, 20);
    MedmatePrincipal support =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThat(service.ratings(support, PID, null, null, null, 1, 20)).isNotNull();
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.ratings(compliance, PID, null, null, null, 1, 20))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void coversServiceEdgeBranches() {
    assertThat(AdminPharmacyPerformanceService.normalisePeriod(null)).isEqualTo("30d");
    assertThat(AdminPharmacyPerformanceService.normalisePeriod("  ")).isEqualTo("30d");
    assertThat(AdminPharmacyPerformanceService.dbPeriod("7d")).isEqualTo("7D");
    assertThat(AdminPharmacyPerformanceService.dbPeriod("90d")).isEqualTo("90D");
    assertThat(AdminPharmacyPerformanceService.dbPeriod("30d")).isEqualTo("30D");
    assertThat(AdminPharmacyPerformanceService.periodDays("7d")).isEqualTo(7);
    assertThat(AdminPharmacyPerformanceService.periodDays("90d")).isEqualTo(90);
    assertThat(AdminPharmacyPerformanceService.periodDays("30d")).isEqualTo(30);
    assertThat(AdminPharmacyPerformanceService.paiseToRupees(100L))
        .isEqualTo(new BigDecimal("1.00"));
    assertThat(
            new AdminPharmacyPerformanceService.PagedResult(null, PaginationMeta.of(1, 20, 0))
                .data())
        .isEmpty();

    MedmatePrincipal finance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.performance(finance, PID, "30d"))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));

    assertThatThrownBy(() -> service.ratings(ops(), PID, 0, "rating", "asc", 2, 200))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_RATING_FILTER"));

    orderMetrics.ratingsResult =
        new RatingListResult(new BigDecimal("4.0"), 5, Map.of(5, 3), List.of(), 5L);
    service.ratings(ops(), PID, null, "rating", "asc", 2, 200);

    orderMetrics.ordersResult = new OrderListResult(List.of(), 0L);
    service.orders(ops(), PID, "delivered", null, null, 2, 200);

    assertThatThrownBy(() -> service.sendAlert(ops(), PID, "  ", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_ALERT_TYPE"));

    alertStore.inserted.clear();
    outboxStore.all().clear();
    service.sendAlert(ops(), PID, "HIGH_CANCEL_RATE", new BigDecimal("20"), "custom msg");

    SnapshotRow noSuspension =
        new SnapshotRow(
            Ids.newId(),
            PID,
            "30D",
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-23"),
            100,
            80,
            5,
            new BigDecimal("75.00"),
            new BigDecimal("80.00"),
            new BigDecimal("5.00"),
            new BigDecimal("4.00"),
            new BigDecimal("12.0"),
            0,
            new BigDecimal("4.00"),
            10,
            0L,
            (short) 3,
            "STABLE",
            "STABLE",
            NOW);
    snapshots.put(noSuspension);
    @SuppressWarnings("unchecked")
    Map<String, Object> alerts =
        (Map<String, Object>) service.performance(ops(), PID, "30d").get("alerts");
    assertThat(alerts.get("auto_suspension_risk")).isEqualTo(false);

    AdminPharmacyPerformanceService localOnly = newService(null);
    localOnly.writeCache(PID, "30D", snapshot30d());
    localCacheCorrupt(localOnly, PID);
    redisWriteCachePath();
  }

  private void redisWriteCachePath() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(any())).thenReturn("");
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    AdminPharmacyPerformanceService redisSvc = newService(provider);
    snapshots.put(snapshot30d());
    assertThat(redisSvc.loadSnapshot(PID, "30D")).isNotNull();
    verify(valueOps).set(any(), any(), any(java.time.Duration.class));
  }

  @Test
  void writeCacheIOExceptionIgnored() throws Exception {
    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    AdminPharmacyPerformanceService brokenSvc =
        new AdminPharmacyPerformanceService(
            pharmacies,
            snapshots,
            alertStore,
            orderMetrics,
            new StubNotificationDispatchClient(
                new OutboxPublisher(outboxStore, new ObjectMapper())),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            broken,
            null);
    brokenSvc.writeCache(PID, "30D", snapshot30d());
  }

  @Test
  void ratingsAndOrdersPaginationBranches() {
    orderMetrics.ratingsResult = new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
    service.ratings(ops(), PID, null, "created_at", null, 0, 0);
    service.orders(ops(), PID, null, null, null, 0, 0);
    assertThatThrownBy(
            () -> service.sendAlert(null, PID, "LOW_FILL_RATE", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("UNAUTHORIZED"));
    MedmatePrincipal support =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> service.sendAlert(support, PID, "LOW_FILL_RATE", new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThat(service.performance(superAdmin, PID, "30d")).isNotNull();
    assertThat(service.ratings(superAdmin, PID, null, null, null, 1, 20)).isNotNull();
    alertStore.inserted.clear();
    outboxStore.all().clear();
    service.sendAlert(superAdmin, PID, "LOW_RATING", new BigDecimal("3"), null);
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThat(service.performance(compliance, PID, "30d")).isNotNull();
  }

  @Test
  void coversPositivePaginationAndCacheBranches() throws Exception {
    MedmatePrincipal support =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThat(service.performance(support, PID, "30d")).isNotNull();

    orderMetrics.ratingsResult =
        new RatingListResult(BigDecimal.ZERO, 0, Map.of(4, 1), List.of(), 0L);
    service.ratings(ops(), PID, null, "", "desc", 1, 1);
    service.ratings(ops(), PID, null, "created_at", "desc", null, null);
    service.ratings(ops(), PID, null, "rating", "desc", 5, 101);

    orderMetrics.ordersResult = new OrderListResult(List.of(), 0L);
    service.orders(
        ops(),
        PID,
        "DELIVERED",
        LocalDate.parse("2026-07-01"),
        LocalDate.parse("2026-07-24"),
        1,
        1);
    service.orders(ops(), PID, null, null, null, null, null);
    service.orders(ops(), PID, "DELIVERED", null, null, 5, 101);

    assertThat(AdminPharmacyPerformanceService.computeTrend(new BigDecimal("90"), null))
        .isEqualTo("STABLE");

    assertThatThrownBy(() -> service.sendAlert(ops(), PID, null, new BigDecimal("1"), null))
        .isInstanceOfSatisfying(
            AppException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_ALERT_TYPE"));

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(cacheMap(snapshot30d()));
    when(valueOps.get(AdminPharmacyPerformanceService.cacheKey(PID, "30D")))
        .thenReturn(null, "   ", json);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    AdminPharmacyPerformanceService redisSvc = newService(provider);
    snapshots.put(snapshot30d());
    assertThat(redisSvc.loadSnapshot(PID, "30D")).isNotNull();
    assertThat(redisSvc.loadSnapshot(PID, "30D").computedAt()).isNotNull();
  }

  @Test
  void recordConstructorsAndPortDefaults() {
    assertThat(new RatingListResult(null, 0, null, null, 0).distribution()).isEmpty();
    assertThat(new RatingListResult(BigDecimal.ONE, 1, Map.of(5, 1), List.of(), 1L).ratings())
        .isEmpty();
    assertThat(new OrderListResult(null, 0L).orders()).isEmpty();
    assertThat(new OrderListResult(List.of(), 0L).orders()).isEmpty();
    assertThat(
            new AlertRow(
                    Ids.newId(), PID, "LOW_FILL_RATE", null, new BigDecimal("1"), null, null, NOW)
                .channels())
        .isEmpty();
    assertThat(
            new AlertRow(
                    Ids.newId(),
                    PID,
                    "LOW_FILL_RATE",
                    null,
                    new BigDecimal("1"),
                    null,
                    List.of("IN_APP"),
                    NOW)
                .channels())
        .containsExactly("IN_APP");
    assertThat(AdminPharmacyPerformanceService.computeTrend(null, new BigDecimal("1")))
        .isEqualTo("STABLE");
    assertThat(AdminPharmacyPerformanceService.computeTrend(null, null)).isEqualTo("STABLE");

    orderMetrics.ratingsResult = new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
    service.ratings(ops(), PID, null, "rating", "", 1, 1);
    service.ratings(ops(), PID, null, "created_at", "desc", 1, 150);

    orderMetrics.ordersResult = new OrderListResult(List.of(), 0L);
    service.orders(ops(), PID, null, null, null, 1, 5);
    service.orders(ops(), PID, "  ", null, null, 1, 150);

    alertStore.inserted.clear();
    outboxStore.all().clear();
    service.sendAlert(ops(), PID, "LOW_FILL_RATE", new BigDecimal("1"), "   ");
  }

  private void localCacheCorrupt(AdminPharmacyPerformanceService svc, UUID pharmacyId) {
    try {
      var field = AdminPharmacyPerformanceService.class.getDeclaredField("localCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var cache = (java.util.concurrent.ConcurrentHashMap<String, Object>) field.get(svc);
      String key = AdminPharmacyPerformanceService.cacheKey(pharmacyId, "30D");
      var entryClass =
          Class.forName(
              "com.nammamedmate.pharmacy.application.AdminPharmacyPerformanceService$LocalCacheEntry");
      var ctor = entryClass.getDeclaredConstructor(String.class, Instant.class);
      ctor.setAccessible(true);
      cache.put(key, ctor.newInstance("{not-json", Instant.parse("2099-01-01T00:00:00Z")));
      assertThat(svc.loadSnapshot(pharmacyId, "30D")).isNotNull();
      cache.put(key, ctor.newInstance("{}", Instant.parse("2020-01-01T00:00:00Z")));
      assertThat(svc.loadSnapshot(pharmacyId, "30D")).isNotNull();
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Test
  void aggregatorWritesSnapshotsForActivePharmacies() {
    pharmacies.activeIds = List.of(PID);
    orderMetrics.periodMetrics =
        new PeriodMetrics(
            10,
            9,
            1,
            new BigDecimal("90.00"),
            new BigDecimal("85.00"),
            new BigDecimal("5.00"),
            new BigDecimal("2.00"),
            new BigDecimal("15.0"),
            0,
            new BigDecimal("4.50"),
            20,
            100_000L,
            (short) 0);
    PharmacyPerformanceAggregatorService aggregator =
        new PharmacyPerformanceAggregatorService(
            pharmacies, orderMetrics, snapshots, service, Clock.fixed(NOW, ZoneOffset.UTC));
    aggregator.aggregateAll();
    assertThat(snapshots.rows).hasSize(3);
    assertThat(snapshots.rows.stream().map(SnapshotRow::period).toList())
        .containsExactlyInAnyOrder("7D", "30D", "90D");
  }

  private static Map<String, Object> cacheMap(SnapshotRow row) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", row.id().toString());
    map.put("pharmacy_id", row.pharmacyId().toString());
    map.put("period", row.period());
    map.put("period_start", row.periodStart().toString());
    map.put("period_end", row.periodEnd().toString());
    map.put("orders_received", row.ordersReceived());
    map.put("orders_fulfilled", row.ordersFulfilled());
    map.put("orders_cancelled", row.ordersCancelled());
    map.put("fill_rate_pct", row.fillRatePct());
    map.put("on_time_prep_pct", row.onTimePrepPct());
    map.put("cancel_rate_pct", row.cancelRatePct());
    map.put("out_of_stock_rate_pct", row.outOfStockRatePct());
    map.put("avg_prep_minutes", row.avgPrepMinutes());
    map.put("complaint_count", row.complaintCount());
    map.put("avg_rating", row.avgRating());
    map.put("review_count", row.reviewCount());
    map.put("gmv_period_paise", row.gmvPeriodPaise());
    map.put("consecutive_low_fill_days", row.consecutiveLowFillDays());
    map.put("fill_rate_trend", row.fillRateTrend());
    map.put("cancel_rate_trend", row.cancelRateTrend());
    map.put("computed_at", row.computedAt().toString());
    return map;
  }

  static final class FakePharmacyStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> details = new ConcurrentHashMap<>();
    List<UUID> activeIds = List.of();

    void put(AdminDetailRow row) {
      details.put(row.pharmacyId(), row);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public List<AdminListRow> exportRows(ListFilter filter) {
      return List.of();
    }

    @Override
    public DirectorySummary directorySummary(Instant asOf) {
      return new DirectorySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, asOf);
    }

    @Override
    public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
      return Optional.ofNullable(details.get(pharmacyId));
    }

    @Override
    public Map<String, String> documentStatusSummary(UUID pharmacyId) {
      return Map.of();
    }

    @Override
    public String nextCode() {
      return "PHM-0001";
    }

    @Override
    public void approve(
        UUID pharmacyId,
        BigDecimal commissionPct,
        UUID zoneId,
        Instant activatedAt,
        Instant updatedAt) {}

    @Override
    public void reject(
        UUID pharmacyId,
        String rejectionReason,
        String rejectionDetails,
        boolean canReapply,
        Instant rejectedAt) {}

    @Override
    public void suspend(
        UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {}

    @Override
    public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {}

    @Override
    public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {}

    @Override
    public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {}

    @Override
    public List<UUID> listActivePharmacyIds() {
      return activeIds;
    }

    @Override
    public List<AdminListRow> listByIds(List<UUID> pharmacyIds) {
      return List.of();
    }
  }

  static final class FakeSnapshotStore implements PharmacyPerformanceSnapshotStore {
    final Map<String, SnapshotRow> byKey = new ConcurrentHashMap<>();
    final List<SnapshotRow> rows = new ArrayList<>();
    int findCalls;

    void put(SnapshotRow row) {
      byKey.put(row.pharmacyId() + ":" + row.period(), row);
    }

    @Override
    public Optional<SnapshotRow> find(UUID pharmacyId, String period) {
      findCalls++;
      return Optional.ofNullable(byKey.get(pharmacyId + ":" + period));
    }

    @Override
    public void upsert(SnapshotRow row, Instant updatedAt) {
      rows.add(row);
      put(row);
    }
  }

  static final class FakeAlertStore implements PerformanceAlertStore {
    final List<AlertRow> inserted = new ArrayList<>();

    @Override
    public void insert(AlertRow row) {
      inserted.add(row);
    }

    @Override
    public Optional<Instant> lastSentAt(UUID pharmacyId, String alertType, Instant since) {
      return inserted.stream()
          .filter(
              r ->
                  r.pharmacyId().equals(pharmacyId)
                      && r.alertType().equals(alertType)
                      && !r.sentAt().isBefore(since))
          .map(AlertRow::sentAt)
          .max(Instant::compareTo);
    }
  }

  static final class SeedableOrderMetrics implements PharmacyOrderMetricsPort {
    RatingListResult ratingsResult =
        new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
    OrderListResult ordersResult = new OrderListResult(List.of(), 0L);
    PeriodMetrics periodMetrics =
        new PeriodMetrics(
            0,
            0,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("0.0"),
            0,
            BigDecimal.ZERO,
            0,
            0L,
            (short) 0);
    LocalDate lastFrom;
    LocalDate lastTo;

    @Override
    public Performance performance(UUID pharmacyId) {
      return new Performance(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0L);
    }

    @Override
    public CommissionLedger commissionLedger(UUID pharmacyId) {
      return new CommissionLedger(0L, 0L, 0L, 0L, null, null);
    }

    @Override
    public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
      return List.of();
    }

    @Override
    public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
      return periodMetrics;
    }

    @Override
    public RatingListResult listRatings(
        UUID pharmacyId, Integer ratingFilter, String sort, String order, int limit, int offset) {
      return ratingsResult;
    }

    @Override
    public OrderListResult listOrders(
        UUID pharmacyId,
        String status,
        LocalDate fromDate,
        LocalDate toDate,
        int limit,
        int offset) {
      lastFrom = fromDate;
      lastTo = toDate;
      return ordersResult;
    }

    @Override
    public long annualGmvYtdPaise(UUID pharmacyId) {
      return 0L;
    }

    @Override
    public long gmvForPeriodPaise(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
      return 0L;
    }
  }
}
