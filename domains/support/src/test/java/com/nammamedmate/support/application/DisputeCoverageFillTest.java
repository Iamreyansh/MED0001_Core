package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.DisputeServiceTest.FakeCustomers;
import com.nammamedmate.support.application.DisputeServiceTest.FakeDisputeStore;
import com.nammamedmate.support.application.DisputeServiceTest.FakeNotifications;
import com.nammamedmate.support.application.DisputeServiceTest.FakeOrders;
import com.nammamedmate.support.application.DisputeServiceTest.FakeRefunds;
import com.nammamedmate.support.application.port.out.OrderContextPort.OrderContext;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.RefundPort.RefundResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisputeCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final UUID ORDER = UUID.fromString("d0000001-0000-4000-8000-000000000001");

  private FakeDisputeStore store;
  private FakeOrders orders;
  private DisputeService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = new FakeDisputeStore();
    orders = new FakeOrders();
    orders.put(
        new OrderContext(
            ORDER, CUST, "DELIVERED", 50_000L, List.of(), "P", "R", "https://t/" + ORDER));
    service =
        new DisputeService(
            store,
            orders,
            new FakeCustomers(),
            new FakeRefunds(),
            new FakeNotifications(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void validationAndRoleBranches() {
    assertThatThrownBy(() -> service.create(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    support,
                    new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new DisputeService.CreateCommand(ORDER, "NOPE", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "  ", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAdmin(customer, null, null, null, 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listMine(support, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void highRefundWithSupportNotAutoAndZeroRefund() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "OVERCHARGED", "over", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> approved =
        service.resolveApprove(
            support, id, new DisputeService.ApproveCommand("PLATFORM", 250, "WALLET", "ok"));
    assertThat(approved.get("auto_processed")).isEqualTo(false);
    assertThat(approved.get("refund_amount_rs")).isEqualTo(250L);

    UUID order2 = UUID.randomUUID();
    orders.put(new OrderContext(order2, CUST, "DELIVERED", 1000L, List.of(), "P", null, null));
    Map<String, Object> c2 =
        service.create(
            customer, new DisputeService.CreateCommand(order2, "QUALITY", "q", List.of()));
    UUID id2 = UUID.fromString(c2.get("id").toString());
    Map<String, Object> zero =
        service.resolveApprove(
            superAdmin, id2, new DisputeService.ApproveCommand("PHARMACY", 0, "SOURCE", "n"));
    assertThat(zero.get("auto_processed")).isEqualTo(false);
    assertThat(zero.get("refund_transaction_id")).isNull();
  }

  @Test
  void rejectForbiddenForOpsAndAlreadyResolved() {
    MedmatePrincipal ops =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    assertThatThrownBy(
            () -> service.resolveReject(ops, id, new DisputeService.RejectCommand("no", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    service.resolveReject(support, id, new DisputeService.RejectCommand("no", "n"));
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id, new DisputeService.ApproveCommand("PHARMACY", 10, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveReject(support, id, new DisputeService.RejectCommand("again", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void filterParseAndInvestigateDefaults() {
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "DAMAGED", "d", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    assertThatThrownBy(() -> service.listAdmin(support, "NOPE", null, null, 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAdmin(support, null, "NOPE", null, 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> inv = service.investigate(support, id, null);
    assertThat(inv.get("assigned_to")).isEqualTo(ADMIN.toString());
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id, new DisputeService.ApproveCommand(null, 10, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support,
                    id,
                    new DisputeService.ApproveCommand("PHARMACY", null, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id, new DisputeService.ApproveCommand("PHARMACY", 10, "NOPE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id, new DisputeService.ApproveCommand("PHARMACY", -1, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_REFUND_AMOUNT");
    assertThatThrownBy(() -> service.resolveApprove(support, id, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.resolveReject(support, id, new DisputeService.RejectCommand("  ", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.getAdmin(support, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void doubleAndFloatRefundAmountAndOrderMismatch() {
    UUID otherCust = UUID.randomUUID();
    UUID ord = UUID.randomUUID();
    orders.put(new OrderContext(ord, otherCust, "DELIVERED", 5000L, List.of(), "P", null, null));
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new DisputeService.CreateCommand(ord, "WRONG_ITEMS", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "EXPIRED_MEDICINE", "d", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> approved =
        service.resolveApprove(
            support, id, new DisputeService.ApproveCommand("PHARMACY", 12.5, "SOURCE", "ok"));
    assertThat(approved.get("refund_amount_rs")).isEqualTo(12L);

    UUID ord2 = UUID.randomUUID();
    orders.put(new OrderContext(ord2, CUST, "DELIVERED", 5000L, List.of(), "P", null, null));
    Map<String, Object> c2 =
        service.create(
            customer, new DisputeService.CreateCommand(ord2, "MISSING_ITEMS", "d", null));
    UUID id2 = UUID.fromString(c2.get("id").toString());
    Map<String, Object> f =
        service.resolveApprove(
            support, id2, new DisputeService.ApproveCommand("PHARMACY", 5.0f, "SOURCE", "ok"));
    assertThat(f.get("refund_amount_rs")).isEqualTo(5L);
  }

  @Test
  void remainingBranches() {
    // blank status/liable filters, empty export csv, null principal roles
    assertThat(service.exportCsvBytes(new DisputeService.ListResult(Map.of(), null))).isEmpty();
    assertThatThrownBy(() -> service.listAdmin(null, " ", " ", " ", 1, 20, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    null,
                    UUID.randomUUID(),
                    new DisputeService.ApproveCommand("PHARMACY", 1, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.create(
                    null, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new DisputeService.CreateCommand(null, "WRONG_ITEMS", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // null/blank filter parse short-circuits (auth passes)
    assertThat(service.listAdmin(support, null, null, null, 1, 20, false).meta().total())
        .isEqualTo(0);
    assertThat(service.listAdmin(support, "  ", "  ", "  ", 1, 20, false).meta().total())
        .isEqualTo(0);

    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "QUALITY", "d", List.of("e")));
    UUID id = UUID.fromString(created.get("id").toString());

    // investigate with null notes + re-investigate while INVESTIGATING
    service.investigate(support, id, new DisputeService.InvestigateCommand(null, null));
    Map<String, Object> detailOpen = service.getAdmin(support, id);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) detailOpen.get("history");
    assertThat(history.stream().anyMatch(h -> !h.containsKey("notes"))).isTrue();
    service.investigate(support, id, new DisputeService.InvestigateCommand(ADMIN, "follow-up"));

    // investigate invalid status after resolve
    service.resolveApprove(
        support, id, new DisputeService.ApproveCommand("PHARMACY", 10, "SOURCE", null));
    assertThatThrownBy(
            () ->
                service.investigate(
                    support, id, new DisputeService.InvestigateCommand(ADMIN, "late")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // toListItem non-null liable + refund (listAdmin, not export)
    DisputeService.ListResult listed =
        service.listAdmin(support, "RESOLVED", null, null, 1, 20, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> disputes = (List<Map<String, Object>>) listed.data().get("disputes");
    assertThat(disputes).isNotEmpty();
    assertThat(disputes.getFirst().get("liable_party")).isEqualTo("PHARMACY");
    assertThat(disputes.getFirst().get("refund_amount_rs")).isEqualTo(10L);

    // order context missing on detail + refund recommendation null order path via missing order
    UUID ordMissingCtx = UUID.randomUUID();
    orders.put(new OrderContext(ordMissingCtx, CUST, "DELIVERED", 0L, null, "P", null, null));
    Map<String, Object> c2 =
        service.create(
            customer,
            new DisputeService.CreateCommand(ordMissingCtx, "OVERCHARGED", "d", List.of()));
    UUID id2 = UUID.fromString(c2.get("id").toString());
    orders.byId.remove(ordMissingCtx);
    Map<String, Object> detail = service.getAdmin(support, id2);
    assertThat(detail.get("order_context")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> rec = (Map<String, Object>) detail.get("system_refund_recommendation");
    assertThat(rec.get("auto_process")).isEqualTo(false);

    // approve when order vanished
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id2, new DisputeService.ApproveCommand("PLATFORM", 1, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    // refund recommendation over auto-cap
    UUID ordBig = UUID.randomUUID();
    orders.put(new OrderContext(ordBig, CUST, "DELIVERED", 50_000L, List.of(), "P", null, null));
    Map<String, Object> cBig =
        service.create(
            customer, new DisputeService.CreateCommand(ordBig, "OVERCHARGED", "d", List.of()));
    UUID idBig = UUID.fromString(cBig.get("id").toString());
    @SuppressWarnings("unchecked")
    Map<String, Object> recBig =
        (Map<String, Object>) service.getAdmin(support, idBig).get("system_refund_recommendation");
    assertThat(recBig.get("auto_process")).isEqualTo(false);

    // reject null body / ops already covered; blank + null refund_to
    UUID ord3 = UUID.randomUUID();
    orders.put(new OrderContext(ord3, CUST, "DELIVERED", 9000L, List.of(), "P", null, null));
    Map<String, Object> c3 =
        service.create(customer, new DisputeService.CreateCommand(ord3, "DAMAGED", "d", List.of()));
    UUID id3 = UUID.fromString(c3.get("id").toString());
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id3, new DisputeService.ApproveCommand("PHARMACY", 10, "  ", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support, id3, new DisputeService.ApproveCommand("PHARMACY", 10, null, "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.resolveReject(support, id3, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.resolveReject(null, id3, new DisputeService.RejectCommand("no", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    compliance,
                    id3,
                    new DisputeService.ApproveCommand("PHARMACY", 1, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // list mine with refunds set and csv with liable/refund; reject via SUPER
    service.resolveReject(superAdmin, id3, new DisputeService.RejectCommand("no", null));
    DisputeService.ListResult mine = service.listMine(customer, 1, 20);
    assertThat(mine.meta().total()).isGreaterThanOrEqualTo(1);
    DisputeService.ListResult csv = service.listAdmin(support, null, "PHARMACY", null, 1, 20, true);
    assertThat(csv.data().get("csv").toString()).contains("PHARMACY");

    // CLOSED status blocks approve/reject
    UUID ordClosed = UUID.randomUUID();
    orders.put(new OrderContext(ordClosed, CUST, "DELIVERED", 5000L, List.of(), "P", null, null));
    Map<String, Object> cClosed =
        service.create(
            customer, new DisputeService.CreateCommand(ordClosed, "QUALITY", "d", List.of()));
    UUID idClosed = UUID.fromString(cClosed.get("id").toString());
    var open = store.byId.get(idClosed);
    store.byId.put(
        idClosed,
        new com.nammamedmate.support.domain.Dispute(
            open.id(),
            open.disputeId(),
            open.orderId(),
            open.customerId(),
            open.disputeType(),
            open.description(),
            open.evidenceUrls(),
            com.nammamedmate.support.domain.DisputeStatus.CLOSED,
            open.liableParty(),
            open.refundAmountPaise(),
            open.refundTo(),
            open.resolutionNotes(),
            open.rejectionReason(),
            open.investigatedBy(),
            open.resolvedAt(),
            open.resolutionSlaAt(),
            open.recommendedLiableParty(),
            open.autoProcessed(),
            open.refundTxnId(),
            open.createdAt(),
            open.updatedAt(),
            open.deletedAt()));
    assertThatThrownBy(
            () ->
                service.resolveApprove(
                    support,
                    idClosed,
                    new DisputeService.ApproveCommand("PHARMACY", 1, "SOURCE", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.resolveReject(
                    support, idClosed, new DisputeService.RejectCommand("late", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // create with null type via requireType blank
    UUID ord4 = UUID.randomUUID();
    orders.put(new OrderContext(ord4, CUST, "DELIVERED", 1000L, List.of(), "P", null, null));
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new DisputeService.CreateCommand(ord4, null, "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new DisputeService.CreateCommand(ord4, "  ", "d", List.of())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void refundProcessedFalseKeepsAutoFalse() {
    RefundPort unprocessed =
        (orderId, customerId, amountPaise, refundTo, disputeId) ->
            new RefundResult("txn_pending", false);
    service =
        new DisputeService(
            store,
            orders,
            new FakeCustomers(),
            unprocessed,
            new FakeNotifications(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> created =
        service.create(
            customer, new DisputeService.CreateCommand(ORDER, "WRONG_ITEMS", "d", List.of()));
    UUID id = UUID.fromString(created.get("id").toString());
    Map<String, Object> approved =
        service.resolveApprove(
            support, id, new DisputeService.ApproveCommand("PHARMACY", 50, "SOURCE", "ok"));
    assertThat(approved.get("auto_processed")).isEqualTo(false);
  }
}
