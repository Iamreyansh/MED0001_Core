package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.DisputeStore;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.OrderContextPort.OrderContext;
import com.nammamedmate.support.application.port.out.OrderContextPort.OrderItem;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.SupportDisputeBannerPort;
import com.nammamedmate.support.domain.Dispute;
import com.nammamedmate.support.domain.DisputeEvent;
import com.nammamedmate.support.domain.DisputeStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisputeServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID OPS = UUID.fromString("a0000002-0000-4000-8000-000000000002");
  private static final UUID ORDER = UUID.fromString("d0000001-0000-4000-8000-000000000001");

  private FakeDisputeStore store;
  private FakeOrders orders;
  private FakeRefunds refunds;
  private FakeNotifications notifications;
  private DisputeService service;

  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(OPS, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = new FakeDisputeStore();
    orders = new FakeOrders();
    refunds = new FakeRefunds();
    notifications = new FakeNotifications();
    orders.put(
        new OrderContext(
            ORDER,
            CUST,
            "DELIVERED",
            9600L,
            List.of(new OrderItem("Paracetamol 500mg", 2, 4800L)),
            "Apollo Pharmacy HSR",
            "Kiran Raj",
            "https://tracking.nammamedmate.com/" + ORDER));
    service =
        new DisputeService(
            store,
            orders,
            new FakeCustomers(),
            refunds,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_wrongItemsRecommendsPharmacy() {
    Map<String, Object> created =
        service.create(
            customer,
            new DisputeService.CreateCommand(
                ORDER, "WRONG_ITEMS", "Got ibuprofen instead", List.of("https://cdn/e1.jpg")));
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> detail = service.getAdmin(support, id);
    @SuppressWarnings("unchecked")
    Map<String, Object> liability = (Map<String, Object>) detail.get("liability_recommendation");
    assertThat(liability.get("recommended_liable_party")).isEqualTo("PHARMACY");
    assertThat(created.get("dispute_id").toString()).matches("DSP-20260724-\\d{6}");
    assertThat(created.get("resolution_sla_at")).isEqualTo(NOW.plusSeconds(48 * 3600).toString());
  }

  @Test
  void ac002_secondDisputeReturns409() {
    service.create(
        customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "first", List.of()));
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new DisputeService.CreateCommand(ORDER, "DAMAGED", "second", List.of())))
        .isInstanceOf(AppException.class)
        .satisfies(
            e -> {
              AppException ae = (AppException) e;
              assertThat(ae.code()).isEqualTo("DISPUTE_ALREADY_EXISTS");
              assertThat(ae.httpStatus()).isEqualTo(409);
            });
  }

  @Test
  void ac003_approve96AutoProcesses() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "wrong", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    service.investigate(support, id, new DisputeService.InvestigateCommand(ADMIN, "checking"));
    Map<String, Object> approved =
        service.resolveApprove(
            support,
            id,
            new DisputeService.ApproveCommand("PHARMACY", 96, "SOURCE", "packing error"));
    assertThat(approved.get("auto_processed")).isEqualTo(true);
    assertThat(approved.get("refund_amount_rs")).isEqualTo(96L);
    assertThat(approved.get("refund_transaction_id")).isNotNull();
    assertThat(refunds.calls).hasSize(1);
  }

  @Test
  void ac004_approve250WithoutSupportReturns403() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "OVERCHARGED", "over", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    orders.put(
        new OrderContext(
            ORDER, CUST, "DELIVERED", 50_000L, List.of(), "P", "R", "https://t/" + ORDER));
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    ops, id, new DisputeService.ApproveCommand("PLATFORM", 250, "SOURCE", "notes")))
        .isInstanceOf(AppException.class)
        .satisfies(
            e -> {
              AppException ae = (AppException) e;
              assertThat(ae.code()).isEqualTo("APPROVAL_REQUIRED");
              assertThat(ae.httpStatus()).isEqualTo(403);
            });
  }

  @Test
  void ac005_rejectShowsZeroRefundInHistory() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "wrong", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    service.resolveReject(
        support, id, new DisputeService.RejectCommand("Evidence insufficient", "cctv ok"));
    DisputeService.ListResult mine = service.listMine(customer, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) mine.data().get("disputes");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("status")).isEqualTo("RESOLVED");
    assertThat(rows.getFirst().get("refund_amount_rs")).isEqualTo(0L);
  }

  @Test
  void ac006_detailShowsHistoryTimeline() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "desc", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    service.investigate(support, id, new DisputeService.InvestigateCommand(ADMIN, "notes"));
    Map<String, Object> detail = service.getAdmin(support, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
    assertThat(history)
        .extracting(m -> m.get("event"))
        .contains("DISPUTE_RAISED", "INVESTIGATION_STARTED");
    assertThat(history.getFirst().get("actor")).isEqualTo("Priya Sharma");
    assertThat(history.getFirst().get("at")).isNotNull();
  }

  @Test
  void ac007_slaIsCreatePlus48hAndBreachEscalates() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "desc", List.of()));
    assertThat(created.get("resolution_sla_at")).isEqualTo("2026-07-26T10:00:00Z");
    UUID id = UUID.fromString(created.get("id").toString());
    Dispute d = store.byId.get(id);
    store.byId.put(
        id,
        new Dispute(
            d.id(),
            d.disputeId(),
            d.orderId(),
            d.customerId(),
            d.disputeType(),
            d.description(),
            d.evidenceUrls(),
            d.status(),
            d.liableParty(),
            d.refundAmountPaise(),
            d.refundTo(),
            d.resolutionNotes(),
            d.rejectionReason(),
            d.investigatedBy(),
            d.resolvedAt(),
            NOW.minusSeconds(1),
            d.recommendedLiableParty(),
            d.autoProcessed(),
            d.refundTxnId(),
            d.createdAt(),
            d.updatedAt(),
            d.deletedAt()));
    assertThat(service.processSlaBreaches(10)).isEqualTo(1);
    assertThat(notifications.supervisorReasons).contains("DISPUTE_SLA_BREACH");
    assertThat(store.events.get(id).stream().anyMatch(e -> "SLA_BREACHED".equals(e.eventType())))
        .isTrue();
  }

  @Test
  void ac008_bannerPortReturnsOpenOrResolved() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "desc", List.of()));
    Optional<SupportDisputeBannerPort.Banner> banner = service.findForOrder(ORDER);
    assertThat(banner).isPresent();
    assertThat(banner.orElseThrow().status()).isEqualTo("OPEN");
    UUID id = UUID.fromString(created.get("id").toString());
    service.resolveApprove(
        support, id, new DisputeService.ApproveCommand("PHARMACY", 96, "WALLET", "ok"));
    assertThat(service.findForOrder(ORDER).orElseThrow().status()).isEqualTo("RESOLVED");
  }

  @Test
  void ac009_notDeliveredOnDeliveredAutoRaises() {
    Map<String, Object> created =
        service.create(
            customer,
            new DisputeService.CreateCommand(ORDER, "NOT_DELIVERED", "Never received", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> detail = service.getAdmin(support, id);
    @SuppressWarnings("unchecked")
    Map<String, Object> liability = (Map<String, Object>) detail.get("liability_recommendation");
    assertThat(liability.get("recommended_liable_party")).isEqualTo("RIDER");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
    assertThat(history).extracting(m -> m.get("event")).contains("AUTO_RAISED_NOT_DELIVERED");
  }

  @Test
  void ac010_csvExportIncludesKeyFields() {
    service.create(
        customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "desc", List.of()));
    DisputeService.ListResult exported = service.listAdmin(support, null, null, null, 1, 20, true);
    String csv = exported.data().get("csv").toString();
    assertThat(csv)
        .contains("dispute_id,order_id,customer_name,type,status,liable_party,refund_amount")
        .contains("DSP-20260724-")
        .contains("WRONG_ITEMS")
        .contains("Priya Sharma");
    assertThat(service.exportCsvBytes(exported)).isNotEmpty();
  }

  @Test
  void orderNotEligibleAndNotFound() {
    orders.put(new OrderContext(ORDER, CUST, "PLACED", 1000L, List.of(), "P", null, null));
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of())))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_ELIGIBLE");

    UUID other = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new DisputeService.CreateCommand(other, "WRONG_ITEMS", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
  }

  @Test
  void invalidRefundExceedsOrder() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id, new DisputeService.ApproveCommand("PHARMACY", 500, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_REFUND_AMOUNT");
  }

  @Test
  void listAdminChipsAndFilters() {
    service.create(
        customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of()));
    DisputeService.ListResult listed =
        service.listAdmin(support, "OPEN", null, "WRONG_ITEMS", 1, 20, false);
    assertThat(listed.meta().total()).isEqualTo(1);
    assertThat(listed.data().get("chips")).isInstanceOf(Map.class);
  }

  public static final class FakeDisputeStore implements DisputeStore {
    final Map<UUID, Dispute> byId = new HashMap<>();
    final Map<UUID, UUID> byOrder = new HashMap<>();
    final Map<UUID, List<DisputeEvent>> events = new HashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    @Override
    public int nextDisputeSeq(LocalDate day) {
      return seq.incrementAndGet();
    }

    @Override
    public Dispute insert(Dispute dispute) {
      byId.put(dispute.id(), dispute);
      byOrder.put(dispute.orderId(), dispute.id());
      return dispute;
    }

    @Override
    public void update(Dispute dispute) {
      byId.put(dispute.id(), dispute);
    }

    @Override
    public Optional<Dispute> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Dispute> findByOrderId(UUID orderId) {
      UUID id = byOrder.get(orderId);
      return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Dispute> findBannerDispute(UUID orderId) {
      return findByOrderId(orderId)
          .filter(
              d ->
                  d.status() == DisputeStatus.OPEN
                      || d.status() == DisputeStatus.INVESTIGATING
                      || d.status() == DisputeStatus.RESOLVED);
    }

    @Override
    public List<Dispute> list(ListFilter filter) {
      return byId.values().stream()
          .filter(d -> filter.status() == null || d.status() == filter.status())
          .filter(d -> filter.liableParty() == null || d.liableParty() == filter.liableParty())
          .filter(d -> filter.disputeType() == null || d.disputeType() == filter.disputeType())
          .sorted(Comparator.comparing(Dispute::createdAt).reversed())
          .skip(filter.offset())
          .limit(filter.limit())
          .collect(Collectors.toList());
    }

    @Override
    public long count(ListFilter filter) {
      return list(new ListFilter(
              filter.status(), filter.liableParty(), filter.disputeType(), 0, 100_000))
          .size();
    }

    @Override
    public List<Dispute> listForCustomer(UUID customerId, int offset, int limit) {
      return byId.values().stream()
          .filter(d -> d.customerId().equals(customerId))
          .sorted(Comparator.comparing(Dispute::createdAt).reversed())
          .skip(offset)
          .limit(limit)
          .collect(Collectors.toList());
    }

    @Override
    public long countForCustomer(UUID customerId) {
      return byId.values().stream().filter(d -> d.customerId().equals(customerId)).count();
    }

    @Override
    public Chips chips(Instant now) {
      long open =
          byId.values().stream()
              .filter(
                  d ->
                      d.status() == DisputeStatus.OPEN || d.status() == DisputeStatus.INVESTIGATING)
              .count();
      return new Chips(open, 0, 0.0, 0);
    }

    @Override
    public DisputeEvent insertEvent(DisputeEvent event) {
      events.computeIfAbsent(event.disputeId(), k -> new ArrayList<>()).add(event);
      return event;
    }

    @Override
    public List<DisputeEvent> listEvents(UUID disputeId) {
      return events.getOrDefault(disputeId, List.of());
    }

    @Override
    public List<Dispute> findSlaBreachedOpen(Instant now, int limit) {
      return byId.values().stream()
          .filter(d -> d.slaBreached(now))
          .limit(limit)
          .collect(Collectors.toList());
    }
  }

  public static final class FakeOrders implements OrderContextPort {
    final Map<UUID, OrderContext> byId = new HashMap<>();

    void put(OrderContext ctx) {
      byId.put(ctx.orderId(), ctx);
    }

    @Override
    public Optional<OrderContext> find(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }
  }

  public static final class FakeRefunds implements RefundPort {
    final List<Long> calls = new ArrayList<>();

    @Override
    public RefundResult processRefund(
        UUID orderId, UUID customerId, long amountPaise, String refundTo, UUID disputeId) {
      calls.add(amountPaise);
      return new RefundResult("txn_test", true);
    }
  }

  public static final class FakeNotifications implements NotificationDispatchPort {
    final List<String> supervisorReasons = new ArrayList<>();

    @Override
    public void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel) {}

    @Override
    public void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel) {}

    @Override
    public void notifySupervisorEscalation(UUID ticketId, String reason) {
      supervisorReasons.add(reason);
    }
  }

  public static final class FakeCustomers implements CustomerLookupPort {
    @Override
    public Optional<CustomerContext> find(UUID customerId) {
      return Optional.of(new CustomerContext(customerId, "Priya Sharma", 1, 100));
    }

    @Override
    public Optional<String> displayName(UUID customerId) {
      return Optional.of("Priya Sharma");
    }
  }
}
