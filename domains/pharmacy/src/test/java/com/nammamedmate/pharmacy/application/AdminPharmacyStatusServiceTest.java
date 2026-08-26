package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyCatalogueStatsClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyOrderMetricsClient;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.DirectorySummary;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.PageResult;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.OrderListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PeriodMetrics;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RatingListResult;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

class AdminPharmacyStatusServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:10:00Z");
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private FakeAdminStore store;
  private FakeZones zones;
  private FakeAudit audit;
  private InMemoryOutboxStore outboxStore;
  private RateLimiter rateLimiter;
  private PharmacyOrderMetricsPort orderMetrics;
  private PharmacyCatalogueStatsPort catalogueStats;
  private ObjectMapper objectMapper;
  private AdminPharmacyStatusService service;

  @BeforeEach
  void setUp() {
    store = new FakeAdminStore();
    zones = new FakeZones();
    zones.put(ZONE, new ZoneRecord(ZONE, "Koramangala Zone", true));
    audit = new FakeAudit();
    outboxStore = new InMemoryOutboxStore();
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    orderMetrics = new StubPharmacyOrderMetricsClient();
    catalogueStats = new StubPharmacyCatalogueStatsClient();
    objectMapper = new ObjectMapper();
    service = newService(null);
  }

  private AdminPharmacyStatusService newService(ObjectProvider<StringRedisTemplate> redis) {
    return new AdminPharmacyStatusService(
        store,
        zones,
        audit,
        orderMetrics,
        catalogueStats,
        rateLimiter,
        new OutboxPublisher(outboxStore, objectMapper),
        Clock.fixed(NOW, ZoneOffset.UTC),
        objectMapper,
        redis);
  }

  @Test
  void ac1_listActiveSortedByGmvTodayDescDefaultLimit50() {
    store.listRows = List.of(listRow(PID, "ACTIVE", 5000L), listRow(Ids.newId(), "ACTIVE", 1000L));
    store.listTotal = 2;
    var result =
        service.list(ops(), "ACTIVE", null, null, null, null, "gmv_today", "desc", null, null);
    assertThat(store.lastFilter.status()).isEqualTo("ACTIVE");
    assertThat(store.lastFilter.sort()).isEqualTo("gmv_today");
    assertThat(store.lastFilter.order()).isEqualTo("desc");
    assertThat(store.lastFilter.limit()).isEqualTo(50);
    assertThat(result.meta().limit()).isEqualTo(50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies =
        (List<Map<String, Object>>) result.data().get("pharmacies");
    assertThat(pharmacies).hasSize(2);
    assertThat(pharmacies.getFirst().get("status")).isEqualTo("ACTIVE");
    assertThat(pharmacies.getFirst().get("gmv_today")).isEqualTo(new BigDecimal("50.00"));
  }

  @Test
  void ac2_searchMinTwoCharsAndPassesFuzzyQuery() {
    store.listRows = List.of(listRow(PID, "ACTIVE", 0L));
    store.listTotal = 1;
    service.list(ops(), "ALL", null, null, null, "Sharma", null, null, 1, 50);
    assertThat(store.lastFilter.search()).isEqualTo("Sharma");

    assertThatThrownBy(() -> service.list(ops(), "ALL", null, null, null, "S", null, null, 1, 50))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void ac3_summaryChipsWithDataAsOf() {
    store.summary =
        new DirectorySummary(342, 18, 11, 7, 23, 289, 3820, 148250000L, 11860000L, 9425000L, NOW);
    Map<String, Object> data = service.summary(ops());
    assertThat(data.get("total_active")).isEqualTo(342L);
    assertThat(data.get("pending_kyc")).isEqualTo(18L);
    assertThat(data.get("gmv_today")).isEqualTo(new BigDecimal("1482500.00"));
    assertThat(data.get("commission_today")).isEqualTo(new BigDecimal("118600.00"));
    assertThat(data.get("payout_due")).isEqualTo(new BigDecimal("94250.00"));
    assertThat(data.get("data_as_of")).isEqualTo(NOW.toString());
    assertThat(data.get("cache_ttl_seconds")).isEqualTo(300);

    // second call hits local cache
    store.summary = new DirectorySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    Map<String, Object> cached = service.summary(ops());
    assertThat(((Number) cached.get("total_active")).longValue()).isEqualTo(342L);

    Map<String, Object> compliance = service.summary(principal(AuthRole.ADMIN_COMPLIANCE));
    assertThat(compliance).doesNotContainKeys("gmv_today", "commission_today", "payout_due");
  }

  @Test
  void ac4_detailIncludesPerformanceLedgerCatalogueAndRecentOrdersStructure() {
    store.put(detail("ACTIVE"));
    orderMetrics =
        new PharmacyOrderMetricsPort() {
          @Override
          public Performance performance(UUID pharmacyId) {
            return new Performance(
                new BigDecimal("91.20"),
                new BigDecimal("88.50"),
                new BigDecimal("3.10"),
                new BigDecimal("4.30"),
                128,
                842,
                48500000L);
          }

          @Override
          public CommissionLedger commissionLedger(UUID pharmacyId) {
            return new CommissionLedger(18500000L, 1480000L, 185000L, 1295000L, null, null);
          }

          @Override
          public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
            assertThat(limit).isGreaterThanOrEqualTo(5);
            return List.of(
                new RecentOrder(Ids.newId(), "ORD-1", "DELIVERED", 45000L, NOW.minusSeconds(60)));
          }

          @Override
          public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
            return new PeriodMetrics(
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
          }

          @Override
          public RatingListResult listRatings(
              UUID pharmacyId,
              Integer ratingFilter,
              String sort,
              String order,
              int limit,
              int offset) {
            return new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
          }

          @Override
          public OrderListResult listOrders(
              UUID pharmacyId,
              String status,
              LocalDate fromDate,
              LocalDate toDate,
              int limit,
              int offset) {
            return new OrderListResult(List.of(), 0L);
          }

          @Override
          public long annualGmvYtdPaise(UUID pharmacyId) {
            return 0L;
          }

          @Override
          public long gmvForPeriodPaise(
              UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
            return 0L;
          }
        };
    service = newService(null);
    Map<String, Object> data = service.detail(ops(), PID);
    assertThat(data.get("performance")).isInstanceOf(Map.class);
    assertThat(data.get("commission_ledger")).isInstanceOf(Map.class);
    assertThat(data.get("catalogue_stats")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recent = (List<Map<String, Object>>) data.get("recent_orders");
    assertThat(recent).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> perf = (Map<String, Object>) data.get("performance");
    assertThat(perf.get("fill_rate_pct")).isEqualTo(new BigDecimal("91.20"));
    assertThat(perf.get("gmv_30d")).isEqualTo(new BigDecimal("485000.00"));
  }

  @Test
  void ac5_supportOmitsCommissionFields() {
    store.listRows = List.of(listRow(PID, "ACTIVE", 1875000L));
    store.listTotal = 1;
    var result =
        service.list(
            principal(AuthRole.ADMIN_SUPPORT), "ACTIVE", null, null, null, null, null, null, 1, 50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies =
        (List<Map<String, Object>>) result.data().get("pharmacies");
    assertThat(pharmacies.getFirst()).containsKey("gmv_today");
    assertThat(pharmacies.getFirst()).doesNotContainKeys("commission_pct", "net_payout");

    store.put(detail("ACTIVE"));
    Map<String, Object> detailData = service.detail(principal(AuthRole.ADMIN_SUPPORT), PID);
    assertThat(detailData).doesNotContainKeys("commission_pct", "commission_ledger");

    assertThat(service.detail(principal(AuthRole.ADMIN_FINANCE), PID))
        .containsKey("commission_ledger");
    assertThat(service.detail(principal(AuthRole.ADMIN_SUPER), PID))
        .containsKey("commission_ledger");
    assertThat(service.detail(ops(), PID)).containsKey("commission_ledger");
  }

  @Test
  void ac6_exportSuspendedCsvWithHeaderTotalAndClampLimit() {
    store.listRows = List.of(listRow(PID, "SUSPENDED", 0L));
    store.listTotal = 3;
    store.exportRows = List.of(listRow(PID, "SUSPENDED", 0L));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      service.export(ops(), "SUSPENDED", null, null, null, out);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    String csv = out.toString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("# Namma MedMate Pharmacy Export | 2026-07-24 | Total rows: 3");
    assertThat(csv).contains("code,business_name,owner_name");
    assertThat(csv).contains("PHM-0001");
    assertThat(store.lastFilter.status()).isEqualTo("SUSPENDED");
    assertThat(store.lastFilter.limit()).isEqualTo(10_000);

    // list clamps limit > 200
    service.list(ops(), "ALL", null, null, null, null, null, null, 1, 999);
    assertThat(store.lastFilter.limit()).isEqualTo(200);
  }

  @Test
  void ac7_complianceExportForbidden() {
    assertThatThrownBy(
            () ->
                service.export(
                    principal(AuthRole.ADMIN_COMPLIANCE),
                    "SUSPENDED",
                    null,
                    null,
                    null,
                    new ByteArrayOutputStream()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void approveActivatesPharmacyAndNotifies() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.approve(ops(), PID, new BigDecimal("8.00"), ZONE, "ok", "1.1.1.1");
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(data.get("commission_pct")).isEqualTo(new BigDecimal("8.00"));
    assertThat(store.details.get(PID).status()).isEqualTo("ACTIVE");
    assertThat(audit.actions).contains("KYC_APPROVED");
    assertThat(outboxStore.all()).anyMatch(e -> e.type().contains("kyc_approved"));
  }

  @Test
  void complianceCannotApprove() {
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () ->
                service.approve(
                    principal(AuthRole.ADMIN_COMPLIANCE),
                    PID,
                    new BigDecimal("8.00"),
                    ZONE,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void rejectWithCanReapplyFalse() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.reject(ops(), PID, "Licence expired", "details", false, null);
    assertThat(data.get("status")).isEqualTo("REJECTED");
    assertThat(data.get("can_reapply")).isEqualTo(false);
    assertThat(store.details.get(PID).canReapply()).isFalse();
    assertThat(audit.actions).contains("KYC_REJECTED");
  }

  @Test
  void permanentSuspendBlocksReapply() {
    store.put(detail("ACTIVE"));
    Map<String, Object> data =
        service.suspend(ops(), PID, "Fraud", "PERMANENT", "internal", "10.0.0.1");
    assertThat(data.get("status")).isEqualTo("SUSPENDED");
    assertThat(data.get("is_online")).isEqualTo(false);
    assertThat(data.get("can_reapply")).isEqualTo(false);
    assertThat(audit.actions).contains("pharmacy.suspend");
  }

  @Test
  void reactivateRequiresNotesAndWritesAudit() {
    store.put(detail("SUSPENDED"));
    Map<String, Object> data = service.reactivate(ops(), PID, "Issue resolved", null);
    assertThat(data.get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("is_online")).isEqualTo(true);
    assertThat(store.details.get(PID).canReapply()).isTrue();
    assertThat(audit.actions).contains("PHARMACY_REACTIVATED");
    assertThat(audit.payloads.getLast().get("notes")).isEqualTo("Issue resolved");
  }

  @Test
  void reactivateOpsPreservesCanReapplyFalse_superRestores() {
    store.put(withCanReapply(detail("SUSPENDED"), false));
    service.reactivate(ops(), PID, "ops reopen", null);
    assertThat(store.details.get(PID).canReapply()).isFalse();

    store.put(withCanReapply(detail("SUSPENDED"), false));
    service.reactivate(principal(AuthRole.ADMIN_SUPER), PID, "super reopen", null);
    assertThat(store.details.get(PID).canReapply()).isTrue();
  }

  @Test
  void listKycSubmittedDefaultsToSubmittedAtAsc() {
    Instant older = NOW.minusSeconds(7200);
    Instant newer = NOW.minusSeconds(3600);
    store.listRows =
        List.of(
            listRow(PID, "KYC_SUBMITTED", older, older, 0L),
            listRow(Ids.newId(), "KYC_SUBMITTED", newer, newer, 0L));
    store.listTotal = 2;
    var result = service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, null, 1, 50);
    assertThat(store.lastFilter.sort()).isEqualTo("submitted_at");
    assertThat(store.lastFilter.order()).isEqualTo("asc");
    assertThat(result.meta().total()).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies =
        (List<Map<String, Object>>) result.data().get("pharmacies");
    assertThat(pharmacies.getFirst().get("urgency")).isEqualTo("LOW");
  }

  @Test
  void requestDocumentsResetsSlaAndNotifies() {
    store.put(detail("KYC_SUBMITTED"));
    Map<String, Object> data =
        service.requestDocuments(
            principal(AuthRole.ADMIN_COMPLIANCE),
            PID,
            List.of("PAN_CARD", "BANK_STATEMENT"),
            "Please re-upload PAN",
            null);
    assertThat(data.get("kyc_sla_reset_at")).isEqualTo(NOW.toString());
    assertThat(data.get("notifications_sent")).isEqualTo(List.of("WHATSAPP", "EMAIL", "IN_APP"));
    assertThat(store.details.get(PID).kycSlaResetAt()).isEqualTo(NOW);
  }

  @Test
  void detailIncludesAutoKycAndDocumentsSummary() {
    store.put(detail("KYC_SUBMITTED"));
    store.docSummary = Map.of("GSTIN_CERTIFICATE", "VERIFIED", "PAN_CARD", "REJECTED");
    Map<String, Object> data = service.detail(ops(), PID);
    assertThat(data.get("code")).isEqualTo("PHM-0042");
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) data.get("kyc");
    assertThat(kyc.get("auto_kyc_status")).isNull();
    assertThat(kyc.get("documents_summary")).isEqualTo(store.docSummary);
    assertThat(data.get("catalogue_stats")).isNotNull();
    assertThat(data.get("performance")).isNotNull();
  }

  @Test
  void approveErrors() {
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_ACTIVE");
    store.put(detail("PENDING_KYC"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("KYC_NOT_SUBMITTED");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("2"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
    assertThatThrownBy(
            () -> service.approve(ops(), PID, new BigDecimal("8"), Ids.newId(), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
    zones.put(ZONE, new ZoneRecord(ZONE, "x", false));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("8"), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_ZONE");
  }

  @Test
  void rejectSuspendReactivateValidation() {
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.reject(ops(), PID, "r", null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_ACTIVE");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, " ", null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
    assertThatThrownBy(() -> service.reject(ops(), PID, "r", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_SUSPENDED");
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "", "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "NOPE", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.suspend(ops(), PID, "temp", "TEMPORARY", null, null);
    assertThat(store.details.get(PID).canReapply()).isTrue();

    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, "n", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_SUSPENDED");
    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTES_REQUIRED");
  }

  @Test
  void requestDocumentsValidationAndNotFound() {
    assertThatThrownBy(
            () -> service.requestDocuments(ops(), Ids.newId(), List.of("PAN_CARD"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), "", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MESSAGE_REQUIRED");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("NOPE"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of(), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
  }

  @Test
  void listValidationAndUrgency() {
    store.listRows =
        List.of(
            listRow(PID, "ACTIVE", NOW.minusSeconds(50 * 3600), NOW.minusSeconds(50 * 3600), 0L));
    store.listTotal = 1;
    var high = service.list(ops(), "ACTIVE", null, null, null, null, "created_at", "desc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) high.data().get("pharmacies");
    assertThat(rows.getFirst().get("urgency")).isEqualTo("HIGH");

    store.listRows =
        List.of(
            listRow(PID, "ACTIVE", NOW.minusSeconds(30 * 3600), NOW.minusSeconds(30 * 3600), 0L));
    var med = service.list(ops(), "ALL", null, null, true, "Sharma", "business_name", "asc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> medRows = (List<Map<String, Object>>) med.data().get("pharmacies");
    assertThat(medRows.getFirst().get("urgency")).isEqualTo("MEDIUM");

    assertThatThrownBy(() -> service.list(ops(), "NOPE", null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.list(ops(), "ALL", null, "GOLD", null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(ops(), "ALL", null, null, null, null, "bad", null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_SORT_FIELD");
    assertThatThrownBy(
            () ->
                service.list(ops(), "ALL", null, null, null, null, "created_at", "sideways", 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void roleGatesAndRateLimit() {
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    // finance can list (directory)
    store.listRows = List.of();
    store.listTotal = 0;
    service.list(
        principal(AuthRole.ADMIN_FINANCE), null, null, null, null, null, null, null, 1, 10);
    store.put(detail("KYC_SUBMITTED"));
    service.detail(principal(AuthRole.ADMIN_FINANCE), PID);
    assertThatThrownBy(
            () ->
                service.requestDocuments(
                    principal(AuthRole.ADMIN_SUPPORT), PID, List.of("PAN_CARD"), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.list(ops(), null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void approveDefaultCommissionAndNotesTooLong() {
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () -> service.approve(ops(), PID, new BigDecimal("8"), ZONE, "x".repeat(501), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> data = service.approve(ops(), PID, null, ZONE, null, null);
    assertThat(data.get("commission_pct")).isEqualTo(new BigDecimal("8.00"));
  }

  @Test
  void summaryRedisCacheAndExportCsvEscaping() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(any())).thenReturn(null);
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    service = newService(provider);
    store.summary = new DirectorySummary(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    service.summary(ops());
    org.mockito.Mockito.verify(ops).set(any(), any(), any(java.time.Duration.class));

    when(ops.get(any()))
        .thenReturn(
            "{\"total_active\":9,\"pending_kyc\":0,\"kyc_submitted\":0,\"suspended\":0,\"rejected\":0,\"currently_online\":0,\"gmv_today\":0,\"commission_today\":0,\"orders_today\":0,\"payout_due\":0,\"data_as_of\":\""
                + NOW
                + "\",\"cache_ttl_seconds\":300}");
    assertThat(((Number) service.summary(ops()).get("total_active")).intValue()).isEqualTo(9);

    store.exportRows =
        List.of(
            new AdminListRow(
                PID,
                "PHM-1",
                "A,B \"C\"",
                "Own",
                "+91",
                "e@x.com",
                ZONE,
                "Zone",
                "ACTIVE",
                "FREE",
                true,
                NOW,
                NOW,
                NOW,
                null,
                new BigDecimal("1.00"),
                0,
                0,
                0L,
                new BigDecimal("0.00"),
                new BigDecimal("8.00"),
                0L,
                null));
    store.listTotal = 1;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(principal(AuthRole.ADMIN_FINANCE), null, null, null, null, out);
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"A,B \"\"C\"\"\"");
  }

  @Test
  void coversRemainingBranchesForJacoco() throws Exception {
    store.listRows = List.of();
    store.listTotal = 0;
    service.list(principal(AuthRole.ADMIN_SUPPORT), "  ", null, "  ", null, null, null, null, 0, 0);
    service.list(
        principal(AuthRole.ADMIN_COMPLIANCE),
        "KYC_SUBMITTED",
        ZONE,
        "FREE",
        false,
        null,
        "created_at",
        "desc",
        null,
        999);
    service.list(
        principal(AuthRole.ADMIN_SUPER), "ALL", null, null, null, null, null, null, 1, null);
    service.list(ops(), "ACTIVE", null, null, null, null, "orders_today", "asc", 1, 10);
    service.list(ops(), "ACTIVE", null, null, null, null, "rating", "desc", 1, 10);
    service.list(ops(), "ACTIVE", null, null, null, null, "fill_rate", "desc", 1, 10);

    store.listRows =
        List.of(
            new AdminListRow(
                PID, "PHM-1", "B", "O", "p", null, null, null, "ACTIVE", "FREE", true, null, null,
                null, null, null, 0, 0, 0L, null, null, 0L, null));
    store.listTotal = 1;
    var listed = service.list(ops(), "ACTIVE", null, null, null, null, "created_at", "asc", 1, 10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("pharmacies");
    assertThat(rows.getFirst().get("document_age_hours")).isEqualTo(0L);
    assertThat(rows.getFirst().get("submitted_at")).isNull();
    assertThat(rows.getFirst().get("created_at")).isNull();
    store.put(
        new AdminDetailRow(
            PID,
            "PHM-0042",
            "Sharma",
            "Rajesh",
            "+91",
            "e@x.com",
            "PHARMACY",
            null,
            "g",
            "d",
            null,
            "p",
            "KYC_SUBMITTED",
            "FREE",
            new BigDecimal("8.00"),
            null,
            null,
            false,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    Map<String, Object> detailData = service.detail(principal(AuthRole.ADMIN_SUPPORT), PID);
    @SuppressWarnings("unchecked")
    Map<String, Object> kyc = (Map<String, Object>) detailData.get("kyc");
    assertThat(kyc.get("auto_kyc_status")).isNull();
    assertThat(kyc.get("submitted_at")).isNull();
    assertThat(detailData.get("created_at")).isNull();
    assertThat(detailData).doesNotContainKey("commission_pct");
    service.detail(principal(AuthRole.ADMIN_SUPER), PID);
    assertThatThrownBy(() -> service.detail(ops(), Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    assertThatThrownBy(() -> service.detail(principal(AuthRole.CUSTOMER), PID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    Map<String, Object> complianceDetail =
        service.detail(principal(AuthRole.ADMIN_COMPLIANCE), PID);
    assertThat(complianceDetail).doesNotContainKey("commission_ledger");
    @SuppressWarnings("unchecked")
    Map<String, Object> perf = (Map<String, Object>) complianceDetail.get("performance");
    assertThat(perf).doesNotContainKey("gmv_30d");

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.approve(ops(), PID, new BigDecimal("21.00"), ZONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COMMISSION_PCT");
    service.approve(
        principal(AuthRole.ADMIN_SUPER), PID, new BigDecimal("10.5"), ZONE, "n", "9.9.9.9");

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, "r".repeat(201), null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(ops(), PID, "ok", "d".repeat(1001), true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.reject(ops(), PID, "bad licence", null, true, null);

    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r".repeat(501), "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.suspend(ops(), PID, "r", "TEMPORARY", "n".repeat(1001), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.suspend(ops(), PID, "hold", "TEMPORARY", "note", null);

    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, "n".repeat(501), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.reactivate(principal(AuthRole.ADMIN_SUPER), PID, "ok", null);

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(
            () -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), "m".repeat(1001), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.requestDocuments(
                    ops(), PID, java.util.Arrays.asList((String) null), "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");
    service.requestDocuments(
        principal(AuthRole.ADMIN_SUPER), PID, List.of("GSTIN_CERTIFICATE"), "please", null);

    assertThatThrownBy(
            () ->
                service.list(
                    principal(AuthRole.CUSTOMER), null, null, null, null, null, null, null, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    service.list(ops(), null, null, null, null, null, "  ", "  ", 1, 10);
    service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, "  ", 1, 10);
    service.list(ops(), "KYC_SUBMITTED", null, null, null, null, null, "desc", 1, 10);
    store.put(
        new AdminDetailRow(
            PID,
            "PHM-0042",
            "Sharma",
            "Rajesh",
            "+91",
            "e@x.com",
            "PHARMACY",
            Map.of(),
            "g",
            "d",
            null,
            "p",
            "KYC_SUBMITTED",
            "FREE",
            new BigDecimal("8.00"),
            ZONE,
            "Koramangala Zone",
            false,
            true,
            NOW,
            NOW,
            NOW,
            NOW,
            null,
            null,
            null,
            null,
            null,
            null));
    assertThat(
            ((Map<?, ?>) service.detail(principal(AuthRole.ADMIN_COMPLIANCE), PID).get("zone"))
                .get("zone_id"))
        .isEqualTo(ZONE.toString());

    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.reject(ops(), PID, null, null, true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
    store.put(detail("ACTIVE"));
    assertThatThrownBy(() -> service.suspend(ops(), PID, null, "TEMPORARY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    store.put(detail("SUSPENDED"));
    assertThatThrownBy(() -> service.reactivate(ops(), PID, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOTES_REQUIRED");
    store.put(detail("KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, List.of("PAN_CARD"), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MESSAGE_REQUIRED");
    assertThatThrownBy(() -> service.requestDocuments(ops(), PID, null, "m", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DOCUMENT_TYPES");

    // export truncation comment + rate limit + blank search
    store.listTotal = 10_001;
    store.exportRows = List.of(listRow(PID, "ACTIVE", 0L));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(ops(), "ALL", ZONE, "FREE", "  ", out);
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("truncated_at=10000");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(
            () -> service.export(ops(), null, null, null, null, new ByteArrayOutputStream()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> service.summary(ops()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    assertThat(AdminPharmacyStatusService.paiseToRupees(18750)).isEqualByComparingTo("187.50");
    assertThat(AdminPharmacyStatusService.canSeeCommission(AuthRole.ADMIN_SUPPORT)).isFalse();
    assertThat(AdminPharmacyStatusService.canSeeFinancial(AuthRole.ADMIN_COMPLIANCE)).isFalse();
  }

  @Test
  void coversCacheCsvAndDetailEdgeBranches() throws Exception {
    orderMetrics =
        new PharmacyOrderMetricsPort() {
          @Override
          public Performance performance(UUID pharmacyId) {
            return new Performance(
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                1,
                1,
                100L);
          }

          @Override
          public CommissionLedger commissionLedger(UUID pharmacyId) {
            return new CommissionLedger(
                1L, 1L, 1L, 1L, LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 24));
          }

          @Override
          public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
            return List.of(new RecentOrder(Ids.newId(), "ORD-2", "PENDING", 100L, null));
          }

          @Override
          public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
            return new PeriodMetrics(
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
          }

          @Override
          public RatingListResult listRatings(
              UUID pharmacyId,
              Integer ratingFilter,
              String sort,
              String order,
              int limit,
              int offset) {
            return new RatingListResult(BigDecimal.ZERO, 0, Map.of(), List.of(), 0L);
          }

          @Override
          public OrderListResult listOrders(
              UUID pharmacyId,
              String status,
              LocalDate fromDate,
              LocalDate toDate,
              int limit,
              int offset) {
            return new OrderListResult(List.of(), 0L);
          }

          @Override
          public long annualGmvYtdPaise(UUID pharmacyId) {
            return 0L;
          }

          @Override
          public long gmvForPeriodPaise(
              UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
            return 0L;
          }
        };
    service = newService(null);
    store.put(detail("ACTIVE"));
    Map<String, Object> withDates = service.detail(ops(), PID);
    @SuppressWarnings("unchecked")
    Map<String, Object> ledger = (Map<String, Object>) withDates.get("commission_ledger");
    assertThat(ledger.get("last_settlement_date")).isEqualTo("2026-07-17");
    Map<String, Object> compliance = service.detail(principal(AuthRole.ADMIN_COMPLIANCE), PID);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orders = (List<Map<String, Object>>) compliance.get("recent_orders");
    assertThat(orders.getFirst()).doesNotContainKey("amount");
    assertThat(orders.getFirst().get("created_at")).isNull();

    store.listRows =
        List.of(
            new AdminListRow(
                PID,
                "PHM-1",
                "B",
                "O",
                "p",
                null,
                null,
                "NameOnlyZone",
                "ACTIVE",
                "FREE",
                true,
                NOW,
                NOW,
                NOW,
                null,
                new BigDecimal("1"),
                0,
                0,
                0L,
                new BigDecimal("1"),
                new BigDecimal("8"),
                0L,
                null));
    store.listTotal = 1;
    var listed =
        service.list(
            principal(AuthRole.ADMIN_COMPLIANCE),
            "ACTIVE",
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            10);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("pharmacies");
    assertThat(((Map<?, ?>) rows.getFirst().get("zone")).get("zone_name"))
        .isEqualTo("NameOnlyZone");
    assertThat(rows.getFirst()).doesNotContainKey("gmv_today");

    store.exportRows =
        List.of(
            new AdminListRow(
                PID, null, null, null, null, null, null, null, "ACTIVE", "FREE", false, null, null,
                null, null, null, 0, 0, 0L, null, null, 0L, null),
            new AdminListRow(
                Ids.newId(),
                "PHM-2",
                "line\nbreak",
                "O",
                "p",
                "e",
                ZONE,
                "Z",
                "ACTIVE",
                "FREE",
                true,
                NOW,
                NOW,
                NOW,
                null,
                new BigDecimal("1"),
                0,
                0,
                0L,
                new BigDecimal("1"),
                new BigDecimal("8"),
                0L,
                NOW),
            new AdminListRow(
                Ids.newId(),
                "PHM-3",
                "has\"quote",
                "O",
                "p",
                "e",
                ZONE,
                "Z",
                "ACTIVE",
                "FREE",
                true,
                NOW,
                NOW,
                NOW,
                null,
                new BigDecimal("1"),
                0,
                0,
                0L,
                new BigDecimal("1"),
                new BigDecimal("8"),
                0L,
                NOW));
    store.listTotal = 3;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.export(ops(), null, null, null, null, out);
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"line\nbreak\"");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"has\"\"quote\"");
    ByteArrayOutputStream out2 = new ByteArrayOutputStream();
    service.export(principal(AuthRole.ADMIN_SUPER), null, null, null, null, out2);
    assertThat(out2.size()).isPositive();
    assertThatThrownBy(
            () ->
                service.export(
                    principal(AuthRole.ADMIN_SUPPORT),
                    null,
                    null,
                    null,
                    null,
                    new ByteArrayOutputStream()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // redis blank + corrupt cache + write failure + expired local
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> opsVal = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(opsVal);
    when(opsVal.get(any())).thenReturn("   ");
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    when(broken.readValue(
            any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new RuntimeException("bad"));
    service =
        new AdminPharmacyStatusService(
            store,
            zones,
            audit,
            orderMetrics,
            catalogueStats,
            rateLimiter,
            new OutboxPublisher(outboxStore, objectMapper),
            Clock.fixed(NOW, ZoneOffset.UTC),
            broken,
            provider);
    store.summary = new DirectorySummary(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    // write fails silently; still returns computed
    assertThat(((Number) service.summary(ops()).get("total_active")).intValue()).isEqualTo(2);

    // corrupt redis JSON → catch returns null, recomputes
    when(opsVal.get(any())).thenReturn("{not-json");
    when(provider.getIfAvailable()).thenReturn(redis);
    service =
        new AdminPharmacyStatusService(
            store,
            zones,
            audit,
            new StubPharmacyOrderMetricsClient(),
            new StubPharmacyCatalogueStatsClient(),
            rateLimiter,
            new OutboxPublisher(outboxStore, objectMapper),
            Clock.fixed(NOW, ZoneOffset.UTC),
            objectMapper,
            provider);
    store.summary = new DirectorySummary(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    assertThat(((Number) service.summary(ops()).get("total_active")).intValue()).isEqualTo(4);

    // expired local cache entry
    service = newService(null);
    Class<?> entryClass =
        Class.forName(
            "com.nammamedmate.pharmacy.application.AdminPharmacyStatusService$LocalCacheEntry");
    var entryCtor = entryClass.getDeclaredConstructor(String.class, Instant.class);
    entryCtor.setAccessible(true);
    Object expired = entryCtor.newInstance("{\"total_active\":99}", NOW.minusSeconds(1));
    var cacheField = AdminPharmacyStatusService.class.getDeclaredField("localSummaryCache");
    cacheField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> local = (Map<String, Object>) cacheField.get(service);
    local.put("admin:pharmacies:summary", expired);
    store.summary = new DirectorySummary(6, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    assertThat(((Number) service.summary(ops()).get("total_active")).intValue()).isEqualTo(6);

    assertThatThrownBy(
            () -> service.export(null, null, null, null, null, new ByteArrayOutputStream()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.summary(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    // ObjectProvider null getIfAvailable
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
    when(emptyProvider.getIfAvailable()).thenReturn(null);
    service = newService(emptyProvider);
    store.summary = new DirectorySummary(5, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    assertThat(((Number) service.summary(ops()).get("total_active")).intValue()).isEqualTo(5);
  }

  private static MedmatePrincipal ops() {
    return principal(AuthRole.ADMIN_OPERATIONS);
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids.newId(), role, null, TokenScope.FULL, "j");
  }

  private static AdminDetailRow detail(String status) {
    return new AdminDetailRow(
        PID,
        "PHM-0042",
        "Sharma Medical",
        "Rajesh",
        "+919876543210",
        "r@s.com",
        "PHARMACY",
        Map.of("city", "Bengaluru", "pincode", "560034"),
        "29AABCS1429B1ZB",
        "KA/DL/1",
        null,
        "AABCS1429B",
        status,
        "FREE",
        new BigDecimal("8.00"),
        null,
        null,
        false,
        true,
        NOW.minusSeconds(3600),
        NOW.minusSeconds(86400),
        NOW.minusSeconds(3600),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static AdminDetailRow withCanReapply(AdminDetailRow row, boolean canReapply) {
    return new AdminDetailRow(
        row.pharmacyId(),
        row.code(),
        row.businessName(),
        row.ownerName(),
        row.phone(),
        row.email(),
        row.businessType(),
        row.address(),
        row.gstin(),
        row.drugLicenceNumber(),
        row.fssaiNumber(),
        row.panNumber(),
        row.status(),
        row.plan(),
        row.commissionPct(),
        row.zoneId(),
        row.zoneName(),
        row.online(),
        canReapply,
        row.kycSubmittedAt(),
        row.createdAt(),
        row.updatedAt(),
        row.planExpiresAt(),
        row.rejectionReason(),
        row.rejectionDetails(),
        row.activatedAt(),
        row.suspendedAt(),
        row.suspendType(),
        row.kycSlaResetAt());
  }

  private static AdminListRow listRow(UUID id, String status, long gmvPaise) {
    return listRow(id, status, NOW.minusSeconds(3600), NOW.minusSeconds(3600), gmvPaise);
  }

  private static AdminListRow listRow(
      UUID id, String status, Instant submitted, Instant ageAnchor, long gmvPaise) {
    return new AdminListRow(
        id,
        "PHM-0001",
        "Sharma",
        "Rajesh",
        "+9198",
        "r@s.com",
        ZONE,
        "Koramangala Zone",
        status,
        "FREE",
        false,
        submitted,
        NOW.minusSeconds(86400),
        ageAnchor,
        "PARTIAL",
        new BigDecimal("4.30"),
        128,
        34,
        gmvPaise,
        new BigDecimal("91.20"),
        new BigDecimal("8.00"),
        172500L,
        NOW);
  }

  static final class FakeAdminStore implements AdminPharmacyStore {
    final Map<UUID, AdminDetailRow> details = new ConcurrentHashMap<>();
    ListFilter lastFilter;
    List<AdminListRow> listRows = List.of();
    List<AdminListRow> exportRows = List.of();
    long listTotal;
    DirectorySummary summary = new DirectorySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW);
    Map<String, String> docSummary = Map.of();

    void put(AdminDetailRow row) {
      details.put(row.pharmacyId(), row);
    }

    @Override
    public PageResult list(ListFilter filter) {
      lastFilter = filter;
      return new PageResult(listRows, listTotal);
    }

    @Override
    public List<AdminListRow> exportRows(ListFilter filter) {
      lastFilter = filter;
      return exportRows.isEmpty() ? listRows : exportRows;
    }

    @Override
    public DirectorySummary directorySummary(Instant asOf) {
      return summary;
    }

    @Override
    public Optional<AdminDetailRow> findDetail(UUID pharmacyId) {
      return Optional.ofNullable(details.get(pharmacyId));
    }

    @Override
    public Map<String, String> documentStatusSummary(UUID pharmacyId) {
      return docSummary;
    }

    @Override
    public String nextCode() {
      return "PHM-0099";
    }

    @Override
    public void approve(
        UUID pharmacyId,
        BigDecimal commissionPct,
        UUID zoneId,
        Instant activatedAt,
        Instant updatedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "ACTIVE",
              cur.plan(),
              commissionPct,
              zoneId,
              cur.zoneName(),
              true,
              cur.canReapply(),
              cur.kycSubmittedAt(),
              cur.createdAt(),
              updatedAt,
              cur.planExpiresAt(),
              null,
              null,
              activatedAt,
              null,
              null,
              cur.kycSlaResetAt()));
    }

    @Override
    public void reject(
        UUID pharmacyId,
        String rejectionReason,
        String rejectionDetails,
        boolean canReapply,
        Instant rejectedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "REJECTED",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              cur.zoneName(),
              false,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              rejectedAt,
              cur.planExpiresAt(),
              rejectionReason,
              rejectionDetails,
              cur.activatedAt(),
              cur.suspendedAt(),
              cur.suspendType(),
              cur.kycSlaResetAt()));
    }

    @Override
    public void suspend(
        UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "SUSPENDED",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              cur.zoneName(),
              false,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              suspendedAt,
              cur.planExpiresAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              cur.activatedAt(),
              suspendedAt,
              suspendType,
              cur.kycSlaResetAt()));
    }

    @Override
    public void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              "ACTIVE",
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              cur.zoneName(),
              true,
              canReapply,
              cur.kycSubmittedAt(),
              cur.createdAt(),
              reactivatedAt,
              cur.planExpiresAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              reactivatedAt,
              null,
              null,
              cur.kycSlaResetAt()));
    }

    @Override
    public void resetKycSla(UUID pharmacyId, Instant slaResetAt) {
      AdminDetailRow cur = details.get(pharmacyId);
      details.put(
          pharmacyId,
          new AdminDetailRow(
              cur.pharmacyId(),
              cur.code(),
              cur.businessName(),
              cur.ownerName(),
              cur.phone(),
              cur.email(),
              cur.businessType(),
              cur.address(),
              cur.gstin(),
              cur.drugLicenceNumber(),
              cur.fssaiNumber(),
              cur.panNumber(),
              cur.status(),
              cur.plan(),
              cur.commissionPct(),
              cur.zoneId(),
              cur.zoneName(),
              cur.online(),
              cur.canReapply(),
              cur.kycSubmittedAt(),
              cur.createdAt(),
              slaResetAt,
              cur.planExpiresAt(),
              cur.rejectionReason(),
              cur.rejectionDetails(),
              cur.activatedAt(),
              cur.suspendedAt(),
              cur.suspendType(),
              slaResetAt));
    }

    @Override
    public void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt) {}

    @Override
    public List<UUID> listActivePharmacyIds() {
      return details.values().stream()
          .filter(r -> "ACTIVE".equals(r.status()))
          .map(AdminDetailRow::pharmacyId)
          .toList();
    }

    @Override
    public List<AdminListRow> listByIds(List<UUID> pharmacyIds) {
      return List.of();
    }
  }

  static final class FakeZones implements ZoneStore {
    final Map<UUID, ZoneRecord> byId = new LinkedHashMap<>();

    void put(UUID id, ZoneRecord z) {
      byId.put(id, z);
    }

    @Override
    public Optional<ZoneRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ZoneStore.AdminZoneRow> listForAdmin(String city, Boolean isActive) {
      return List.of();
    }
  }

  static final class FakeAudit implements AuditLogStore {
    final List<String> actions = new ArrayList<>();
    final List<Map<String, Object>> payloads = new ArrayList<>();

    @Override
    public void append(AuditLogRecord record) {
      actions.add(record.action());
      payloads.add(record.payload());
    }
  }
}
