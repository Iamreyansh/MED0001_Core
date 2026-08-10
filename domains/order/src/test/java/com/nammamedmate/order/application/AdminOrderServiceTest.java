package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.adapter.out.cache.RedisLiveFeedCache;
import com.nammamedmate.order.adapter.out.persistence.LocalExportObjectStore;
import com.nammamedmate.order.adapter.out.persistence.StubPrescriptionAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubRiderLookupAdapter;
import com.nammamedmate.order.application.AdminOrderService.ListResult;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStatusEventStore;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStore;
import com.nammamedmate.order.application.port.out.AdminOrderExportStore;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminListFilter;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminOrderListRow;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminStaffName;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.CustomerAdminView;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.PharmacyAdminView;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.SummaryAgg;
import com.nammamedmate.order.application.port.out.ExportObjectStore;
import com.nammamedmate.order.application.port.out.ExternalDisputeBannerPort;
import com.nammamedmate.order.application.port.out.LiveFeedCachePort;
import com.nammamedmate.order.application.port.out.OrderDisputeStore;
import com.nammamedmate.order.application.port.out.OrderNoteStore;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.AdminOrderExportJob;
import com.nammamedmate.order.domain.ExportJobStatus;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderDispute;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderNote;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOrderServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0008-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("cccccccc-0008-4000-8000-000000000001");
  private static final UUID RIDER = UUID.fromString("dddddddd-0008-4000-8000-000000000001");
  private static final UUID RIDER2 = UUID.fromString("dddddddd-0008-4000-8000-000000000002");
  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @TempDir java.nio.file.Path tempDir;
  @Mock private RateLimiter rateLimiter;

  private InMemoryOrderStore orders;
  private InMemoryOrderStatusEventStore events;
  private InMemoryQuery query;
  private InMemoryDisputeStore disputes;
  private InMemoryNoteStore notes;
  private InMemoryExportStore exportJobs;
  private LiveFeedCachePort cache;
  private AdminOrderService service;
  private final ObjectMapper mapper = new ObjectMapper();

  private final MedmatePrincipal ops =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    orders = new InMemoryOrderStore();
    events = new InMemoryOrderStatusEventStore();
    query = new InMemoryQuery(orders);
    disputes = new InMemoryDisputeStore();
    notes = new InMemoryNoteStore();
    exportJobs = new InMemoryExportStore();
    cache = new RedisLiveFeedCache(null);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    Executor sync = Runnable::run;
    service =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new LocalExportObjectStore(tempDir, "file://" + tempDir),
            cache,
            new StubRiderLookupAdapter(),
            new StubPrescriptionAdapter(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            mapper,
            sync);
  }

  @Test
  void ac1_slaRiskSegmentOnlyNearDeadlineOrders() {
    Order risk = seedLive(T0.plusSeconds(120)); // 2 min remaining
    Order safe = seedLive(T0.plusSeconds(600)); // 10 min
    query.seedRow(risk, "Ravi", "Sai", "Koramangala", new BigDecimal("10"), false);
    query.seedRow(safe, "Meena", "Sai", "Koramangala", new BigDecimal("10"), false);

    ListResult result =
        service.list(ops, "SLA_RISK", null, null, null, null, null, null, null, null, 1, 20, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.data().get("orders");
    assertThat(list).hasSize(1);
    assertThat(list.getFirst().get("order_id")).isEqualTo(risk.id().toString());
  }

  @Test
  void ac2_complianceDetailRedactsRxFileUrl() {
    UUID rx = UUID.randomUUID();
    Order order = seedOrder(OrderStatus.OUT_FOR_DELIVERY, T0.plusSeconds(600), rx);
    query.seedPharmacy(PH1, "Sai", "Koramangala", new BigDecimal("10"));
    query.seedCustomer(CUST, "Ravi", "+9198", 5, 10000);

    Map<String, Object> detail = service.detail(compliance, order.id());
    @SuppressWarnings("unchecked")
    Map<String, Object> card = (Map<String, Object>) detail.get("prescription_card");
    assertThat(card.get("id")).isEqualTo(rx.toString());
    assertThat(card.get("type")).isEqualTo("E_PRESCRIPTION");
    assertThat(card).doesNotContainKey("file_url");
  }

  @Test
  void ac3_disputeBannerVisibleOnDetail() {
    Order order = seedOrder(OrderStatus.DELIVERED, null, null);
    query.seedPharmacy(PH1, "Sai", "Area", new BigDecimal("8"));
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);

    service.flagDispute(support, order.id(), "Not delivered", "RIDER");
    Map<String, Object> detail = service.detail(ops, order.id());
    assertThat(detail.get("is_disputed")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> banner = (Map<String, Object>) detail.get("dispute_banner");
    assertThat(banner.get("liable_party")).isEqualTo("RIDER");
    assertThat(banner.get("reason")).isEqualTo("Not delivered");
  }

  @Test
  void supportExternalDisputeBannerVisibleOnDetail() throws Exception {
    Order order = seedOrder(OrderStatus.DELIVERED, null, null);
    query.seedPharmacy(PH1, "Sai", "Area", new BigDecimal("8"));
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    ExternalDisputeBannerPort port =
        orderId ->
            Optional.of(
                Map.of(
                    "dispute_id",
                    "DSP-20260724-000001",
                    "status",
                    "OPEN",
                    "reason",
                    "Wrong items",
                    "liable_party",
                    "PHARMACY"));
    var field = AdminOrderService.class.getDeclaredField("externalDisputeBanners");
    field.setAccessible(true);
    field.set(service, port);
    Map<String, Object> detail = service.detail(ops, order.id());
    assertThat(detail.get("is_disputed")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> banner = (Map<String, Object>) detail.get("dispute_banner");
    assertThat(banner.get("dispute_id")).isEqualTo("DSP-20260724-000001");
  }

  @Test
  void supportExternalDisputeBannerAbsentLeavesUndisputed() throws Exception {
    Order order = seedOrder(OrderStatus.DELIVERED, null, null);
    query.seedPharmacy(PH1, "Sai", "Area", new BigDecimal("8"));
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    var field = AdminOrderService.class.getDeclaredField("externalDisputeBanners");
    field.setAccessible(true);
    field.set(service, (ExternalDisputeBannerPort) orderId -> Optional.empty());
    Map<String, Object> detail = service.detail(ops, order.id());
    assertThat(detail.get("is_disputed")).isEqualTo(false);
  }

  @Test
  void ac4_financeNoteVisibleInternally() {
    Order order = seedOrder(OrderStatus.ACCEPTED, T0.plusSeconds(900), null);
    query.seedPharmacy(PH1, "Sai", "Area", new BigDecimal("8"));
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    query.seedAdmin(ADMIN, "Fin Admin");

    Map<String, Object> created = service.addNote(finance, order.id(), "Called customer", true);
    assertThat(created.get("note_id")).isNotNull();

    Map<String, Object> detail = service.detail(ops, order.id());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> internal = (List<Map<String, Object>>) detail.get("internal_notes");
    assertThat(internal).hasSize(1);
    assertThat(internal.getFirst().get("note")).isEqualTo("Called customer");
    assertThat(internal.getFirst().get("is_pinned")).isEqualTo(true);
  }

  @Test
  void ac5_deleteNoteGuardAllowsAdmin() {
    service.requireNoteDeleteDenied(ops);
    assertThatThrownBy(() -> service.requireNoteDeleteDenied(null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
  }

  @Test
  void ac6_liveFeedOrdersSortedBySlaRisk() {
    Order risk = seedLive(T0.plusSeconds(90));
    Order safe = seedLive(T0.plusSeconds(900));
    query.seedRow(risk, "A", "Ph", "Area", BigDecimal.TEN, false);
    query.seedRow(safe, "B", "Ph", "Area", BigDecimal.TEN, false);

    Map<String, Object> feed1 = service.liveFeed(ops);
    assertThat(feed1.get("total_live")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orders1 = (List<Map<String, Object>>) feed1.get("orders");
    assertThat(orders1.getFirst().get("order_id")).isEqualTo(risk.id().toString());
    assertThat(orders1.getFirst().get("sla_risk")).isEqualTo(true);

    // cached hit
    Map<String, Object> feed2 = service.liveFeed(ops);
    assertThat(feed2.get("total_live")).isEqualTo(2);
  }

  @Test
  void ac7_asyncExportWhenOverThreshold() {
    query.forceCount = 15_000;
    ListResult result =
        service.list(ops, "ALL", null, null, null, null, null, null, null, null, 1, 20, true);
    assertThat(result.data().get("status")).isEqualTo("PROCESSING");
    assertThat(result.data().get("job_id")).isNotNull();
    assertThat(result.data().get("download_url")).isNull();

    UUID jobId = UUID.fromString(String.valueOf(result.data().get("job_id")));
    AdminOrderExportJob job = exportJobs.findById(jobId).orElseThrow();
    assertThat(job.status()).isEqualTo(ExportJobStatus.READY);
    assertThat(job.s3Key()).isNotBlank();
    Map<String, Object> status = service.exportJobStatus(ops, jobId);
    assertThat(status.get("status")).isEqualTo("READY");
    assertThat(status.get("download_url")).isNotNull();
  }

  @Test
  void ac8_reassignRiderRecordsAdminStatusEvent() {
    Order order = seedOrder(OrderStatus.OUT_FOR_DELIVERY, T0.plusSeconds(600), null);
    order.assignRider(RIDER, T0);
    orders.update(order);

    Map<String, Object> data =
        service.reassignRider(superAdmin, order.id(), RIDER2, "Original rider unavailable");
    assertThat(data.get("new_rider_id")).isEqualTo(RIDER2.toString());
    assertThat(data.get("previous_rider_id")).isEqualTo(RIDER.toString());

    List<OrderStatusEvent> ev = events.listByOrderId(order.id());
    assertThat(ev).isNotEmpty();
    OrderStatusEvent last = ev.getLast();
    assertThat(last.actorType()).isEqualTo(ActorType.ADMIN);
    assertThat(last.notes()).contains("Rider reassigned");
  }

  @Test
  void syncExportUnderThreshold() throws Exception {
    Order order = seedLive(T0.plusSeconds(600));
    query.seedRow(order, "Ravi", "Sai", "Area", BigDecimal.TEN, false);
    ListResult result =
        service.list(ops, "ALL", null, null, null, null, null, null, null, null, 1, 20, true);
    assertThat(result.data().get("status")).isEqualTo("READY");
    assertThat(result.data().get("download_url")).isNotNull();
    assertThat(Files.list(tempDir).findAny()).isPresent();
  }

  @Test
  void rbacAndValidationBranches() {
    assertThatThrownBy(() -> service.liveFeed(finance))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.flagDispute(finance, UUID.randomUUID(), "x", "RIDER"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.addNote(compliance, UUID.randomUUID(), "n", false))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(
            () ->
                service.list(
                    ops, "NOPE", null, null, null, null, null, null, null, null, 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reassignRider(ops, UUID.randomUUID(), null, "r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flagDispute(support, UUID.randomUUID(), "   ", "RIDER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flagDispute(support, UUID.randomUUID(), "ok", "X"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order delivered = seedOrder(OrderStatus.DELIVERED, null, null);
    assertThatThrownBy(() -> service.reassignRider(ops, delivered.id(), RIDER, "late"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(422);

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.liveFeed(ops))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(429);
  }

  @Test
  void listSummaryAndFilters() {
    Order o = seedLive(T0.plusSeconds(600));
    query.seedRow(o, "Ravi", "Sai", "Koramangala", new BigDecimal("10"), true);
    ListResult result =
        service.list(
            ops,
            "LIVE",
            "Ravi",
            PH1,
            null,
            null,
            "COD",
            true,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
            1,
            20,
            false);
    assertThat(result.meta()).isNotNull();
    assertThat(result.data().get("summary")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) result.data().get("summary");
    assertThat(summary.get("total_orders")).isNotNull();
  }

  @Test
  void duplicateDisputeAndNoteValidation() {
    Order order = seedOrder(OrderStatus.PACKING, T0.plusSeconds(500), null);
    service.flagDispute(ops, order.id(), "first", "PHARMACY");
    assertThatThrownBy(() -> service.flagDispute(ops, order.id(), "second", "PHARMACY"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(409);
    assertThatThrownBy(() -> service.addNote(ops, order.id(), " ", false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    String longNote = "x".repeat(2001);
    assertThatThrownBy(() -> service.addNote(ops, order.id(), longNote, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    String longReason = "r".repeat(501);
    Order other = seedOrder(OrderStatus.ACCEPTED, T0.plusSeconds(400), null);
    assertThatThrownBy(() -> service.flagDispute(ops, other.id(), longReason, "CUSTOMER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void commissionHelperAndDetailWithoutRx() {
    assertThat(AdminOrderQueryPort.commissionPaise(10000, new BigDecimal("10"))).isEqualTo(1000);
    assertThat(AdminOrderQueryPort.commissionPaise(10000, null)).isEqualTo(0);
    Order order = seedOrder(OrderStatus.PENDING_ACCEPTANCE, T0.plusSeconds(1000), null);
    query.seedPharmacy(PH1, "Sai", "Area", new BigDecimal("12.5"));
    query.seedCustomer(CUST, "Ravi", "+91", 2, 500);
    Map<String, Object> detail = service.detail(ops, order.id());
    assertThat(detail.get("prescription_card")).isNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> bill = (Map<String, Object>) detail.get("bill");
    assertThat((BigDecimal) bill.get("commission_rate_pct"))
        .isEqualByComparingTo(new BigDecimal("12.5"));
  }

  @Test
  void moreBranchesForCoverage() {
    Order withRider = seedOrder(OrderStatus.OUT_FOR_DELIVERY, T0.plusSeconds(600), null);
    withRider.assignRider(RIDER, T0);
    withRider.clearDeliveryOtp(T0);
    orders.update(withRider);
    query.seedPharmacy(PH1, "Sai", "Area", BigDecimal.TEN);
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    events.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            withRider.id(),
            OrderStatus.ACCEPTED,
            OrderStatus.PACKING,
            ActorType.PHARMACY,
            UUID.randomUUID(),
            "packing",
            T0));

    Map<String, Object> detail = service.detail(ops, withRider.id());
    assertThat(detail.get("delivery_partner")).isNotNull();

    Map<String, Object> reassigned = service.reassignRider(ops, withRider.id(), RIDER2, "swap");
    assertThat(reassigned.get("previous_rider_id")).isEqualTo(RIDER.toString());

    Order noPrev = seedOrder(OrderStatus.READY_FOR_PICKUP, T0.plusSeconds(500), null);
    Map<String, Object> firstAssign = service.reassignRider(ops, noPrev.id(), RIDER, "assign");
    assertThat(firstAssign.get("previous_rider_id")).isNull();

    assertThatThrownBy(() -> service.reassignRider(ops, noPrev.id(), RIDER, "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    ops, "ALL", null, null, null, null, "NOPE", null, null, null, 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order cancelled = seedOrder(OrderStatus.CANCELLED, null, null);
    query.seedRow(cancelled, "X", "P", "A", BigDecimal.TEN, false);
    Order delivered = seedOrder(OrderStatus.DELIVERED, null, null);
    query.seedRow(delivered, "Y", "P", "A", BigDecimal.TEN, true);
    service.list(ops, "CANCELLED", null, null, null, null, null, null, null, null, 1, 20, false);
    service.list(ops, "DELIVERED", null, null, null, null, null, null, null, null, 1, 20, false);
    service.list(ops, "DISPUTES", null, null, null, null, null, null, null, null, 1, 20, false);

    cache.put(RedisLiveFeedCache.KEY, "{not-json", java.time.Duration.ofSeconds(10));
    Map<String, Object> rebuilt = service.liveFeed(ops);
    assertThat(rebuilt.get("orders")).isNotNull();

    assertThatThrownBy(() -> service.exportJobStatus(ops, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(404);
    assertThatThrownBy(() -> service.detail(ops, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(404);
    assertThatThrownBy(() -> service.detail(null, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);

    // processExportJob failure marks FAILED
    UUID jobId = UUID.randomUUID();
    exportJobs.insert(
        new AdminOrderExportJob(
            jobId, ADMIN, "{bad", null, ExportJobStatus.PROCESSING, null, T0, null));
    // force failure via exploding query count path: replace query temporarily
    query.forceCount = null;
    // corrupt filters still parse to ALL; mark failed by throwing in listAll using force
    AdminOrderService exploding =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new ExportObjectStore() {
              @Override
              public void put(String key, byte[] bytes, String contentType) {
                throw new RuntimeException("boom");
              }

              @Override
              public String createDownloadUrl(String key) {
                return "x";
              }
            },
            cache,
            new StubRiderLookupAdapter(),
            new StubPrescriptionAdapter(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            mapper,
            Runnable::run);
    assertThatThrownBy(() -> exploding.processExportJob(jobId))
        .isInstanceOf(RuntimeException.class);
    assertThat(exportJobs.findById(jobId).orElseThrow().status()).isEqualTo(ExportJobStatus.FAILED);

    assertThatThrownBy(() -> service.flagDispute(support, withRider.id(), "dup", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.detail(ops, null))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(400);
    assertThatThrownBy(() -> service.processExportJob(UUID.randomUUID()))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(404);

    // breached live-feed (fresh cache) + csv quoting + parseFilters via export
    LiveFeedCachePort freshCache = new RedisLiveFeedCache(null);
    AdminOrderService liveSvc =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new LocalExportObjectStore(tempDir, "file://" + tempDir),
            freshCache,
            new StubRiderLookupAdapter(),
            new StubPrescriptionAdapter(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            mapper,
            Runnable::run);
    Order breached = seedLive(T0.minusSeconds(60));
    breached.markSlaBreached(T0);
    orders.update(breached);
    query.seedRow(breached, null, "Ph,A", "Area\nX", BigDecimal.TEN, false);
    Map<String, Object> breachedFeed = liveSvc.liveFeed(ops);
    assertThat(breachedFeed.get("sla_breached_count")).isEqualTo(1L);

    Order forExport = seedLive(T0.plusSeconds(400));
    query.seedRow(forExport, "A,B", "P", "Z", BigDecimal.TEN, false);
    service.list(
        superAdmin,
        "ALL",
        "A",
        PH1,
        RIDER,
        UUID.randomUUID(),
        "COD",
        false,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31),
        1,
        20,
        true);

    // parseFilters with explicit dates + uuid + null string uuid
    UUID jobWithFilters = UUID.randomUUID();
    exportJobs.insert(
        new AdminOrderExportJob(
            jobWithFilters,
            ADMIN,
            """
            {"segment":"ALL","search":null,"pharmacy_id":"%s","rider_id":"null","zone_id":null,\
            "payment_method":"COD","is_rx_only":false,"from_date":"2026-08-01","to_date":"2026-08-31"}
            """
                .formatted(PH1),
            null,
            ExportJobStatus.PROCESSING,
            null,
            T0,
            null));
    service.processExportJob(jobWithFilters);

    // PENDING_ACCEPTANCE timeline skip + note without admin name
    Order pending = seedOrder(OrderStatus.PENDING_ACCEPTANCE, T0.plusSeconds(900), null);
    events.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            pending.id(),
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PENDING_ACCEPTANCE,
            ActorType.SYSTEM,
            null,
            null,
            T0));
    query.seedPharmacy(PH1, "Sai", "Area", BigDecimal.TEN);
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    service.detail(ops, pending.id());
    notes.insert(
        new OrderNote(
            UUID.randomUUID(), pending.id(), "orphan note", false, UUID.randomUUID(), T0));
    service.detail(ops, pending.id());

    // unknown rider
    AdminOrderService noRider =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new LocalExportObjectStore(tempDir, "file://" + tempDir),
            cache,
            riderId -> Optional.empty(),
            new StubPrescriptionAdapter(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            mapper,
            Runnable::run);
    assertThatThrownBy(() -> noRider.reassignRider(ops, forExport.id(), RIDER, "x"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(404);

    ObjectMapper badMapper = org.mockito.Mockito.mock(ObjectMapper.class);
    try {
      org.mockito.Mockito.when(badMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    } catch (Exception ignored) {
    }
    AdminOrderService badJson =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new LocalExportObjectStore(tempDir, "file://" + tempDir),
            new RedisLiveFeedCache(null),
            new StubRiderLookupAdapter(),
            new StubPrescriptionAdapter(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            badMapper,
            Runnable::run);
    assertThatThrownBy(() -> badJson.liveFeed(ops)).isInstanceOf(IllegalStateException.class);

    // Role matrix branches + null rate limiter + blank search + unpaid confirm timeline
    for (MedmatePrincipal p : List.of(superAdmin, ops, finance, support, compliance)) {
      service.list(p, "ALL", "  ", null, null, null, null, null, null, null, 1, 5, false);
    }
    service.flagDispute(superAdmin, forExport.id(), "super flags", "PLATFORM");
    service.addNote(superAdmin, forExport.id(), "super note", false);
    service.addNote(ops, forExport.id(), "ops note", false);
    service.addNote(support, forExport.id(), "sup note", false);
    service.reassignRider(superAdmin, forExport.id(), RIDER2, "super reassign");
    assertThatThrownBy(() -> service.flagDispute(support, forExport.id(), "x", "   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order noConfirm =
        new Order(
            UUID.randomUUID(),
            "ORD-PAY",
            CUST,
            PH1,
            UUID.randomUUID(),
            List.of(),
            100,
            null,
            0,
            0,
            0,
            0,
            100,
            PaymentMethod.UPI,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            StubPrescriptionAdapter.NOT_FOUND_ID,
            UUID.randomUUID(),
            null,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            null,
            null,
            null,
            T0,
            T0);
    orders.insert(noConfirm);
    query.seedPharmacy(PH1, "Sai", "Area", BigDecimal.TEN);
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    events.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            noConfirm.id(),
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PENDING_ACCEPTANCE,
            ActorType.SYSTEM,
            null,
            null,
            T0));
    Map<String, Object> pendingDetail = service.detail(ops, noConfirm.id());
    assertThat(pendingDetail.get("prescription_card")).isNotNull();
    assertThat(pendingDetail.get("sla_deadline")).isNull();

    AdminOrderService noLimit =
        new AdminOrderService(
            orders,
            events,
            query,
            disputes,
            notes,
            exportJobs,
            new LocalExportObjectStore(tempDir, "file://" + tempDir),
            new RedisLiveFeedCache(null),
            new StubRiderLookupAdapter(),
            new StubPrescriptionAdapter(),
            null,
            Clock.fixed(T0, ZoneOffset.UTC),
            mapper,
            Runnable::run);
    noLimit.list(ops, null, null, null, null, null, null, null, null, null, 1, 5, false);

    exportJobs.insert(
        new AdminOrderExportJob(
            UUID.randomUUID(),
            ADMIN,
            "{\"segment\":\"ALL\",\"from_date\":\"null\",\"to_date\":\"null\",\"pharmacy_id\":\"null\",\"is_rx_only\":true}",
            null,
            ExportJobStatus.PROCESSING,
            null,
            T0,
            null));
    UUID nullDatesJob =
        exportJobs.findByStatus(ExportJobStatus.PROCESSING, 10).stream()
            .filter(j -> j.filtersJson().contains("\"from_date\":\"null\""))
            .findFirst()
            .orElseThrow()
            .id();
    service.processExportJob(nullDatesJob);

    MedmatePrincipal customer =
        new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.list(
                    customer, "ALL", null, null, null, null, null, null, null, null, 1, 5, false))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);

    // export job map null completed_at / download_url
    UUID pendingExport = UUID.randomUUID();
    exportJobs.insert(
        new AdminOrderExportJob(
            pendingExport, ADMIN, "{}", null, ExportJobStatus.PROCESSING, null, T0, null));
    Map<String, Object> pendingView = service.exportJobStatus(ops, pendingExport);
    assertThat(pendingView.get("download_url")).isNull();
    assertThat(pendingView.get("completed_at")).isNull();

    // slaBreachedView branches + csv quote-only + blank segment/payment
    Order flaggedOnly = seedLive(T0.plusSeconds(900));
    flaggedOnly.markSlaBreached(T0);
    assertThat(AdminOrderService.slaBreachedView(flaggedOnly, T0)).isTrue();
    Order pastDeadline = seedLive(T0.minusSeconds(1));
    assertThat(AdminOrderService.slaBreachedView(pastDeadline, T0)).isTrue();
    Order future = seedLive(T0.plusSeconds(900));
    assertThat(AdminOrderService.slaBreachedView(future, T0)).isFalse();
    service.list(ops, "", null, null, null, null, "  ", null, null, null, 1, 5, false);
    query.seedRow(future, "He said \"hi\"", "P", "A", BigDecimal.TEN, false);
    service.list(ops, "ALL", null, null, null, null, null, null, null, null, 1, 20, true);

    // rider without otp
    Order riding = seedOrder(OrderStatus.OUT_FOR_DELIVERY, T0.plusSeconds(500), null);
    riding.assignRider(RIDER, T0);
    orders.update(riding);
    query.seedPharmacy(PH1, "Sai", "Area", BigDecimal.TEN);
    query.seedCustomer(CUST, "Ravi", "+91", 1, 100);
    Map<String, Object> ridingDetail = service.detail(ops, riding.id());
    @SuppressWarnings("unchecked")
    Map<String, Object> partner = (Map<String, Object>) ridingDetail.get("delivery_partner");
    assertThat(partner.get("otp_verified")).isEqualTo(false);

    assertThatThrownBy(() -> service.reassignRider(ops, riding.id(), RIDER, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flagDispute(ops, riding.id(), null, "RIDER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.addNote(ops, riding.id(), null, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Order cancelledForRider = seedOrder(OrderStatus.CANCELLED, null, null);
    assertThatThrownBy(() -> service.reassignRider(ops, cancelledForRider.id(), RIDER, "x"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(422);
    assertThatThrownBy(() -> service.liveFeed(null))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.flagDispute(null, riding.id(), "x", "RIDER"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.addNote(null, riding.id(), "x", false))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.reassignRider(null, riding.id(), RIDER, "x"))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);

    Order withRx = seedOrder(OrderStatus.DELIVERED, null, UUID.randomUUID());
    query.seedRow(withRx, "RxCust", "P", "A", BigDecimal.TEN, false);
    ListResult rxList =
        service.list(ops, "ALL", null, null, null, null, null, null, null, null, 1, 20, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rxOrders = (List<Map<String, Object>>) rxList.data().get("orders");
    assertThat(rxOrders.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("has_prescription"))))
        .isTrue();
    service.list(ops, "ALL", null, null, null, null, null, null, null, null, 1, 20, true);

    exportJobs.insert(
        new AdminOrderExportJob(
            UUID.randomUUID(),
            ADMIN,
            "{\"segment\":\"ALL\",\"search\":\"hello\"}",
            null,
            ExportJobStatus.PROCESSING,
            null,
            T0,
            null));
    UUID searchJob =
        exportJobs.findByStatus(ExportJobStatus.PROCESSING, 20).stream()
            .filter(j -> j.filtersJson().contains("hello"))
            .findFirst()
            .orElseThrow()
            .id();
    service.processExportJob(searchJob);
  }

  private Order seedLive(Instant slaDeadline) {
    return seedOrder(OrderStatus.OUT_FOR_DELIVERY, slaDeadline, null);
  }

  private Order seedOrder(OrderStatus status, Instant slaDeadline, UUID rxId) {
    UUID id = UUID.randomUUID();
    Instant created = T0.minusSeconds(600);
    Order order =
        new Order(
            id,
            "ORD-20260808-00001",
            CUST,
            PH1,
            UUID.randomUUID(),
            List.of(new OrderItemSnapshot(UUID.randomUUID(), "Metformin", 1, 8500, 8500, false)),
            8500,
            null,
            0,
            2500,
            500,
            0,
            11500,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            rxId,
            UUID.randomUUID(),
            null,
            status,
            null,
            null,
            null,
            created,
            created.plusSeconds(1800),
            created,
            created,
            created,
            null,
            slaDeadline,
            false,
            null,
            null,
            null,
            null,
            null);
    orders.insert(order);
    return order;
  }

  static final class InMemoryQuery implements AdminOrderQueryPort {
    private final InMemoryOrderStore orders;
    private final List<AdminOrderListRow> rows = new CopyOnWriteArrayList<>();
    private final Map<UUID, PharmacyAdminView> pharmacies = new ConcurrentHashMap<>();
    private final Map<UUID, CustomerAdminView> customers = new ConcurrentHashMap<>();
    private final Map<UUID, AdminStaffName> admins = new ConcurrentHashMap<>();
    Integer forceCount;

    InMemoryQuery(InMemoryOrderStore orders) {
      this.orders = orders;
    }

    void seedRow(
        Order order,
        String customer,
        String pharmacy,
        String area,
        BigDecimal pct,
        boolean disputed) {
      rows.add(new AdminOrderListRow(order, customer, "+91", pharmacy, area, pct, disputed));
    }

    void seedPharmacy(UUID id, String name, String area, BigDecimal pct) {
      pharmacies.put(id, new PharmacyAdminView(id, name, area, pct));
    }

    void seedCustomer(UUID id, String name, String phone, int count, long ltv) {
      customers.put(id, new CustomerAdminView(id, name, phone, count, ltv));
    }

    void seedAdmin(UUID id, String name) {
      admins.put(id, new AdminStaffName(id, name));
    }

    @Override
    public List<AdminOrderListRow> list(AdminListFilter filter) {
      return filtered(filter).stream()
          .skip((long) (filter.page() - 1) * filter.limit())
          .limit(filter.limit())
          .toList();
    }

    @Override
    public long count(AdminListFilter filter) {
      if (forceCount != null) {
        return forceCount;
      }
      return filtered(filter).size();
    }

    @Override
    public SummaryAgg summary(AdminListFilter filter) {
      List<AdminOrderListRow> all = filtered(filter);
      long gmv =
          all.stream()
              .filter(r -> r.order().status() != OrderStatus.CANCELLED)
              .mapToLong(r -> r.order().totalPayablePaise())
              .sum();
      long commission =
          all.stream()
              .filter(r -> r.order().status() != OrderStatus.CANCELLED)
              .mapToLong(
                  r ->
                      AdminOrderQueryPort.commissionPaise(
                          r.order().totalPayablePaise(), r.commissionPct()))
              .sum();
      long live =
          all.stream()
              .filter(r -> AdminOrderQueryPort.liveStatuses().contains(r.order().status()))
              .count();
      long risk = all.stream().filter(r -> r.order().slaRisk(filter.now())).count();
      return new SummaryAgg(all.size(), live, risk, gmv, commission);
    }

    @Override
    public List<AdminOrderListRow> listAllForExport(AdminListFilter filter, int maxRows) {
      return filtered(filter).stream().limit(maxRows).toList();
    }

    @Override
    public List<AdminOrderListRow> liveFeed(Instant now, int limit) {
      return rows.stream()
          .filter(r -> AdminOrderQueryPort.liveStatuses().contains(r.order().status()))
          .sorted(
              Comparator.comparingInt(
                  (AdminOrderListRow r) -> r.order().slaRemainingMinutesRaw(now)))
          .limit(limit)
          .toList();
    }

    @Override
    public Optional<PharmacyAdminView> findPharmacy(UUID pharmacyId) {
      return Optional.ofNullable(pharmacies.get(pharmacyId));
    }

    @Override
    public Optional<CustomerAdminView> findCustomer(UUID customerId) {
      return Optional.ofNullable(customers.get(customerId));
    }

    @Override
    public Optional<AdminStaffName> findAdminName(UUID adminId) {
      return Optional.ofNullable(admins.get(adminId));
    }

    @Override
    public Optional<String> findAddressArea(UUID addressId) {
      return Optional.of("Koramangala");
    }

    private List<AdminOrderListRow> filtered(AdminListFilter filter) {
      return rows.stream()
          .filter(
              r -> {
                Order o = r.order();
                return switch (filter.segment()) {
                  case LIVE -> AdminOrderQueryPort.liveStatuses().contains(o.status());
                  case SLA_RISK -> o.slaRisk(filter.now());
                  case DISPUTES -> r.disputed();
                  case DELIVERED -> o.status() == OrderStatus.DELIVERED;
                  case CANCELLED -> o.status() == OrderStatus.CANCELLED;
                  case ALL -> true;
                };
              })
          .filter(
              r ->
                  filter.search() == null
                      || (r.customerName() != null
                          && r.customerName()
                              .toLowerCase()
                              .contains(filter.search().toLowerCase())))
          .filter(
              r ->
                  filter.pharmacyId() == null || filter.pharmacyId().equals(r.order().pharmacyId()))
          .filter(
              r ->
                  filter.paymentMethod() == null
                      || filter.paymentMethod() == r.order().paymentMethod())
          .filter(
              r ->
                  filter.isRxOnly() == null
                      || filter.isRxOnly() == (r.order().prescriptionId() != null))
          .collect(Collectors.toCollection(ArrayList::new));
    }
  }

  static final class InMemoryDisputeStore implements OrderDisputeStore {
    private final Map<UUID, OrderDispute> byOrder = new ConcurrentHashMap<>();

    @Override
    public OrderDispute insert(OrderDispute dispute) {
      byOrder.put(dispute.orderId(), dispute);
      return dispute;
    }

    @Override
    public Optional<OrderDispute> findOpenByOrderId(UUID orderId) {
      return Optional.ofNullable(byOrder.get(orderId)).filter(d -> !d.resolved());
    }
  }

  static final class InMemoryNoteStore implements OrderNoteStore {
    private final List<OrderNote> all = new CopyOnWriteArrayList<>();

    @Override
    public OrderNote insert(OrderNote note) {
      all.add(note);
      return note;
    }

    @Override
    public List<OrderNote> listByOrderId(UUID orderId) {
      return all.stream()
          .filter(n -> n.orderId().equals(orderId))
          .sorted(
              Comparator.comparing(OrderNote::pinned)
                  .reversed()
                  .thenComparing(OrderNote::createdAt, Comparator.reverseOrder()))
          .toList();
    }
  }

  static final class InMemoryExportStore implements AdminOrderExportStore {
    private final Map<UUID, AdminOrderExportJob> jobs = new ConcurrentHashMap<>();

    @Override
    public AdminOrderExportJob insert(AdminOrderExportJob job) {
      jobs.put(job.id(), job);
      return job;
    }

    @Override
    public Optional<AdminOrderExportJob> findById(UUID jobId) {
      return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<AdminOrderExportJob> findByStatus(ExportJobStatus status, int limit) {
      return jobs.values().stream().filter(j -> j.status() == status).limit(limit).toList();
    }

    @Override
    public void markReady(UUID jobId, String s3Key, int rowCount, Instant completedAt) {
      AdminOrderExportJob prev = jobs.get(jobId);
      jobs.put(
          jobId,
          new AdminOrderExportJob(
              prev.id(),
              prev.requestedBy(),
              prev.filtersJson(),
              rowCount,
              ExportJobStatus.READY,
              s3Key,
              prev.createdAt(),
              completedAt));
    }

    @Override
    public void markFailed(UUID jobId, Instant completedAt) {
      AdminOrderExportJob prev = jobs.get(jobId);
      jobs.put(
          jobId,
          new AdminOrderExportJob(
              prev.id(),
              prev.requestedBy(),
              prev.filtersJson(),
              prev.rowCount(),
              ExportJobStatus.FAILED,
              prev.s3Key(),
              prev.createdAt(),
              completedAt));
    }
  }
}
