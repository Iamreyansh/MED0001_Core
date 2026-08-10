package com.nammamedmate.support.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.DisputeStore;
import com.nammamedmate.support.application.port.out.DisputeStore.Chips;
import com.nammamedmate.support.application.port.out.DisputeStore.ListFilter;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.OrderContextPort.OrderContext;
import com.nammamedmate.support.application.port.out.OrderContextPort.OrderItem;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.SupportDisputeBannerPort;
import com.nammamedmate.support.domain.Dispute;
import com.nammamedmate.support.domain.DisputeEvent;
import com.nammamedmate.support.domain.DisputeIds;
import com.nammamedmate.support.domain.DisputeStatus;
import com.nammamedmate.support.domain.DisputeType;
import com.nammamedmate.support.domain.LiabilityMatrix;
import com.nammamedmate.support.domain.LiableParty;
import com.nammamedmate.support.domain.RefundDestination;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeService implements SupportDisputeBannerPort {

  public static final long AUTO_REFUND_CAP_PAISE = 20_000L;
  private static final Duration RESOLUTION_SLA = Duration.ofHours(48);

  private static final Set<AuthRole> ADMIN_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final Set<AuthRole> RESOLVE_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_SUPPORT, AuthRole.ADMIN_OPERATIONS);
  private static final Set<AuthRole> HIGH_REFUND_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_SUPPORT);

  private final DisputeStore disputes;
  private final OrderContextPort orders;
  private final CustomerLookupPort customers;
  private final RefundPort refunds;
  private final NotificationDispatchPort notifications;
  private final Clock clock;

  public DisputeService(
      DisputeStore disputes,
      OrderContextPort orders,
      CustomerLookupPort customers,
      RefundPort refunds,
      NotificationDispatchPort notifications,
      Clock clock) {
    this.disputes = disputes;
    this.orders = orders;
    this.customers = customers;
    this.refunds = refunds;
    this.notifications = notifications;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = Map.copyOf(data);
    }
  }

  public record CreateCommand(
      UUID orderId, String disputeType, String description, List<String> evidenceUrls) {
    public CreateCommand {
      evidenceUrls = evidenceUrls == null ? List.of() : List.copyOf(evidenceUrls);
    }
  }

  public record InvestigateCommand(UUID assignedTo, String notes) {}

  public record ApproveCommand(
      String liableParty, Number refundAmount, String refundTo, String resolutionNotes) {}

  public record RejectCommand(String rejectionReason, String notes) {}

  @Override
  @Transactional(readOnly = true)
  public Optional<Banner> findForOrder(UUID orderId) {
    return disputes
        .findBannerDispute(orderId)
        .map(
            d ->
                new Banner(
                    d.disputeId(),
                    d.status().name(),
                    d.disputeType().name(),
                    d.liableParty() == null ? null : d.liableParty().name(),
                    d.description(),
                    d.createdAt()));
  }

  @Transactional(readOnly = true)
  public ListResult listAdmin(
      MedmatePrincipal principal,
      String status,
      String liableParty,
      String disputeType,
      Integer page,
      Integer limit,
      Boolean export) {
    requireAdmin(principal);
    Instant now = clock.instant();
    ListFilter filter =
        new ListFilter(
            parseStatus(status), parseLiable(liableParty), parseType(disputeType), 0, 100_000);
    if (Boolean.TRUE.equals(export)) {
      List<Dispute> rows = disputes.list(filter);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("csv", buildCsv(rows));
      data.put("record_count", rows.size());
      return new ListResult(data, null);
    }
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    ListFilter paged =
        new ListFilter(
            filter.status(), filter.liableParty(), filter.disputeType(), pr.offset(), pr.limit());
    long total = disputes.count(paged);
    Chips chips = disputes.chips(now);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Dispute d : disputes.list(paged)) {
      items.add(toListItem(d));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", toChipsMap(chips));
    data.put("disputes", items);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  public byte[] exportCsvBytes(ListResult result) {
    Object csv = result.data().get("csv");
    return csv == null ? new byte[0] : csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireCustomer(principal);
    if (cmd == null || cmd.orderId() == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    Instant now = clock.instant();
    OrderContext order =
        orders
            .find(cmd.orderId())
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    if (!order.customerId().equals(principal.subject())) {
      throw new AppException("ORDER_NOT_FOUND", "Order does not belong to customer", 404);
    }
    if (!order.delivered()) {
      throw new AppException("ORDER_NOT_ELIGIBLE", "Order status does not allow dispute", 422);
    }
    if (disputes.findByOrderId(cmd.orderId()).isPresent()) {
      throw new AppException("DISPUTE_ALREADY_EXISTS", "Order already has a dispute", 409);
    }
    DisputeType type = requireType(cmd.disputeType());
    String description = requireDescription(cmd.description());
    LiableParty recommended = LiabilityMatrix.recommend(type);
    LocalDate day = DisputeIds.dayKey(now);
    String disputeId = DisputeIds.format(day, disputes.nextDisputeSeq(day));
    UUID id = Ids.newId();
    Dispute dispute =
        new Dispute(
            id,
            disputeId,
            cmd.orderId(),
            principal.subject(),
            type,
            description,
            cmd.evidenceUrls(),
            DisputeStatus.OPEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now.plus(RESOLUTION_SLA),
            recommended,
            false,
            null,
            now,
            now,
            null);
    disputes.insert(dispute);
    String actorName = customers.displayName(principal.subject()).orElse("Customer");
    insertEvent(id, "DISPUTE_RAISED", principal.subject(), actorName, description, now);
    // AC-009: NOT_DELIVERED on DELIVERED order is the auto-raise path
    if (type == DisputeType.NOT_DELIVERED) {
      insertEvent(
          id,
          "AUTO_RAISED_NOT_DELIVERED",
          null,
          "System",
          "Auto-raised: customer reported non-delivery despite DELIVERED status",
          now);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id.toString());
    data.put("dispute_id", disputeId);
    data.put("order_id", cmd.orderId().toString());
    data.put("dispute_type", type.name());
    data.put("status", DisputeStatus.OPEN.name());
    data.put("resolution_sla_at", dispute.resolutionSlaAt().toString());
    data.put("created_at", now.toString());
    data.put("message", "Your dispute has been raised. We will resolve it within 48 hours.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getAdmin(MedmatePrincipal principal, UUID id) {
    requireAdmin(principal);
    Dispute d = requireDispute(id);
    OrderContext order = orders.find(d.orderId()).orElse(null);
    List<DisputeEvent> events = disputes.listEvents(d.id());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", d.id().toString());
    data.put("dispute_id", d.disputeId());
    data.put("dispute_type", d.disputeType().name());
    data.put("status", d.status().name());
    data.put("description", d.description());
    data.put("evidence_urls", d.evidenceUrls());
    data.put("order_context", orderContextMap(order, d.orderId()));
    Map<String, Object> liability = new LinkedHashMap<>();
    liability.put("recommended_liable_party", d.recommendedLiableParty().name());
    liability.put("rationale", LiabilityMatrix.rationale(d.disputeType()));
    data.put("liability_recommendation", liability);
    data.put("system_refund_recommendation", refundRecommendation(order));
    List<Map<String, Object>> history = new ArrayList<>();
    for (DisputeEvent e : events) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("event", e.eventType());
      row.put("at", e.createdAt().toString());
      row.put("actor", e.actorName());
      if (e.notes() != null) {
        row.put("notes", e.notes());
      }
      history.add(row);
    }
    data.put("history", history);
    data.put("investigated_by", d.investigatedBy() == null ? null : d.investigatedBy().toString());
    data.put("created_at", d.createdAt().toString());
    data.put("resolution_sla_at", d.resolutionSlaAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> investigate(
      MedmatePrincipal principal, UUID id, InvestigateCommand cmd) {
    requireAdmin(principal);
    Dispute d = requireDispute(id);
    if (d.status() != DisputeStatus.OPEN && d.status() != DisputeStatus.INVESTIGATING) {
      throw new AppException("VALIDATION_ERROR", "Dispute cannot be investigated", 400);
    }
    Instant now = clock.instant();
    UUID assignee =
        cmd != null && cmd.assignedTo() != null ? cmd.assignedTo() : principal.subject();
    String notes = cmd == null ? null : blankToNull(cmd.notes());
    Dispute updated = d.withInvestigating(assignee, now);
    disputes.update(updated);
    String actorName = customers.displayName(principal.subject()).orElse("Admin");
    insertEvent(id, "INVESTIGATION_STARTED", principal.subject(), actorName, notes, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id.toString());
    data.put("status", DisputeStatus.INVESTIGATING.name());
    data.put("assigned_to", assignee.toString());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> resolveApprove(
      MedmatePrincipal principal, UUID id, ApproveCommand cmd) {
    requireResolveRole(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "request body is required", 400);
    }
    Dispute d = requireDispute(id);
    if (d.status() == DisputeStatus.RESOLVED || d.status() == DisputeStatus.CLOSED) {
      throw new AppException("VALIDATION_ERROR", "Dispute already resolved", 400);
    }
    LiableParty liable = requireLiable(cmd.liableParty());
    long refundPaise = rupeesToPaise(cmd.refundAmount());
    if (refundPaise < 0) {
      throw new AppException("INVALID_REFUND_AMOUNT", "Refund amount must be >= 0", 422);
    }
    OrderContext order =
        orders
            .find(d.orderId())
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    if (refundPaise > order.totalPayablePaise()) {
      throw new AppException("INVALID_REFUND_AMOUNT", "Amount exceeds order value", 422);
    }
    if (refundPaise > AUTO_REFUND_CAP_PAISE && !HIGH_REFUND_ROLES.contains(principal.role())) {
      throw new AppException(
          "APPROVAL_REQUIRED", "Refund > Rs 200 requires admin_support role", 403);
    }
    RefundDestination dest = requireRefundTo(cmd.refundTo());
    Instant now = clock.instant();
    boolean auto = refundPaise > 0 && refundPaise <= AUTO_REFUND_CAP_PAISE;
    String txnId = null;
    if (refundPaise > 0) {
      RefundPort.RefundResult result =
          refunds.processRefund(d.orderId(), d.customerId(), refundPaise, dest.name(), d.id());
      txnId = result.transactionId();
      auto = auto && result.processed();
    } else {
      auto = false;
    }
    if (refundPaise > AUTO_REFUND_CAP_PAISE) {
      auto = false;
    }
    Dispute updated =
        d.withApproved(
            liable, refundPaise, dest, blankToNull(cmd.resolutionNotes()), auto, txnId, now, now);
    disputes.update(updated);
    String actorName = customers.displayName(principal.subject()).orElse("Admin");
    insertEvent(
        id,
        "DISPUTE_APPROVED",
        principal.subject(),
        actorName,
        blankToNull(cmd.resolutionNotes()),
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id.toString());
    data.put("status", DisputeStatus.RESOLVED.name());
    data.put("liable_party", liable.name());
    data.put("refund_amount_rs", paiseToRs(refundPaise));
    data.put("refund_to", dest.name());
    data.put("auto_processed", auto);
    data.put("refund_transaction_id", txnId);
    data.put("resolved_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> resolveReject(MedmatePrincipal principal, UUID id, RejectCommand cmd) {
    if (principal == null
        || (principal.role() != AuthRole.ADMIN_SUPER
            && principal.role() != AuthRole.ADMIN_SUPPORT)) {
      throw new AppException("FORBIDDEN", "admin_support role required to reject", 403);
    }
    if (cmd == null || blankToNull(cmd.rejectionReason()) == null) {
      throw new AppException("VALIDATION_ERROR", "rejection_reason is required", 400);
    }
    Dispute d = requireDispute(id);
    if (d.status() == DisputeStatus.RESOLVED || d.status() == DisputeStatus.CLOSED) {
      throw new AppException("VALIDATION_ERROR", "Dispute already resolved", 400);
    }
    Instant now = clock.instant();
    Dispute updated = d.withRejected(cmd.rejectionReason(), blankToNull(cmd.notes()), now, now);
    disputes.update(updated);
    String actorName = customers.displayName(principal.subject()).orElse("Admin");
    insertEvent(id, "DISPUTE_REJECTED", principal.subject(), actorName, cmd.rejectionReason(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id.toString());
    data.put("status", DisputeStatus.RESOLVED.name());
    data.put("liable_party", LiableParty.CUSTOMER.name());
    data.put("refund_amount_rs", 0);
    data.put("resolved_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public ListResult listMine(MedmatePrincipal principal, Integer page, Integer limit) {
    requireCustomer(principal);
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    long total = disputes.countForCustomer(principal.subject());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Dispute d : disputes.listForCustomer(principal.subject(), pr.offset(), pr.limit())) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", d.id().toString());
      row.put("dispute_id", d.disputeId());
      row.put("order_id", d.orderId().toString());
      row.put("dispute_type", d.disputeType().name());
      row.put("status", d.status().name());
      row.put(
          "refund_amount_rs",
          d.refundAmountPaise() == null ? null : paiseToRs(d.refundAmountPaise()));
      row.put("refund_to", d.refundTo() == null ? null : d.refundTo().name());
      row.put("created_at", d.createdAt().toString());
      row.put("resolved_at", d.resolvedAt() == null ? null : d.resolvedAt().toString());
      items.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("disputes", items);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional
  public int processSlaBreaches(int limit) {
    Instant now = clock.instant();
    int count = 0;
    for (Dispute d : disputes.findSlaBreachedOpen(now, limit)) {
      insertEvent(
          d.id(), "SLA_BREACHED", null, "System", "Resolution SLA breached; escalating", now);
      notifications.notifySupervisorEscalation(d.id(), "DISPUTE_SLA_BREACH");
      count++;
    }
    return count;
  }

  private void insertEvent(
      UUID disputeId, String type, UUID actorId, String actorName, String notes, Instant at) {
    disputes.insertEvent(
        new DisputeEvent(Ids.newId(), disputeId, type, actorId, actorName, notes, at));
  }

  private Dispute requireDispute(UUID id) {
    return disputes
        .findById(id)
        .orElseThrow(() -> new AppException("NOT_FOUND", "Dispute not found", 404));
  }

  private Map<String, Object> toListItem(Dispute d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id().toString());
    m.put("order_id", d.orderId().toString());
    m.put("customer_name", customers.displayName(d.customerId()).orElse("Customer"));
    m.put("dispute_type", d.disputeType().name());
    m.put("status", d.status().name());
    m.put("liable_party", d.liableParty() == null ? null : d.liableParty().name());
    m.put(
        "refund_amount_rs",
        d.refundAmountPaise() == null ? null : paiseToRs(d.refundAmountPaise()));
    m.put("created_at", d.createdAt().toString());
    m.put("dispute_id", d.disputeId());
    return m;
  }

  private static Map<String, Object> toChipsMap(Chips c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("open_disputes", c.openDisputes());
    m.put("refund_exposure_rs", c.refundExposureRs());
    m.put("avg_resolution_hours", c.avgResolutionHours());
    m.put("resolved_today", c.resolvedToday());
    return m;
  }

  private static Map<String, Object> orderContextMap(OrderContext order, UUID orderId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("order_id", orderId.toString());
    if (order == null) {
      m.put("order_items", List.of());
      m.put("pharmacy_name", null);
      m.put("rider_name", null);
      m.put("delivery_tracking_url", null);
      return m;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (OrderItem item : order.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", item.name());
      row.put("qty", item.qty());
      row.put("price_rs", paiseToRs(item.pricePaise()));
      items.add(row);
    }
    m.put("order_items", items);
    m.put("pharmacy_name", order.pharmacyName());
    m.put("rider_name", order.riderName());
    m.put("delivery_tracking_url", order.deliveryTrackingUrl());
    return m;
  }

  private static Map<String, Object> refundRecommendation(OrderContext order) {
    Map<String, Object> m = new LinkedHashMap<>();
    long amountPaise = order == null ? 0L : order.totalPayablePaise();
    long amountRs = paiseToRs(amountPaise);
    boolean auto = amountPaise > 0 && amountPaise <= AUTO_REFUND_CAP_PAISE;
    m.put("refund_amount_rs", amountRs);
    m.put("refund_to", RefundDestination.SOURCE.name());
    m.put("auto_process", auto);
    m.put(
        "note",
        auto
            ? "Amount ≤ Rs 200; eligible for auto-processing."
            : "Amount > Rs 200; requires admin_support approval.");
    return m;
  }

  private String buildCsv(List<Dispute> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append("dispute_id,order_id,customer_name,type,status,liable_party,refund_amount\n");
    for (Dispute d : rows) {
      String name = customers.displayName(d.customerId()).orElse("Customer").replace(',', ' ');
      sb.append(d.disputeId())
          .append(',')
          .append(d.orderId())
          .append(',')
          .append(name)
          .append(',')
          .append(d.disputeType().name())
          .append(',')
          .append(d.status().name())
          .append(',')
          .append(d.liableParty() == null ? "" : d.liableParty().name())
          .append(',')
          .append(d.refundAmountPaise() == null ? "" : paiseToRs(d.refundAmountPaise()))
          .append('\n');
    }
    return sb.toString();
  }

  private static long rupeesToPaise(Number refundAmount) {
    if (refundAmount == null) {
      throw new AppException("VALIDATION_ERROR", "refund_amount is required", 400);
    }
    if (refundAmount instanceof Double d) {
      return Math.round(d * 100.0);
    }
    if (refundAmount instanceof Float f) {
      return Math.round(f * 100.0f);
    }
    return refundAmount.longValue() * 100L;
  }

  private static long paiseToRs(long paise) {
    return paise / 100L;
  }

  private static DisputeType requireType(String raw) {
    DisputeType t = parseType(raw);
    if (t == null) {
      throw new AppException("VALIDATION_ERROR", "dispute_type is required", 400);
    }
    return t;
  }

  private static DisputeType parseType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return DisputeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid dispute_type", 400);
    }
  }

  private static DisputeStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return DisputeStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid status", 400);
    }
  }

  private static LiableParty parseLiable(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LiableParty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid liable_party", 400);
    }
  }

  private static LiableParty requireLiable(String raw) {
    LiableParty p = parseLiable(raw);
    if (p == null) {
      throw new AppException("VALIDATION_ERROR", "liable_party is required", 400);
    }
    return p;
  }

  private static RefundDestination requireRefundTo(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "refund_to is required", 400);
    }
    try {
      return RefundDestination.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid refund_to", 400);
    }
  }

  private static String requireDescription(String raw) {
    String d = blankToNull(raw);
    if (d == null) {
      throw new AppException("VALIDATION_ERROR", "description is required", 400);
    }
    return d;
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireResolveRole(MedmatePrincipal principal) {
    if (principal == null || !RESOLVE_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "Customer role required", 403);
    }
  }
}
