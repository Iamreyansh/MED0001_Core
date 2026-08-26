package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueueOrder;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueuePage;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.rider.domain.AssignmentScoring;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchService {

  public static final Duration ACCEPT_WINDOW = Duration.ofMinutes(5);
  private static final Set<String> REASSIGN_REASONS =
      Set.of("RIDER_NO_SHOW", "RIDER_OFFLINE", "PERFORMANCE", "OTHER");
  private static final Set<AuthRole> ADMIN_ROLES =
      Set.of(AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPER);

  private final OrderAssignmentStore assignments;
  private final DispatchOrderPort orders;
  private final RiderStore riders;
  private final RiderFleetStore fleet;
  private final DistanceMatrixPort distance;
  private final AssignmentOtpCachePort otpCache;
  private final OutboxPublisher outbox;
  private final Clock clock;
  private final boolean autoAssignEnabled;

  public DispatchService(
      OrderAssignmentStore assignments,
      DispatchOrderPort orders,
      RiderStore riders,
      RiderFleetStore fleet,
      DistanceMatrixPort distance,
      AssignmentOtpCachePort otpCache,
      OutboxPublisher outbox,
      Clock clock,
      @Value("${medmate.rider.auto-assign-enabled:true}") boolean autoAssignEnabled) {
    this.assignments = assignments;
    this.orders = orders;
    this.riders = riders;
    this.fleet = fleet;
    this.distance = distance;
    this.otpCache = otpCache;
    this.outbox = outbox;
    this.clock = clock;
    this.autoAssignEnabled = autoAssignEnabled;
  }

  public record QueueResult(Map<String, Object> data, PaginationMeta meta) {
    public QueueResult {
      data = Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public QueueResult queue(MedmatePrincipal principal, UUID zoneId, Integer page, Integer limit) {
    requireAdmin(principal);
    int p = 1;
    if (page != null && page >= 1) {
      p = page;
    }
    int lim = 20;
    if (limit != null && limit >= 1) {
      lim = Math.min(limit, 100);
    }
    QueuePage qp = orders.listUnassignedReady(zoneId, p, lim);
    Instant now = clock.instant();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (QueueOrder o : qp.rows()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("order_id", o.orderId().toString());
      row.put("order_number", o.orderNumber());
      row.put("pharmacy_id", o.pharmacyId().toString());
      row.put("pharmacy_name", o.pharmacyName());
      row.put("zone_id", o.zoneId() == null ? null : o.zoneId().toString());
      row.put("zone_name", o.zoneName());
      row.put("items_count", o.itemsCount());
      row.put("order_value", paiseToRupees(o.orderValuePaise()));
      row.put("payment_method", o.paymentMethod());
      row.put("created_at", o.createdAt().toString());
      Instant waitFrom = o.readyForPickupAt() != null ? o.readyForPickupAt() : o.createdAt();
      row.put("wait_minutes", Math.max(0, Duration.between(waitFrom, now).toMinutes()));
      row.put("recommended_rider", recommendedRiderView(o));
      rows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("queue", rows);
    return new QueueResult(data, PaginationMeta.of(p, lim, qp.total(), qp.total()));
  }

  @Transactional
  public Map<String, Object> assignManual(MedmatePrincipal principal, UUID orderId, UUID riderId) {
    requireAdmin(principal);
    if (riderId == null) {
      throw new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404);
    }
    OrderDetails order = requireReadyUnassigned(orderId);
    RiderRecord rider = requireOnlineEligible(riderId);
    ScoredRider scored = scoreRider(rider, order.pharmacyLat(), order.pharmacyLng());
    AssignmentRecord created =
        createAssignment(
            order, rider, "MANUAL", principal.subject(), scored.score(), clock.instant());
    return assignmentResponse(created, rider.name(), principal.subject());
  }

  @Transactional
  public Map<String, Object> autoAssignAll(MedmatePrincipal principal) {
    requireAdmin(principal);
    if (!autoAssignEnabled) {
      throw new AppException("AUTO_ASSIGN_DISABLED", "auto_assign_enabled is false", 403);
    }
    QueuePage qp = orders.listUnassignedReady(null, 1, 100);
    int attempted = qp.rows().size();
    List<Map<String, Object>> assigned = new ArrayList<>();
    List<Map<String, Object>> unassigned = new ArrayList<>();
    for (QueueOrder qo : qp.rows()) {
      if (assignments.hasActiveForOrder(qo.orderId())) {
        continue;
      }
      Optional<OrderDetails> details = orders.findOrder(qo.orderId());
      if (details.isEmpty() || !"READY_FOR_PICKUP".equals(details.get().status())) {
        unassigned.add(Map.of("order_id", qo.orderId().toString(), "reason", "NO_ELIGIBLE_RIDER"));
        continue;
      }
      Optional<ScoredRider> best = pickBestRider(details.get());
      if (best.isEmpty()) {
        unassigned.add(Map.of("order_id", qo.orderId().toString(), "reason", "NO_ELIGIBLE_RIDER"));
        continue;
      }
      ScoredRider s = best.get();
      createAssignment(details.get(), s.rider(), "AUTO", null, s.score(), clock.instant());
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("order_id", qo.orderId().toString());
      a.put("rider_id", s.rider().id().toString());
      a.put("rider_name", s.rider().name());
      a.put("composite_score", s.score());
      assigned.add(a);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("attempted", attempted);
    data.put("assigned", assigned.size());
    data.put("failed_no_rider", unassigned.size());
    data.put("assignments", assigned);
    data.put("unassigned_orders", unassigned);
    return data;
  }

  /** Idempotent automation hook: assign best rider to one READY_FOR_PICKUP order. */
  @Transactional
  public void autoAssignOrder(UUID orderId) {
    if (orderId == null || !autoAssignEnabled) {
      return;
    }
    if (assignments.hasActiveForOrder(orderId)) {
      return;
    }
    OrderDetails order = orders.findOrder(orderId).orElse(null);
    if (order == null || !"READY_FOR_PICKUP".equals(order.status()) || order.riderId() != null) {
      return;
    }
    pickBestRider(order)
        .ifPresent(
            s -> createAssignment(order, s.rider(), "AUTO", null, s.score(), clock.instant()));
  }

  @Transactional
  public Map<String, Object> reassign(
      MedmatePrincipal principal, UUID orderId, UUID riderId, String reason) {
    requireAdmin(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason field missing", 422);
    }
    String reasonNorm = reason.trim().toUpperCase(Locale.ROOT);
    if (!REASSIGN_REASONS.contains(reasonNorm)) {
      throw new AppException("REASON_REQUIRED", "reason field missing", 422);
    }
    if (riderId == null) {
      throw new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404);
    }
    AssignmentRecord active =
        assignments
            .findActiveByOrder(orderId)
            .orElseThrow(
                () ->
                    new AppException(
                        "ORDER_NOT_ASSIGNED", "Order has no active assignment to reassign", 422));
    OrderDetails order =
        orders
            .findOrder(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    RiderRecord newRider = requireOnlineEligible(riderId);
    Instant now = clock.instant();
    UUID previousRiderId = active.riderId();
    if ("ACCEPTED".equals(active.status()) || "PICKED_UP".equals(active.status())) {
      otpCache.decrConcurrent(previousRiderId);
    }
    assignments.update(withStatus(active, "REASSIGNED", reasonNorm, now));
    ScoredRider scored = scoreRider(newRider, order.pharmacyLat(), order.pharmacyLng());
    AssignmentRecord created =
        createAssignment(order, newRider, "MANUAL", principal.subject(), scored.score(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("assignment_id", created.id().toString());
    data.put("order_id", orderId.toString());
    data.put("previous_rider_id", previousRiderId.toString());
    data.put("new_rider_id", newRider.id().toString());
    data.put("reason", reasonNorm);
    data.put("reassigned_by", principal.subject().toString());
    data.put("reassigned_at", now.toString());
    return data;
  }

  /** AC-002: TIMED_OUT + clear rider + optional re-auto-assign. */
  @Transactional
  public int timeoutExpiredAssignments() {
    Instant now = clock.instant();
    List<AssignmentRecord> expired = assignments.findPendingPastDeadline(now, 50);
    int count = 0;
    for (AssignmentRecord a : expired) {
      assignments.update(withStatus(a, "TIMED_OUT", null, now));
      orders.clearRiderOnOrder(a.orderId(), now);
      otpCache.evict(a.orderId());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("order_id", a.orderId().toString());
      payload.put("assignment_id", a.id().toString());
      payload.put("rider_id", a.riderId().toString());
      payload.put("alert", "ASSIGNMENT_TIMED_OUT");
      payload.put("audience", "admin_operations");
      outbox.publish(DomainEvent.of("order.assignment.timed_out", "order", a.orderId(), payload));
      if (autoAssignEnabled) {
        tryAutoReassignAfterTimeout(a.orderId(), now);
      }
      count++;
    }
    return count;
  }

  void tryAutoReassignAfterTimeout(UUID orderId, Instant now) {
    Optional<OrderDetails> ready =
        orders
            .findOrder(orderId)
            .filter(o -> "READY_FOR_PICKUP".equals(o.status()))
            .filter(o -> o.riderId() == null);
    if (ready.isEmpty()) {
      return;
    }
    Optional<ScoredRider> best = pickBestRider(ready.get());
    if (best.isEmpty()) {
      return;
    }
    ScoredRider s = best.get();
    createAssignment(ready.get(), s.rider(), "AUTO", null, s.score(), now);
  }

  private Map<String, Object> recommendedRiderView(QueueOrder o) {
    Optional<OrderDetails> details = orders.findOrder(o.orderId());
    if (details.isEmpty()) {
      return null;
    }
    return pickBestRider(details.get())
        .map(
            s -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("rider_id", s.rider().id().toString());
              m.put("name", s.rider().name());
              m.put("vehicle_type", s.rider().vehicleType());
              m.put("avg_rating", s.rider().avgRating());
              m.put("distance_from_pharmacy_km", round1(s.distanceKm()));
              m.put("trips_today", s.tripsToday());
              m.put("concurrent_active_orders", s.concurrent());
              m.put("composite_score", s.score());
              return m;
            })
        .orElse(null);
  }

  private Optional<ScoredRider> pickBestRider(OrderDetails order) {
    List<FleetRiderRow> online =
        fleet.listFleet(new RiderFleetStore.FleetFilter(order.zoneId(), "ONLINE", 1, 200)).rows();
    return online.stream()
        .map(r -> riders.findById(r.riderId()))
        .flatMap(Optional::stream)
        .filter(r -> "ONLINE".equals(r.status()))
        .map(r -> scoreRider(r, order.pharmacyLat(), order.pharmacyLng()))
        .filter(s -> s.concurrent() < AssignmentScoring.MAX_CONCURRENT)
        .max(Comparator.comparing(ScoredRider::score));
  }

  private ScoredRider scoreRider(RiderRecord rider, Double pharmacyLat, Double pharmacyLng) {
    int concurrent = assignments.countActiveForRider(rider.id());
    double dist = distance.distanceKm(rider.id(), pharmacyLat, pharmacyLng);
    BigDecimal score =
        AssignmentScoring.composite(dist, rider.avgRating(), concurrent, rider.onTimePct());
    Instant dayStart = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant dayEnd = dayStart.plus(Duration.ofDays(1));
    int trips = fleet.countTripsToday(rider.id(), dayStart, dayEnd);
    return new ScoredRider(rider, score, dist, concurrent, trips);
  }

  private AssignmentRecord createAssignment(
      OrderDetails order,
      RiderRecord rider,
      String type,
      UUID assignedBy,
      BigDecimal score,
      Instant now) {
    if (assignments.hasActiveForOrder(order.orderId())) {
      throw new AppException(
          "ORDER_ALREADY_ASSIGNED", "Order already has an active assignment", 409);
    }
    String pickupOtp = AssignmentOtps.generate();
    String deliveryPlain =
        orders
            .peekDeliveryOtp(order.orderId())
            .orElseGet(() -> orders.ensureDeliveryOtp(order.orderId(), now));
    String pickupHash = AssignmentOtps.hash(pickupOtp);
    String deliveryHash = AssignmentOtps.hash(deliveryPlain);
    UUID id = Ids.newId();
    Instant deadline = now.plus(ACCEPT_WINDOW);
    AssignmentRecord row =
        new AssignmentRecord(
            id,
            order.orderId(),
            rider.id(),
            type,
            assignedBy,
            "PENDING_ACCEPTANCE",
            deadline,
            null,
            null,
            null,
            pickupHash,
            deliveryHash,
            null,
            score,
            now,
            now);
    assignments.insert(row);
    orders.assignRiderOnOrder(order.orderId(), rider.id(), now);
    otpCache.storePickupOtp(order.orderId(), pickupOtp);
    otpCache.storeDeliveryOtp(order.orderId(), deliveryPlain);
    otpCache.resetPickupAttempts(order.orderId());
    Map<String, Object> push = new LinkedHashMap<>();
    push.put("rider_id", rider.id().toString());
    push.put("order_id", order.orderId().toString());
    push.put("assignment_id", id.toString());
    push.put("template", "RIDER_ORDER_ASSIGNED");
    push.put("title", "New delivery assigned");
    push.put("body", "Order " + order.orderNumber() + " — accept within 5 minutes");
    push.put("recipient_type", "RIDER");
    push.put("recipient_ids", List.of(rider.id().toString()));
    push.put("accept_deadline", deadline.toString());
    outbox.publish(DomainEvent.of("rider.notification.order_assigned", "rider", rider.id(), push));
    return row;
  }

  private OrderDetails requireReadyUnassigned(UUID orderId) {
    OrderDetails order =
        orders
            .findOrder(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (!"READY_FOR_PICKUP".equals(order.status())) {
      throw new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404);
    }
    if (assignments.hasActiveForOrder(orderId) || order.riderId() != null) {
      throw new AppException(
          "ORDER_ALREADY_ASSIGNED", "Order already has an active assignment", 409);
    }
    return order;
  }

  private RiderRecord requireOnlineEligible(UUID riderId) {
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    if (!"ONLINE".equals(rider.status())) {
      throw new AppException("RIDER_NOT_ONLINE", "Rider is not in ONLINE status", 422);
    }
    if (assignments.countActiveForRider(riderId) >= AssignmentScoring.MAX_CONCURRENT) {
      throw new AppException(
          "RIDER_AT_MAX_LOAD", "Rider already has 2 concurrent active orders", 422);
    }
    return rider;
  }

  private Map<String, Object> assignmentResponse(
      AssignmentRecord a, String riderName, UUID assignedBy) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("assignment_id", a.id().toString());
    data.put("order_id", a.orderId().toString());
    data.put("rider_id", a.riderId().toString());
    data.put("rider_name", riderName);
    data.put("assignment_type", a.assignmentType());
    data.put("assigned_by", assignedBy.toString());
    data.put("assigned_at", a.createdAt().toString());
    data.put("accept_deadline", a.acceptDeadline().toString());
    return data;
  }

  private static AssignmentRecord withStatus(
      AssignmentRecord a, String status, String reason, Instant now) {
    return new AssignmentRecord(
        a.id(),
        a.orderId(),
        a.riderId(),
        a.assignmentType(),
        a.assignedBy(),
        status,
        a.acceptDeadline(),
        a.acceptedAt(),
        a.pickupConfirmedAt(),
        a.deliveredAt(),
        a.pickupOtpHash(),
        a.deliveryOtpHash(),
        reason,
        a.compositeScore(),
        a.createdAt(),
        now);
  }

  private void requireAdmin(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin operations required", 403);
    }
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  private static double round1(double v) {
    return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }

  private record ScoredRider(
      RiderRecord rider, BigDecimal score, double distanceKm, int concurrent, int tripsToday) {}
}
