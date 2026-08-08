package com.nammamedmate.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.order.adapter.out.cache.RedisLiveFeedCache;
import com.nammamedmate.order.application.port.out.AdminOrderExportStore;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminListFilter;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.AdminOrderListRow;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.CustomerAdminView;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.PharmacyAdminView;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort.SummaryAgg;
import com.nammamedmate.order.application.port.out.ExportObjectStore;
import com.nammamedmate.order.application.port.out.LiveFeedCachePort;
import com.nammamedmate.order.application.port.out.OrderDisputeStore;
import com.nammamedmate.order.application.port.out.OrderNoteStore;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.application.port.out.RiderLookupPort.RiderInfo;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.AdminOrderExportJob;
import com.nammamedmate.order.domain.AdminOrderSegment;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.ExportJobStatus;
import com.nammamedmate.order.domain.LiableParty;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderDispute;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderNote;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrderService {

  public static final int ASYNC_EXPORT_THRESHOLD = 10_000;
  private static final Duration LIVE_FEED_TTL = Duration.ofSeconds(10);
  private static final int LIVE_FEED_LIMIT = 200;

  private final OrderStore orders;
  private final OrderStatusEventStore statusEvents;
  private final AdminOrderQueryPort query;
  private final OrderDisputeStore disputes;
  private final OrderNoteStore notes;
  private final AdminOrderExportStore exportJobs;
  private final ExportObjectStore exportObjects;
  private final LiveFeedCachePort liveFeedCache;
  private final RiderLookupPort riders;
  private final PrescriptionPort prescriptions;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final Executor exportExecutor;

  @Autowired
  public AdminOrderService(
      OrderStore orders,
      OrderStatusEventStore statusEvents,
      AdminOrderQueryPort query,
      OrderDisputeStore disputes,
      OrderNoteStore notes,
      AdminOrderExportStore exportJobs,
      ExportObjectStore exportObjects,
      LiveFeedCachePort liveFeedCache,
      RiderLookupPort riders,
      PrescriptionPort prescriptions,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper,
      @Qualifier("adminOrderExportExecutor") Executor exportExecutor) {
    this.orders = orders;
    this.statusEvents = statusEvents;
    this.query = query;
    this.disputes = disputes;
    this.notes = notes;
    this.exportJobs = exportJobs;
    this.exportObjects = exportObjects;
    this.liveFeedCache = liveFeedCache;
    this.riders = riders;
    this.prescriptions = prescriptions;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.exportExecutor = exportExecutor;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {}

  public ListResult list(
      MedmatePrincipal principal,
      String segmentRaw,
      String search,
      UUID pharmacyId,
      UUID riderId,
      UUID zoneId,
      String paymentMethodRaw,
      Boolean isRxOnly,
      LocalDate fromDate,
      LocalDate toDate,
      Integer page,
      Integer limit,
      Boolean export) {
    requireAnyAdmin(principal);
    rateLimit("admin:orders:list:" + principal.subject(), 30, 60);
    AdminListFilter filter =
        buildFilter(
            segmentRaw,
            search,
            pharmacyId,
            riderId,
            zoneId,
            paymentMethodRaw,
            isRxOnly,
            fromDate,
            toDate,
            page,
            limit);

    if (Boolean.TRUE.equals(export)) {
      return new ListResult(startExport(principal, filter), null);
    }
    return listPaged(filter);
  }

  @Transactional(readOnly = true)
  protected ListResult listPaged(AdminListFilter filter) {
    PageRequest pageReq =
        PageRequest.normalize(filter.page(), filter.limit(), "created_at", "desc");
    AdminListFilter paged =
        new AdminListFilter(
            filter.segment(),
            filter.search(),
            filter.pharmacyId(),
            filter.riderId(),
            filter.zoneId(),
            filter.paymentMethod(),
            filter.isRxOnly(),
            filter.fromDate(),
            filter.toDate(),
            filter.now(),
            pageReq.page(),
            pageReq.limit());

    SummaryAgg summary = query.summary(paged);
    List<AdminOrderListRow> rows = query.list(paged);
    long total = query.count(paged);

    List<Map<String, Object>> ordersOut = new ArrayList<>(rows.size());
    Instant now = paged.now();
    for (AdminOrderListRow row : rows) {
      ordersOut.add(toListItem(row, now));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", toSummaryMap(summary));
    data.put("orders", ordersOut);
    return new ListResult(data, PaginationMeta.of(pageReq.page(), pageReq.limit(), total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(MedmatePrincipal principal, UUID orderId) {
    requireAnyAdmin(principal);
    rateLimit("admin:orders:detail:" + principal.subject(), 60, 60);
    Order order = requireOrder(orderId);
    Instant now = clock.instant();

    OrderDispute openDispute = disputes.findOpenByOrderId(orderId).orElse(null);
    List<OrderStatusEvent> events = statusEvents.listByOrderId(orderId);
    List<OrderNote> noteRows = notes.listByOrderId(orderId);

    PharmacyAdminView pharmacy =
        query
            .findPharmacy(order.pharmacyId())
            .orElse(new PharmacyAdminView(order.pharmacyId(), "Pharmacy", null, BigDecimal.ZERO));
    CustomerAdminView customer =
        query
            .findCustomer(order.customerId())
            .orElse(new CustomerAdminView(order.customerId(), "Customer", null, 0, 0L));

    long commissionPaise =
        AdminOrderQueryPort.commissionPaise(order.totalPayablePaise(), pharmacy.commissionPct());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("order_number", order.orderNumber());
    data.put("status", order.status().name());
    data.put("is_disputed", openDispute != null);
    data.put("dispute_banner", disputeBanner(openDispute));
    data.put("status_timeline", timeline(events, order));
    data.put("customer", customerMap(customer));
    data.put("pharmacy", pharmacyMap(pharmacy));
    data.put("items", itemsMap(order.items()));
    data.put("bill", billMap(order, pharmacy.commissionPct(), commissionPaise));
    data.put("payment", paymentMap(order));
    data.put("prescription_card", prescriptionCard(order));
    data.put("delivery_partner", deliveryPartner(order));
    data.put("customer_rating", null);
    data.put("internal_notes", notesMap(noteRows));
    data.put("sla_deadline", order.slaDeadline() == null ? null : order.slaDeadline().toString());
    data.put("sla_remaining_minutes", order.slaRemainingMinutesClamped(now));
    data.put("sla_breached", slaBreachedView(order, now));
    data.put("created_at", order.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> reassignRider(
      MedmatePrincipal principal, UUID orderId, UUID riderId, String reason) {
    requireOpsOrSuper(principal);
    rateLimit("admin:orders:reassign:" + principal.subject(), 10, 60);
    if (riderId == null) {
      throw new AppException("VALIDATION_ERROR", "rider_id is required", 400);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    Order order = requireOrder(orderId);
    if (order.status() == OrderStatus.DELIVERED || order.status() == OrderStatus.CANCELLED) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION", "Cannot reassign rider after delivery/cancel", 422);
    }
    RiderInfo rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("VALIDATION_ERROR", "rider not found", 404));
    UUID previous = order.riderId();
    Instant now = clock.instant();
    OrderStatus status = order.status();
    order.assignRider(riderId, now);
    orders.update(order);
    String notesText =
        "Rider reassigned: "
            + (previous == null ? "none" : previous)
            + " -> "
            + riderId
            + " | "
            + reason.trim();
    statusEvents.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            order.id(),
            status,
            status,
            ActorType.ADMIN,
            principal.subject(),
            notesText,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("new_rider_id", rider.id().toString());
    data.put("new_rider_name", rider.name());
    data.put("previous_rider_id", previous == null ? null : previous.toString());
    data.put("reassigned_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> flagDispute(
      MedmatePrincipal principal, UUID orderId, String reason, String liablePartyRaw) {
    requireDisputeRole(principal);
    rateLimit("admin:orders:dispute:" + principal.subject(), 10, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    if (reason.trim().length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max 500 chars", 400);
    }
    LiableParty liable = parseLiable(liablePartyRaw);
    Order order = requireOrder(orderId);
    if (disputes.findOpenByOrderId(order.id()).isPresent()) {
      throw new AppException("VALIDATION_ERROR", "Order already has an open dispute", 409);
    }
    Instant now = clock.instant();
    OrderDispute dispute =
        disputes.insert(
            new OrderDispute(
                UUID.randomUUID(),
                order.id(),
                reason.trim(),
                liable,
                principal.subject(),
                now,
                false,
                null,
                null));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("is_disputed", true);
    data.put("dispute_reason", dispute.reason());
    data.put("liable_party", dispute.liableParty().name());
    data.put("flagged_by", dispute.flaggedBy().toString());
    data.put("flagged_at", dispute.flaggedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> addNote(
      MedmatePrincipal principal, UUID orderId, String noteText, Boolean pinned) {
    requireNoteRole(principal);
    rateLimit("admin:orders:note:" + principal.subject(), 20, 60);
    if (noteText == null || noteText.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "note is required", 400);
    }
    if (noteText.trim().length() > 2000) {
      throw new AppException("VALIDATION_ERROR", "note max 2000 chars", 400);
    }
    Order order = requireOrder(orderId);
    Instant now = clock.instant();
    OrderNote note =
        notes.insert(
            new OrderNote(
                UUID.randomUUID(),
                order.id(),
                noteText.trim(),
                Boolean.TRUE.equals(pinned),
                principal.subject(),
                now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("note_id", note.id().toString());
    data.put("order_id", order.id().toString());
    data.put("note", note.note());
    data.put("is_pinned", note.pinned());
    data.put("added_by", note.addedBy().toString());
    data.put("created_at", note.createdAt().toString());
    return data;
  }

  /** Notes are append-only — controller returns 405 after this guard. */
  public void requireNoteDeleteDenied(MedmatePrincipal principal) {
    requireAnyAdmin(principal);
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Map<String, Object> liveFeed(MedmatePrincipal principal) {
    requireOpsOrSuper(principal);
    rateLimit("admin:orders:live-feed:" + principal.subject(), 60, 60);

    var cached = liveFeedCache.get(RedisLiveFeedCache.KEY);
    if (cached.isPresent()) {
      try {
        return objectMapper.readValue(cached.get(), Map.class);
      } catch (JsonProcessingException ignored) {
        // rebuild
      }
    }

    Instant now = clock.instant();
    List<AdminOrderListRow> rows = query.liveFeed(now, LIVE_FEED_LIMIT);
    long slaRisk = 0;
    long slaBreached = 0;
    List<Map<String, Object>> ordersOut = new ArrayList<>(rows.size());
    for (AdminOrderListRow row : rows) {
      Order o = row.order();
      boolean risk = o.slaRisk(now);
      boolean breached = slaBreachedView(o, now);
      if (risk) {
        slaRisk++;
      }
      if (breached) {
        slaBreached++;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("order_id", o.id().toString());
      m.put("order_number", o.orderNumber());
      m.put("status", o.status().name());
      m.put("pharmacy_name", row.pharmacyName());
      m.put("area", row.area());
      m.put("customer_name", row.customerName());
      m.put("sla_remaining_minutes", o.slaRemainingMinutesRaw(now));
      m.put("sla_risk", risk);
      m.put("sla_breached", breached);
      m.put("is_disputed", row.disputed());
      m.put("total", CartPricing.paiseToRupees(o.totalPayablePaise()));
      ordersOut.add(m);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_live", rows.size());
    data.put("sla_risk_count", slaRisk);
    data.put("sla_breached_count", slaBreached);
    data.put("last_updated_at", now.toString());
    data.put("orders", ordersOut);

    liveFeedCache.put(RedisLiveFeedCache.KEY, writeJson(data), LIVE_FEED_TTL);
    return data;
  }

  /** Completes async export jobs (also callable from tests). */
  public void processExportJob(UUID jobId) {
    AdminOrderExportJob job =
        exportJobs
            .findById(jobId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Export job not found", 404));
    try {
      AdminListFilter filter = parseFilters(job.filtersJson());
      // Allow large exports for async path
      List<AdminOrderListRow> rows =
          query.listAllForExport(filter, Math.max(ASYNC_EXPORT_THRESHOLD * 2, 50_000));
      byte[] csv = buildCsv(rows, filter.now());
      String key = StorageObjectKeys.export("admin-orders-" + jobId + ".csv");
      exportObjects.put(key, csv, "text/csv");
      exportJobs.markReady(jobId, key, rows.size(), clock.instant());
    } catch (RuntimeException e) {
      exportJobs.markFailed(jobId, clock.instant());
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> exportJobStatus(MedmatePrincipal principal, UUID jobId) {
    requireAnyAdmin(principal);
    AdminOrderExportJob job =
        exportJobs
            .findById(jobId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Export job not found", 404));
    return exportJobMap(job);
  }

  @Transactional
  protected Map<String, Object> startExport(MedmatePrincipal principal, AdminListFilter filter) {
    long count = query.count(filter);
    Instant now = clock.instant();
    String filtersJson = toFiltersJson(filter);
    UUID jobId = UUID.randomUUID();
    if (count <= ASYNC_EXPORT_THRESHOLD) {
      List<AdminOrderListRow> rows =
          query.listAllForExport(filter, (int) Math.min(count, ASYNC_EXPORT_THRESHOLD));
      byte[] csv = buildCsv(rows, filter.now());
      String key = StorageObjectKeys.export("admin-orders-" + jobId + ".csv");
      exportObjects.put(key, csv, "text/csv");
      exportJobs.insert(
          new AdminOrderExportJob(
              jobId,
              principal.subject(),
              filtersJson,
              rows.size(),
              ExportJobStatus.READY,
              key,
              now,
              now));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("job_id", jobId.toString());
      data.put("status", ExportJobStatus.READY.name());
      data.put("row_count", rows.size());
      data.put("download_url", exportObjects.createDownloadUrl(key));
      return data;
    }

    exportJobs.insert(
        new AdminOrderExportJob(
            jobId,
            principal.subject(),
            filtersJson,
            null,
            ExportJobStatus.PROCESSING,
            null,
            now,
            null));
    exportExecutor.execute(() -> processExportJob(jobId));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("status", ExportJobStatus.PROCESSING.name());
    data.put("row_count", null);
    data.put("download_url", null);
    return data;
  }

  private Map<String, Object> exportJobMap(AdminOrderExportJob job) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id().toString());
    data.put("status", job.status().name());
    data.put("row_count", job.rowCount());
    data.put(
        "download_url", job.s3Key() == null ? null : exportObjects.createDownloadUrl(job.s3Key()));
    data.put("created_at", job.createdAt().toString());
    data.put("completed_at", job.completedAt() == null ? null : job.completedAt().toString());
    return data;
  }

  private byte[] buildCsv(List<AdminOrderListRow> rows, Instant now) {
    StringBuilder sb = new StringBuilder();
    sb.append("order_id,order_number,customer_name,customer_phone,pharmacy_name,area,status,");
    sb.append("sla_remaining_minutes,sla_breached,is_disputed,total,commission,payment_method,");
    sb.append("payment_status,has_prescription,item_total,delivery_fee,handling_fee,created_at\n");
    for (AdminOrderListRow row : rows) {
      Order o = row.order();
      long commission =
          AdminOrderQueryPort.commissionPaise(o.totalPayablePaise(), row.commissionPct());
      sb.append(csv(o.id().toString())).append(',');
      sb.append(csv(o.orderNumber())).append(',');
      sb.append(csv(row.customerName())).append(',');
      sb.append(csv(row.customerPhone())).append(',');
      sb.append(csv(row.pharmacyName())).append(',');
      sb.append(csv(row.area())).append(',');
      sb.append(csv(o.status().name())).append(',');
      sb.append(o.slaRemainingMinutesClamped(now)).append(',');
      sb.append(slaBreachedView(o, now)).append(',');
      sb.append(row.disputed()).append(',');
      sb.append(CartPricing.paiseToRupees(o.totalPayablePaise())).append(',');
      sb.append(CartPricing.paiseToRupees(commission)).append(',');
      sb.append(csv(o.paymentMethod().name())).append(',');
      sb.append(csv(o.paymentStatus().name())).append(',');
      sb.append(o.prescriptionId() != null).append(',');
      sb.append(CartPricing.paiseToRupees(o.itemTotalPaise())).append(',');
      sb.append(CartPricing.paiseToRupees(o.deliveryFeePaise())).append(',');
      sb.append(CartPricing.paiseToRupees(o.handlingFeePaise())).append(',');
      sb.append(csv(o.createdAt().toString())).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String csv(String v) {
    if (v == null) {
      return "";
    }
    String escaped = v.replace("\"", "\"\"");
    if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
      return "\"" + escaped + "\"";
    }
    return escaped;
  }

  private AdminListFilter buildFilter(
      String segmentRaw,
      String search,
      UUID pharmacyId,
      UUID riderId,
      UUID zoneId,
      String paymentMethodRaw,
      Boolean isRxOnly,
      LocalDate fromDate,
      LocalDate toDate,
      Integer page,
      Integer limit) {
    AdminOrderSegment segment = AdminOrderSegment.ALL;
    if (segmentRaw != null && !segmentRaw.isBlank()) {
      try {
        segment = AdminOrderSegment.valueOf(segmentRaw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new AppException(
            "VALIDATION_ERROR",
            "segment must be ALL|LIVE|SLA_RISK|DISPUTES|DELIVERED|CANCELLED",
            400);
      }
    }
    PaymentMethod paymentMethod = null;
    if (paymentMethodRaw != null && !paymentMethodRaw.isBlank()) {
      try {
        paymentMethod = PaymentMethod.valueOf(paymentMethodRaw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new AppException("VALIDATION_ERROR", "invalid payment_method", 400);
      }
    }
    PageRequest pageReq = PageRequest.normalize(page, limit, "created_at", "desc");
    return new AdminListFilter(
        segment,
        blankToNull(search),
        pharmacyId,
        riderId,
        zoneId,
        paymentMethod,
        isRxOnly,
        fromDate,
        toDate,
        clock.instant(),
        pageReq.page(),
        pageReq.limit());
  }

  private String toFiltersJson(AdminListFilter filter) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("segment", filter.segment().name());
    m.put("search", filter.search());
    m.put("pharmacy_id", filter.pharmacyId() == null ? null : filter.pharmacyId().toString());
    m.put("rider_id", filter.riderId() == null ? null : filter.riderId().toString());
    m.put("zone_id", filter.zoneId() == null ? null : filter.zoneId().toString());
    m.put("payment_method", filter.paymentMethod() == null ? null : filter.paymentMethod().name());
    m.put("is_rx_only", filter.isRxOnly());
    m.put("from_date", filter.fromDate() == null ? null : filter.fromDate().toString());
    m.put("to_date", filter.toDate() == null ? null : filter.toDate().toString());
    return writeJson(m);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private AdminListFilter parseFilters(String json) {
    try {
      Map<String, Object> m = objectMapper.readValue(json, Map.class);
      String segment = String.valueOf(m.getOrDefault("segment", "ALL"));
      String search = m.get("search") == null ? null : String.valueOf(m.get("search"));
      UUID pharmacyId = uuidOrNull(m.get("pharmacy_id"));
      UUID riderId = uuidOrNull(m.get("rider_id"));
      UUID zoneId = uuidOrNull(m.get("zone_id"));
      String pm = m.get("payment_method") == null ? null : String.valueOf(m.get("payment_method"));
      Boolean rx =
          m.get("is_rx_only") == null ? null : Boolean.valueOf(String.valueOf(m.get("is_rx_only")));
      LocalDate from =
          m.get("from_date") == null || "null".equals(String.valueOf(m.get("from_date")))
              ? null
              : LocalDate.parse(String.valueOf(m.get("from_date")));
      LocalDate to =
          m.get("to_date") == null || "null".equals(String.valueOf(m.get("to_date")))
              ? null
              : LocalDate.parse(String.valueOf(m.get("to_date")));
      return buildFilter(segment, search, pharmacyId, riderId, zoneId, pm, rx, from, to, 1, 100);
    } catch (JsonProcessingException | IllegalArgumentException | DateTimeException e) {
      return buildFilter("ALL", null, null, null, null, null, null, null, null, 1, 100);
    }
  }

  private Map<String, Object> toListItem(AdminOrderListRow row, Instant now) {
    Order o = row.order();
    long commission =
        AdminOrderQueryPort.commissionPaise(o.totalPayablePaise(), row.commissionPct());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("order_id", o.id().toString());
    m.put("order_number", o.orderNumber());
    m.put("customer_name", row.customerName());
    m.put("customer_phone", row.customerPhone());
    m.put("pharmacy_name", row.pharmacyName());
    m.put("area", row.area());
    m.put("status", o.status().name());
    m.put("sla_remaining_minutes", o.slaRemainingMinutesClamped(now));
    m.put("sla_breached", slaBreachedView(o, now));
    m.put("is_disputed", row.disputed());
    m.put("total", CartPricing.paiseToRupees(o.totalPayablePaise()));
    m.put("commission", CartPricing.paiseToRupees(commission));
    m.put("payment_method", o.paymentMethod().name());
    m.put("payment_status", o.paymentStatus().name());
    m.put("has_prescription", o.prescriptionId() != null);
    m.put("created_at", o.createdAt().toString());
    return m;
  }

  private Map<String, Object> toSummaryMap(SummaryAgg s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total_orders", s.totalOrders());
    m.put("live_now", s.liveNow());
    m.put("sla_risk", s.slaRisk());
    m.put("gmv", CartPricing.paiseToRupees(s.gmvPaise()));
    m.put("commission", CartPricing.paiseToRupees(s.commissionPaise()));
    BigDecimal aov =
        s.totalOrders() == 0
            ? BigDecimal.ZERO.setScale(2)
            : CartPricing.paiseToRupees(s.gmvPaise())
                .divide(BigDecimal.valueOf(s.totalOrders()), 2, java.math.RoundingMode.HALF_UP);
    m.put("aov", aov);
    return m;
  }

  private List<Map<String, Object>> timeline(List<OrderStatusEvent> events, Order order) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (order.confirmedAt() != null) {
      Map<String, Object> first = new LinkedHashMap<>();
      first.put("status", OrderStatus.PENDING_ACCEPTANCE.name());
      first.put("timestamp", order.confirmedAt().toString());
      first.put("actor", "system");
      rows.add(first);
    }
    for (OrderStatusEvent e : events) {
      if (e.toStatus() == OrderStatus.PENDING_ACCEPTANCE && order.confirmedAt() != null) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("status", e.toStatus().name());
      row.put("timestamp", e.createdAt().toString());
      row.put("actor", e.actorType().name().toLowerCase(Locale.ROOT));
      rows.add(row);
    }
    return rows;
  }

  private Map<String, Object> disputeBanner(OrderDispute d) {
    if (d == null) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("reason", d.reason());
    m.put("liable_party", d.liableParty().name());
    m.put("flagged_by", d.flaggedBy().toString());
    m.put("flagged_at", d.flaggedAt().toString());
    return m;
  }

  private Map<String, Object> customerMap(CustomerAdminView c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.id().toString());
    m.put("name", c.name());
    m.put("phone", c.phone());
    m.put("order_count", c.orderCount());
    m.put("ltv", CartPricing.paiseToRupees(c.ltvPaise()));
    return m;
  }

  private Map<String, Object> pharmacyMap(PharmacyAdminView p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id().toString());
    m.put("name", p.name());
    m.put("area", p.area());
    m.put("commission_rate", p.commissionPct());
    return m;
  }

  private List<Map<String, Object>> itemsMap(List<OrderItemSnapshot> items) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (OrderItemSnapshot item : items) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", item.name());
      m.put("quantity", item.quantity());
      m.put("unit_price", CartPricing.paiseToRupees(item.unitPricePaise()));
      m.put("line_total", CartPricing.paiseToRupees(item.lineTotalPaise()));
      out.add(m);
    }
    return out;
  }

  private Map<String, Object> billMap(Order order, BigDecimal commissionPct, long commissionPaise) {
    long subtotal = order.itemTotalPaise() - order.couponDiscountPaise();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("item_total", CartPricing.paiseToRupees(order.itemTotalPaise()));
    m.put("coupon_code", order.couponCode());
    m.put("coupon_discount", CartPricing.paiseToRupees(order.couponDiscountPaise()));
    m.put("subtotal_after_discount", CartPricing.paiseToRupees(Math.max(0, subtotal)));
    m.put("delivery_fee", CartPricing.paiseToRupees(order.deliveryFeePaise()));
    m.put("handling_fee", CartPricing.paiseToRupees(order.handlingFeePaise()));
    m.put("wallet_applied", CartPricing.paiseToRupees(order.walletAppliedPaise()));
    m.put("total_payable", CartPricing.paiseToRupees(order.totalPayablePaise()));
    m.put("commission_amount", CartPricing.paiseToRupees(commissionPaise));
    m.put("commission_rate_pct", commissionPct);
    return m;
  }

  private Map<String, Object> paymentMap(Order order) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("method", order.paymentMethod().name());
    m.put("status", order.paymentStatus().name());
    m.put("transaction_id", order.razorpayPaymentId());
    m.put("razorpay_order_id", order.razorpayOrderId());
    return m;
  }

  private Map<String, Object> prescriptionCard(Order order) {
    if (order.prescriptionId() == null) {
      return null;
    }
    String status =
        prescriptions
            .findVerified(order.prescriptionId(), order.customerId())
            .map(r -> r.status())
            .orElse("ATTACHED");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", order.prescriptionId().toString());
    m.put("type", "E_PRESCRIPTION");
    m.put("status", status);
    // Locked: Rx file URL redacted for non-compliance; AC2: compliance also ID/type only here
    m.put("note", "Prescription content restricted. View via Compliance Audit (EPIC-008).");
    // Never include file_url — content routed via EPIC-008 compliance audit
    return m;
  }

  private Map<String, Object> deliveryPartner(Order order) {
    if (order.riderId() == null) {
      return null;
    }
    return riders
        .findById(order.riderId())
        .map(
            r -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("rider_id", r.id().toString());
              m.put("name", r.name());
              m.put("phone", r.phone());
              m.put("vehicle_plate", r.vehiclePlate());
              m.put("otp_verified", order.otpVerifiedAt() != null);
              return m;
            })
        .orElse(null);
  }

  private List<Map<String, Object>> notesMap(List<OrderNote> noteRows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (OrderNote n : noteRows) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("note_id", n.id().toString());
      m.put("note", n.note());
      m.put("added_by", n.addedBy().toString());
      m.put("added_by_name", query.findAdminName(n.addedBy()).map(a -> a.name()).orElse("Admin"));
      m.put("is_pinned", n.pinned());
      m.put("created_at", n.createdAt().toString());
      out.add(m);
    }
    return out;
  }

  private Order requireOrder(UUID orderId) {
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    return orders
        .findById(orderId)
        .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
  }

  private LiableParty parseLiable(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "liable_party is required", 400);
    }
    try {
      return LiableParty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException(
          "VALIDATION_ERROR", "liable_party must be PHARMACY|RIDER|PLATFORM|CUSTOMER", 400);
    }
  }

  private static final java.util.Set<AuthRole> ANY_ADMIN =
      java.util.Set.of(
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_FINANCE,
          AuthRole.ADMIN_SUPPORT,
          AuthRole.ADMIN_COMPLIANCE);
  private static final java.util.Set<AuthRole> OPS_OR_SUPER =
      java.util.Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final java.util.Set<AuthRole> DISPUTE_ROLES =
      java.util.Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final java.util.Set<AuthRole> NOTE_ROLES =
      java.util.Set.of(
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_SUPPORT,
          AuthRole.ADMIN_FINANCE);

  private void requireAnyAdmin(MedmatePrincipal principal) {
    if (principal == null || !ANY_ADMIN.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private void requireOpsOrSuper(MedmatePrincipal principal) {
    if (principal == null || !OPS_OR_SUPER.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "admin_super or admin_operations required", 403);
    }
  }

  private void requireDisputeRole(MedmatePrincipal principal) {
    if (principal == null || !DISPUTE_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Dispute flagging not allowed for this role", 403);
    }
  }

  private void requireNoteRole(MedmatePrincipal principal) {
    if (principal == null || !NOTE_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Notes not allowed for this role", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (rateLimiter != null && !rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static UUID uuidOrNull(Object v) {
    if (v == null || "null".equals(String.valueOf(v))) {
      return null;
    }
    return UUID.fromString(String.valueOf(v));
  }

  static boolean slaBreachedView(Order order, Instant now) {
    if (order.slaBreached()) {
      return true;
    }
    Instant deadline = order.slaDeadline();
    return deadline != null && now.isAfter(deadline);
  }
}
