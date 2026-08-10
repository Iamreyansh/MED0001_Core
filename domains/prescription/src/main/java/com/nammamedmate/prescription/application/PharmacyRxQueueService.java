package com.nammamedmate.prescription.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.prescription.application.port.out.CustomerContactPort;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryStockPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.OrderLinesPort;
import com.nammamedmate.prescription.application.port.out.OrderStatusPort;
import com.nammamedmate.prescription.application.port.out.PharmacyPlanPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore.Kpis;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.ScheduleRegisterWritePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyRxQueueService {

  private static final Set<String> REJECT_REASONS =
      Set.of("ILLEGIBLE", "UNVERIFIED_PRESCRIBER", "EXPIRED", "NOT_STOCKED", "INVALID");
  private static final Duration SIGNED_TTL = Duration.ofHours(1);
  private static final Duration KPI_TTL = Duration.ofSeconds(60);
  private static final Duration DUPLICATE_WINDOW = Duration.ofDays(30);

  private final PharmacyRxQueueStore queueStore;
  private final PrescriptionStore prescriptionStore;
  private final PharmacyPlanPort planPort;
  private final OrderLinesPort orderLines;
  private final OrderStatusPort orderStatus;
  private final PosDispensePort pos;
  private final NotificationDispatchPort notifications;
  private final DoctorCardPort doctorCards;
  private final ScheduleRegisterWritePort scheduleRegister;
  private final InventoryStockPort inventory;
  private final CustomerContactPort customers;
  private final PresignedUrlService presigner;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final RxComplianceAuditService complianceAudit;
  private final ConcurrentHashMap<UUID, CachedKpis> kpiCache = new ConcurrentHashMap<>();

  public PharmacyRxQueueService(
      PharmacyRxQueueStore queueStore,
      PrescriptionStore prescriptionStore,
      PharmacyPlanPort planPort,
      OrderLinesPort orderLines,
      OrderStatusPort orderStatus,
      PosDispensePort pos,
      NotificationDispatchPort notifications,
      DoctorCardPort doctorCards,
      ScheduleRegisterWritePort scheduleRegister,
      InventoryStockPort inventory,
      CustomerContactPort customers,
      PresignedUrlService presigner,
      RateLimiter rateLimiter,
      Clock clock) {
    this(
        queueStore,
        prescriptionStore,
        planPort,
        orderLines,
        orderStatus,
        pos,
        notifications,
        doctorCards,
        scheduleRegister,
        inventory,
        customers,
        presigner,
        rateLimiter,
        clock,
        null);
  }

  @Autowired
  public PharmacyRxQueueService(
      PharmacyRxQueueStore queueStore,
      PrescriptionStore prescriptionStore,
      PharmacyPlanPort planPort,
      OrderLinesPort orderLines,
      OrderStatusPort orderStatus,
      PosDispensePort pos,
      NotificationDispatchPort notifications,
      DoctorCardPort doctorCards,
      ScheduleRegisterWritePort scheduleRegister,
      InventoryStockPort inventory,
      CustomerContactPort customers,
      PresignedUrlService presigner,
      RateLimiter rateLimiter,
      Clock clock,
      @Autowired(required = false) RxComplianceAuditService complianceAudit) {
    this.queueStore = queueStore;
    this.prescriptionStore = prescriptionStore;
    this.planPort = planPort;
    this.orderLines = orderLines;
    this.orderStatus = orderStatus;
    this.pos = pos;
    this.notifications = notifications;
    this.doctorCards = doctorCards;
    this.scheduleRegister = scheduleRegister;
    this.inventory = inventory;
    this.customers = customers;
    this.presigner = presigner;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.complianceAudit = complianceAudit;
  }

  /** Application API for order/rx-quote bridges to land a prescription in a pharmacy queue. */
  @Transactional
  public UUID enqueue(UUID rxId, UUID pharmacyId, UUID orderId) {
    if (rxId == null || pharmacyId == null) {
      throw new AppException("VALIDATION_ERROR", "rx_id and pharmacy_id are required", 400);
    }
    if (queueStore.findByRxAndPharmacy(rxId, pharmacyId).isPresent()) {
      return queueStore.findByRxAndPharmacy(rxId, pharmacyId).orElseThrow().id();
    }
    Instant now = clock.instant();
    UUID id = Ids.newId();
    queueStore.insert(
        new PharmacyRxQueueEntry(
            id,
            rxId,
            pharmacyId,
            orderId,
            now,
            "PENDING_REVIEW",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            now,
            now,
            null));
    if (complianceAudit != null) {
      complianceAudit.applyPendingFlags(rxId, pharmacyId);
    }
    return id;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public ListResult list(
      MedmatePrincipal principal,
      String status,
      String source,
      String search,
      Integer pageRaw,
      Integer limitRaw,
      String sort) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:list:" + pharmacyId, 60, 60);
    int page = pageRaw == null ? 1 : Math.max(pageRaw, 1);
    int limit = limitRaw == null ? 20 : Math.min(Math.max(limitRaw, 1), 100);
    String statusFilter = normalizeStatus(status);
    String sourceFilter = normalizeSource(source);
    String sortKey =
        sort == null || sort.isBlank() ? "urgency" : sort.trim().toLowerCase(Locale.ROOT);
    Instant now = clock.instant();
    notifyOverdueOnList(pharmacyId, now);

    PharmacyRxQueueStore.Page result =
        queueStore.list(pharmacyId, statusFilter, sourceFilter, search, page, limit, sortKey);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (PharmacyRxQueueEntry e : result.items()) {
      PrescriptionRecord rx = prescriptionStore.findById(e.rxId()).orElse(null);
      if (rx == null) {
        continue;
      }
      rows.add(toListItem(e, rx, now));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpis", toKpiMap(kpisCached(pharmacyId, now)));
    data.put("prescriptions", rows);
    return new ListResult(data, PaginationMeta.of(page, limit, result.total()));
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID rxId) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:get:" + pharmacyId, 60, 60);
    PharmacyRxQueueEntry entry = requireEntry(rxId, pharmacyId);
    PrescriptionRecord rx = requireRx(entry.rxId());
    Instant now = clock.instant();
    boolean duplicate = detectDuplicate(rx, entry);
    return toDetail(entry, rx, now, duplicate);
  }

  @Transactional
  public Map<String, Object> approve(
      MedmatePrincipal principal, UUID rxId, List<ApprovedMedicine> medicines, String notes) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:approve:" + pharmacyId, 30, 60);
    PharmacyRxQueueEntry entry = requireEntry(rxId, pharmacyId);
    if (!"PENDING_REVIEW".equals(entry.status())) {
      throw new AppException(
          "RX_ALREADY_ACTIONED", "Prescription already approved/rejected/dispensed", 409);
    }
    if (medicines == null || medicines.isEmpty()) {
      throw new AppException("APPROVED_MEDICINES_EMPTY", "approved_medicines list is empty", 422);
    }
    String normalizedNotes = normalizeNotes(notes);
    PrescriptionRecord rx = requireRx(entry.rxId());
    boolean duplicate = detectDuplicate(rx, entry);
    Instant now = clock.instant();
    queueStore.markApproved(
        entry.id(), medicines, principal.subject(), now, normalizedNotes, duplicate, now);
    if (entry.orderId() != null) {
      orderLines.replaceOrderLines(entry.orderId(), medicines);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("status", "APPROVED");
    data.put("approved_medicines", approvedViews(medicines));
    data.put("approved_by", principal.subject());
    data.put("approved_at", now);
    data.put("order_id", entry.orderId());
    if (duplicate) {
      data.put("warning", "POSSIBLE_DUPLICATE_RX");
    }
    return data;
  }

  @Transactional
  public Map<String, Object> reject(
      MedmatePrincipal principal, UUID rxId, String reason, String customMessage) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:reject:" + pharmacyId, 30, 60);
    PharmacyRxQueueEntry entry = requireEntry(rxId, pharmacyId);
    if (!"PENDING_REVIEW".equals(entry.status())) {
      throw new AppException(
          "RX_ALREADY_ACTIONED", "Prescription already approved/rejected/dispensed", 409);
    }
    String code = reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT);
    if (!REJECT_REASONS.contains(code)) {
      throw new AppException("INVALID_REJECTION_REASON", "Reason not in allowed ENUM values", 422);
    }
    String message = normalizeCustomMessage(customMessage);
    PrescriptionRecord rx = requireRx(entry.rxId());
    Instant now = clock.instant();
    queueStore.markRejected(entry.id(), code, message, principal.subject(), now, now);
    notifications.notifyCustomerRxRejected(rx.customerId(), rxId, code, message);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("status", "REJECTED");
    data.put("reason", code);
    data.put("custom_message", message);
    data.put("rejected_by", principal.subject());
    data.put("rejected_at", now);
    data.put("customer_notified", true);
    return data;
  }

  @Transactional
  public Map<String, Object> dispense(MedmatePrincipal principal, UUID rxId) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:dispense:" + pharmacyId, 30, 60);
    PharmacyRxQueueEntry entry = requireEntry(rxId, pharmacyId);
    if (!"APPROVED".equals(entry.status())) {
      throw new AppException("RX_NOT_APPROVED", "Cannot dispense without prior approval", 409);
    }
    Instant now = clock.instant();
    List<ApprovedMedicine> meds =
        entry.approvedMedicines() == null ? List.of() : entry.approvedMedicines();
    UUID saleId = pos.createSaleRecord(pharmacyId, principal.subject(), entry.orderId(), meds);
    scheduleRegister.recordDispense(pharmacyId, rxId, principal.subject(), meds);
    queueStore.markDispensed(entry.id(), principal.subject(), now, now);
    if (entry.orderId() != null) {
      orderStatus.markReadyForPickup(entry.orderId());
    }
    if (complianceAudit != null) {
      complianceAudit.createFromDispense(
          rxId, entry.orderId(), pharmacyId, meds, requireRx(rxId), now);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("status", "DISPENSED");
    data.put("dispensed_by", principal.subject());
    data.put("dispensed_at", now);
    data.put("sale_record_id", saleId);
    data.put("order_status_updated_to", "READY_FOR_PICKUP");
    return data;
  }

  @Transactional
  public Map<String, Object> dispenseToBilling(MedmatePrincipal principal, UUID rxId) {
    UUID pharmacyId = requirePharmacy(principal);
    requirePlan(pharmacyId);
    rateLimit("rxq:billing:" + pharmacyId, 10, 60);
    PharmacyRxQueueEntry entry = requireEntry(rxId, pharmacyId);
    if (!"APPROVED".equals(entry.status()) && !"DISPENSED".equals(entry.status())) {
      throw new AppException("RX_NOT_APPROVED", "Prescription has not been approved yet", 409);
    }
    if (!pos.available()) {
      throw new AppException("POS_UNAVAILABLE", "POS integration unavailable", 503);
    }
    List<ApprovedMedicine> meds =
        entry.approvedMedicines() == null ? List.of() : entry.approvedMedicines();
    UUID cartId = pos.pushToBillingCart(pharmacyId, principal.subject(), meds);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rx_id", rxId);
    data.put("pos_cart_id", cartId);
    data.put("medicines_loaded", meds.size());
    data.put("message", "Approved medicines pushed to POS billing cart");
    return data;
  }

  @Transactional
  public int notifyOverdue() {
    Instant now = clock.instant();
    Instant deadline = now.minus(PharmacyRxQueueEntry.SLA);
    List<PharmacyRxQueueEntry> overdue = queueStore.findPendingOverdueUnnotified(deadline, 100);
    int n = 0;
    for (PharmacyRxQueueEntry e : overdue) {
      notifications.notifyPharmacyOwnerOverdue(e.pharmacyId(), e.rxId());
      queueStore.markOverdueNotified(e.id(), now, now);
      n++;
    }
    return n;
  }

  private void notifyOverdueOnList(UUID pharmacyId, Instant now) {
    Instant deadline = now.minus(PharmacyRxQueueEntry.SLA);
    for (PharmacyRxQueueEntry e : queueStore.findPendingOverdueUnnotified(deadline, 50)) {
      if (!pharmacyId.equals(e.pharmacyId())) {
        continue;
      }
      notifications.notifyPharmacyOwnerOverdue(e.pharmacyId(), e.rxId());
      queueStore.markOverdueNotified(e.id(), now, now);
    }
  }

  private boolean detectDuplicate(PrescriptionRecord rx, PharmacyRxQueueEntry entry) {
    if (rx.medicinesExtracted() == null || rx.medicinesExtracted().isEmpty()) {
      return entry.duplicateWarning();
    }
    Instant since = clock.instant().minus(DUPLICATE_WINDOW);
    for (MedicineExtracted m : rx.medicinesExtracted()) {
      if (m.name() == null || m.name().isBlank()) {
        continue;
      }
      if (queueStore.hasDuplicateDispense(rx.customerId(), m.name().trim(), since, rx.id())) {
        return true;
      }
    }
    return entry.duplicateWarning();
  }

  private Kpis kpisCached(UUID pharmacyId, Instant now) {
    CachedKpis cached = kpiCache.get(pharmacyId);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.kpis();
    }
    Kpis fresh = queueStore.computeKpis(pharmacyId, now);
    kpiCache.put(pharmacyId, new CachedKpis(fresh, now.plus(KPI_TTL)));
    return fresh;
  }

  private record CachedKpis(Kpis kpis, Instant expiresAt) {}

  private Map<String, Object> toKpiMap(Kpis k) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pending_review", k.pendingReview());
    m.put("pending_review_overdue", k.pendingReviewOverdue());
    m.put("awaiting_dispense", k.awaitingDispense());
    m.put("dispensed_today_count", k.dispensedTodayCount());
    m.put("dispensed_today_value", paiseToRupees(k.dispensedTodayValuePaise()));
    m.put("avg_turnaround_minutes", k.avgTurnaroundMinutes());
    m.put("digital_share_pct", k.digitalSharePct());
    m.put("sla_on_time_pct", k.slaOnTimePct());
    return m;
  }

  private Map<String, Object> toListItem(
      PharmacyRxQueueEntry e, PrescriptionRecord rx, Instant now) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rx_id", e.rxId());
    m.put("type", rx.type());
    m.put("status", e.status());
    m.put("is_overdue", e.isOverdue(now));
    m.put("overdue_by_minutes", e.overdueByMinutes(now));
    m.put("patient_name", rx.patientName());
    m.put("doctor_name", rx.doctorName());
    m.put("received_at", e.receivedAt());
    m.put("sla_deadline", e.slaDeadline());
    m.put("medicines_extracted", medicinesExtractedMaps(rx.medicinesExtracted()));
    m.put("order_id", e.orderId());
    m.put("source", "E_PRESCRIPTION".equals(rx.type()) ? "DIGITAL" : "UPLOADED");
    return m;
  }

  private Map<String, Object> toDetail(
      PharmacyRxQueueEntry e, PrescriptionRecord rx, Instant now, boolean duplicate) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rx_id", e.rxId());
    m.put("type", rx.type());
    m.put("status", e.status());
    m.put("is_overdue", e.isOverdue(now));
    m.put("file_url", freshFileUrl(rx.s3Key()));
    CustomerContactPort.Contact contact =
        customers
            .find(rx.customerId())
            .orElse(new CustomerContactPort.Contact(rx.patientName(), null));
    Map<String, Object> patient = new LinkedHashMap<>();
    patient.put("name", contact.name() != null ? contact.name() : rx.patientName());
    patient.put("phone", contact.phone());
    patient.put("customer_id", rx.customerId());
    patient.put(
        "previous_orders_count", customers.previousOrdersCount(rx.customerId(), e.pharmacyId()));
    m.put("patient", patient);

    DoctorCardPort.DoctorCard doctor =
        doctorCards
            .findForPrescription(rx.id(), rx.type(), rx.doctorName(), rx.teleconsultId())
            .orElse(
                new DoctorCardPort.DoctorCard(
                    rx.doctorName(), null, null, "E_PRESCRIPTION".equals(rx.type())));
    Map<String, Object> doctorMap = new LinkedHashMap<>();
    doctorMap.put("name", doctor.name());
    doctorMap.put("qualification", doctor.qualification());
    doctorMap.put("registration_no", doctor.registrationNo());
    doctorMap.put("verified", doctor.verified());
    m.put("doctor", doctorMap);

    List<Map<String, Object>> verified = new ArrayList<>();
    BigDecimal bill = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    if (rx.medicinesExtracted() != null) {
      for (MedicineExtracted med : rx.medicinesExtracted()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", med.name());
        int qty = parseQty(med.quantity());
        row.put("quantity", qty);
        InventoryStockPort.StockInfo stock =
            inventory.findByName(e.pharmacyId(), med.name()).orElse(null);
        boolean inStock = stock != null && stock.inStock();
        int stockQty = stock == null ? 0 : stock.stockQty();
        BigDecimal price =
            stock == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : paiseToRupees(stock.unitPricePaise());
        row.put("in_stock", inStock);
        row.put("stock_qty", stockQty);
        row.put("price", price);
        if (inStock) {
          bill = bill.add(price.multiply(BigDecimal.valueOf(qty)));
        }
        verified.add(row);
      }
    }
    m.put("medicines_verified", verified);
    m.put("estimated_bill_value", bill.setScale(2, RoundingMode.HALF_UP));
    m.put("duplicate_rx_warning", duplicate);
    if (duplicate) {
      m.put("warning", "POSSIBLE_DUPLICATE_RX");
    }
    List<Map<String, Object>> timeline = new ArrayList<>();
    timeline.add(event("UPLOADED", rx.createdAt(), "customer"));
    timeline.add(event("RECEIVED_BY_PHARMACY", e.receivedAt(), "system"));
    if (e.approvedAt() != null) {
      timeline.add(event("APPROVED", e.approvedAt(), "pharmacist"));
    }
    if (e.rejectedAt() != null) {
      timeline.add(event("REJECTED", e.rejectedAt(), "pharmacist"));
    }
    if (e.dispensedAt() != null) {
      timeline.add(event("DISPENSED", e.dispensedAt(), "pharmacist"));
    }
    m.put("timeline", timeline);
    m.put("order_id", e.orderId());
    m.put("received_at", e.receivedAt());
    m.put("sla_deadline", e.slaDeadline());
    return m;
  }

  private static Map<String, Object> event(String name, Instant ts, String actor) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("event", name);
    e.put("timestamp", ts);
    e.put("actor", actor);
    return e;
  }

  private static List<Map<String, Object>> medicinesExtractedMaps(List<MedicineExtracted> meds) {
    if (meds == null) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (MedicineExtracted m : meds) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", m.name());
      row.put("quantity", m.quantity());
      out.add(row);
    }
    return out;
  }

  private static List<Map<String, Object>> approvedViews(List<ApprovedMedicine> medicines) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ApprovedMedicine m : medicines) {
      BigDecimal price = m.price() == null ? BigDecimal.ZERO : m.price();
      BigDecimal line =
          price.multiply(BigDecimal.valueOf(m.quantity())).setScale(2, RoundingMode.HALF_UP);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", m.name());
      row.put("quantity", m.quantity());
      row.put("price", price.setScale(2, RoundingMode.HALF_UP));
      row.put("line_total", line);
      out.add(row);
    }
    return out;
  }

  private String freshFileUrl(String s3Key) {
    String base = presigner.createGetUrl(s3Key, SIGNED_TTL).url();
    String sep = base.contains("?") ? "&" : "?";
    return base + sep + "n=" + Ids.newId();
  }

  private PharmacyRxQueueEntry requireEntry(UUID rxId, UUID pharmacyId) {
    return queueStore
        .findByRxAndPharmacy(rxId, pharmacyId)
        .orElseThrow(
            () ->
                new AppException("RX_NOT_FOUND", "Prescription not in this pharmacy's queue", 404));
  }

  private PrescriptionRecord requireRx(UUID rxId) {
    return prescriptionStore
        .findById(rxId)
        .orElseThrow(() -> new AppException("RX_NOT_FOUND", "Prescription not found", 404));
  }

  private void requirePlan(UUID pharmacyId) {
    if (!planPort.rxQueueEnabled(pharmacyId)) {
      throw new AppException(
          "PLAN_UPGRADE_REQUIRED", "Rx queue requires Starter plan or higher", 403);
    }
  }

  private static UUID requirePharmacy(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.PHARMACY_OWNER
            && principal.role() != AuthRole.PHARMACY_STAFF)
        || principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy authentication required", 401);
    }
    return principal.pharmacyId();
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank() || "PENDING".equalsIgnoreCase(status.trim())) {
      return "PENDING_REVIEW";
    }
    if ("ALL".equalsIgnoreCase(status.trim())) {
      return null;
    }
    return status.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeSource(String source) {
    if (source == null || source.isBlank()) {
      return null;
    }
    String s = source.trim().toUpperCase(Locale.ROOT);
    if ("ALL".equals(s)) {
      return null;
    }
    if ("DIGITAL".equals(s) || "UPLOADED".equals(s)) {
      return s;
    }
    throw new AppException("VALIDATION_ERROR", "source must be DIGITAL or UPLOADED", 400);
  }

  private static String normalizeNotes(String notes) {
    if (notes == null || notes.isBlank()) {
      return null;
    }
    String n = notes.trim();
    if (n.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 characters", 400);
    }
    return n;
  }

  private static String normalizeCustomMessage(String raw) {
    if (raw == null) {
      return null;
    }
    String m = raw.trim();
    if (m.isEmpty()) {
      return null;
    }
    if (m.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "custom_message max 300 characters", 400);
    }
    return m;
  }

  private static int parseQty(String raw) {
    if (raw == null) {
      return 1;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return 1;
    }
    raw = trimmed;
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (Character.isDigit(c)) {
        digits.append(c);
      } else if (digits.length() > 0) {
        break;
      }
    }
    if (digits.isEmpty()) {
      return 1;
    }
    try {
      return Math.max(1, Integer.parseInt(digits.toString()));
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
