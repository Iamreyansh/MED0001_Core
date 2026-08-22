package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.DeliveryInvoicePort;
import com.nammamedmate.order.application.port.out.DeliveryOtpCachePort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort.RefundPlan;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.application.port.out.RiderLookupPort.RiderInfo;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStateMachine;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.RejectReason;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleService {

  private static final int JOB_BATCH = 100;
  private static final String CANCEL_PHARMACY_TIMEOUT = "PHARMACY_ACCEPTANCE_TIMEOUT";

  private final OrderStore orders;
  private final OrderStatusEventStore statusEvents;
  private final RiderLookupPort riders;
  private final RefundInitiatorPort refunds;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final DeliveryOtpCachePort deliveryOtpCache;
  private final PasswordEncoder otpEncoder;
  private final SecureRandom random;
  private InventoryAvailabilityPort inventory = new InventoryAvailabilityPort() {};
  private PrescriptionPort prescriptions = new PrescriptionPort() {};
  private DeliveryInvoicePort deliveryInvoice = new DeliveryInvoicePort() {};

  @Autowired
  public OrderLifecycleService(
      OrderStore orders,
      OrderStatusEventStore statusEvents,
      RiderLookupPort riders,
      RefundInitiatorPort refunds,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock,
      DeliveryOtpCachePort deliveryOtpCache) {
    this(
        orders,
        statusEvents,
        riders,
        refunds,
        outbox,
        rateLimiter,
        clock,
        deliveryOtpCache,
        new BCryptPasswordEncoder(10),
        new SecureRandom());
  }

  OrderLifecycleService(
      OrderStore orders,
      OrderStatusEventStore statusEvents,
      RiderLookupPort riders,
      RefundInitiatorPort refunds,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock,
      DeliveryOtpCachePort deliveryOtpCache,
      PasswordEncoder otpEncoder,
      SecureRandom random) {
    this.orders = orders;
    this.statusEvents = statusEvents;
    this.riders = riders;
    this.refunds = refunds;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.deliveryOtpCache = deliveryOtpCache;
    this.otpEncoder = otpEncoder;
    this.random = random;
  }

  @Autowired(required = false)
  public void setInventory(InventoryAvailabilityPort inventory) {
    this.inventory = inventory == null ? new InventoryAvailabilityPort() {} : inventory;
  }

  @Autowired(required = false)
  public void setPrescriptions(PrescriptionPort prescriptions) {
    this.prescriptions = prescriptions == null ? new PrescriptionPort() {} : prescriptions;
  }

  @Autowired(required = false)
  public void setDeliveryInvoice(DeliveryInvoicePort deliveryInvoice) {
    this.deliveryInvoice = deliveryInvoice == null ? new DeliveryInvoicePort() {} : deliveryInvoice;
  }

  @Transactional
  public Map<String, Object> accept(MedmatePrincipal principal, UUID orderId) {
    requirePharmacy(principal);
    rateLimit("order:ph-accept:" + principal.subject(), 30, 60);
    Order order = requirePharmacyOrder(principal.pharmacyId(), orderId);
    Instant now = now();
    if (order.status() != OrderStatus.PENDING_ACCEPTANCE) {
      throw new AppException("ORDER_ALREADY_ACTIONED", "Order already accepted or rejected", 409);
    }
    if (order.isAcceptanceTimedOut(now)) {
      throw new AppException(
          "ORDER_ACCEPTANCE_TIMEOUT", "10-minute acceptance window elapsed", 409);
    }
    if (order.prescriptionId() != null) {
      String rxStatus =
          prescriptions
              .pharmacyQueueStatus(order.prescriptionId(), order.pharmacyId())
              .orElse("PENDING_REVIEW");
      if (!"APPROVED".equalsIgnoreCase(rxStatus) && !"VERIFIED".equalsIgnoreCase(rxStatus)) {
        throw new AppException(
            "RX_NOT_VERIFIED", "Prescription must be approved before fulfilment", 422);
      }
    }
    OrderStatus from = order.status();
    order.accept(now);
    orders.update(order);
    inventory.deductForOrder(order.id());
    appendEvent(
        order.id(), from, OrderStatus.ACCEPTED, ActorType.PHARMACY, principal.subject(), null, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("order_number", order.orderNumber());
    data.put("status", OrderStatus.ACCEPTED.name());
    data.put("accepted_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> advancePharmacyStatus(
      MedmatePrincipal principal, UUID orderId, String statusRaw, String notes) {
    requirePharmacy(principal);
    rateLimit("order:ph-status:" + principal.subject(), 20, 60);
    OrderStatus to = parseStatus(statusRaw);
    String trimmedNotes = trimNotes(notes, 300);
    if (!OrderStateMachine.isPharmacyAdvanceTarget(to)) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION", "Transition not allowed for pharmacy role", 422);
    }
    Order order = requirePharmacyOrder(principal.pharmacyId(), orderId);
    Instant now = now();
    OrderStatus from = order.status();
    if (!OrderStateMachine.isPharmacyAdvance(from, to)) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION", "Transition not allowed for pharmacy role", 422);
    }
    applyTransition(order, from, to, ActorType.PHARMACY, principal.subject(), trimmedNotes, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("status", order.status().name());
    data.put("updated_at", order.updatedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> reject(
      MedmatePrincipal principal, UUID orderId, String reasonRaw, String message) {
    requirePharmacy(principal);
    rateLimit("order:ph-reject:" + principal.subject(), 10, 60);
    RejectReason reason = parseRejectReason(reasonRaw);
    String msg = trimNotes(message, 300);
    Order order = requirePharmacyOrder(principal.pharmacyId(), orderId);
    Instant now = now();
    if (order.status() != OrderStatus.PENDING_ACCEPTANCE) {
      throw new AppException("ORDER_ALREADY_ACTIONED", "Order already accepted or rejected", 409);
    }
    String cancelReason = "PHARMACY_REJECT_" + reason.name();
    String notes = msg == null ? reason.name() : reason.name() + ": " + msg;
    RefundPlan plan =
        cancelWithRefund(order, cancelReason, notes, ActorType.PHARMACY, principal.subject(), now);
    Map<String, Object> wa = new LinkedHashMap<>();
    wa.put("order_id", order.id().toString());
    wa.put("customer_id", order.customerId().toString());
    wa.put("channel", "WHATSAPP");
    wa.put("template", "order_rejected");
    wa.put("reason", reason.name());
    if (msg != null) {
      wa.put("message", msg);
    }
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), wa));
    return rejectResponse(order, plan);
  }

  @Transactional
  public Map<String, Object> assignRider(MedmatePrincipal principal, UUID orderId, UUID riderId) {
    requirePharmacy(principal);
    rateLimit("order:ph-assign-rider:" + principal.subject(), 10, 60);
    if (riderId == null) {
      throw new AppException("VALIDATION_ERROR", "rider_id is required", 400);
    }
    Order order = requirePharmacyOrder(principal.pharmacyId(), orderId);
    if (order.status() != OrderStatus.READY_FOR_PICKUP
        && order.status() != OrderStatus.PACKING
        && order.status() != OrderStatus.ACCEPTED) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION", "Cannot assign rider in current status", 422);
    }
    RiderInfo rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("VALIDATION_ERROR", "rider not found", 404));
    Instant now = now();
    order.assignRider(riderId, now);
    orders.update(order);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("rider_id", rider.id().toString());
    data.put("rider_name", rider.name());
    data.put("rider_phone", rider.phone());
    data.put("rider_vehicle_plate", rider.vehiclePlate());
    data.put("assigned_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> adminForceStatus(
      MedmatePrincipal principal, UUID orderId, String statusRaw, String reason, String notes) {
    requireAdmin(principal);
    rateLimit("order:admin-status:" + principal.subject(), 10, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    OrderStatus to = parseStatus(statusRaw);
    Order order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    Instant now = now();
    OrderStatus from = order.status();
    if (!OrderStateMachine.isAdminForceAllowed(from, to)) {
      throw new AppException(
          "INVALID_STATUS_TRANSITION", "Admin cannot transition from terminal status", 422);
    }
    String combined =
        reason.trim() + (notes == null || notes.isBlank() ? "" : " | " + notes.trim());
    if (to == OrderStatus.CANCELLED) {
      cancelWithRefund(order, "ADMIN_FORCE", combined, ActorType.ADMIN, principal.subject(), now);
    } else {
      applyTransition(order, from, to, ActorType.ADMIN, principal.subject(), combined, now);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("status", order.status().name());
    data.put("advanced_by", principal.subject().toString());
    data.put("advanced_at", now.toString());
    data.put("reason", reason.trim());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> tracking(MedmatePrincipal principal, UUID orderId) {
    requireCustomer(principal);
    rateLimit("order:tracking:" + principal.subject(), 60, 60);
    Order order = requireCustomerOrder(principal.subject(), orderId);
    Instant now = now();
    List<OrderStatusEvent> events = statusEvents.listByOrderId(orderId);
    Map<String, Instant> stamps = timestampsByToStatus(events, order);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("order_number", order.orderNumber());
    data.put("status", order.status().name());
    data.put("current_step", currentStepLabel(order.status()));
    data.put("steps", trackingSteps(order, stamps));
    data.put("rider", riderView(order.riderId()));
    data.put("eta_minutes", order.etaMinutes(now));
    data.put("sla_remaining_minutes", order.slaRemainingMinutesClamped(now));
    data.put("sla_risk", order.slaRisk(now));
    data.put("last_updated_at", order.updatedAt().toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> timeline(MedmatePrincipal principal, UUID orderId) {
    requireCustomer(principal);
    rateLimit("order:timeline:" + principal.subject(), 30, 60);
    requireCustomerOrder(principal.subject(), orderId);
    List<OrderStatusEvent> events = statusEvents.listByOrderId(orderId);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (OrderStatusEvent e : events) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("status", e.toStatus().name());
      row.put("timestamp", e.createdAt().toString());
      row.put("actor", e.actorType().name().toLowerCase(Locale.ROOT));
      row.put("notes", e.notes());
      rows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", orderId.toString());
    data.put("events", rows);
    return data;
  }

  @Transactional
  public int cancelTimedOutAcceptances() {
    Instant cutoff = now().minus(Order.ACCEPTANCE_WINDOW);
    int count = 0;
    for (Order order : orders.findPendingAcceptanceTimedOut(cutoff, JOB_BATCH)) {
      Instant now = now();
      cancelWithRefund(
          order, CANCEL_PHARMACY_TIMEOUT, CANCEL_PHARMACY_TIMEOUT, ActorType.SYSTEM, null, now);
      count++;
    }
    return count;
  }

  @Transactional
  public int escalateMissingRiders() {
    Instant cutoff = now().minus(Order.RIDER_ASSIGN_ALERT);
    int count = 0;
    for (Order order : orders.findReadyWithoutRiderEscalation(cutoff, JOB_BATCH)) {
      Instant now = now();
      cancelWithRefund(
          order, "NO_RIDER_AVAILABLE", "NO_RIDER_AVAILABLE", ActorType.SYSTEM, null, now);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("order_id", order.id().toString());
      payload.put("order_number", order.orderNumber());
      payload.put("pharmacy_id", order.pharmacyId().toString());
      payload.put("alert", "NO_RIDER_ASSIGNED");
      payload.put("audience", "admin_operations");
      outbox.publish(DomainEvent.of("order.rider.escalation", "order", order.id(), payload));
      count++;
    }
    return count;
  }

  @Transactional
  public int markSlaBreaches() {
    Instant now = now();
    int count = 0;
    for (Order order : orders.findOpenPastSlaDeadline(now, JOB_BATCH)) {
      order.markSlaBreached(now);
      orders.update(order);
      count++;
    }
    return count;
  }

  private void applyTransition(
      Order order,
      OrderStatus from,
      OrderStatus to,
      ActorType actorType,
      UUID actorId,
      String notes,
      Instant now) {
    order.advanceTo(to, now);
    if (to == OrderStatus.READY_FOR_PICKUP) {
      String otp = String.format("%04d", random.nextInt(10_000));
      order.setDeliveryOtpHash(otpEncoder.encode(otp), now);
      publishOtp(order, otp);
    }
    orders.update(order);
    appendEvent(order.id(), from, to, actorType, actorId, notes, now);
    if (to == OrderStatus.DELIVERED) {
      deliveryInvoice.onDelivered(order);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("order_id", order.id().toString());
      payload.put("customer_id", order.customerId().toString());
      payload.put("item_total_paise", order.itemTotalPaise());
      payload.put("total_payable_paise", order.totalPayablePaise());
      payload.put("order_number", order.orderNumber());
      payload.put("delivered_at", now.toString());
      outbox.publish(DomainEvent.of("order.delivered", "order", order.id(), payload));
    }
  }

  private RefundPlan cancelWithRefund(
      Order order,
      String cancelReason,
      String notes,
      ActorType actorType,
      UUID actorId,
      Instant now) {
    OrderStatus from = order.status();
    order.cancel(cancelReason, now);
    orders.update(order);
    inventory.releaseForOrder(order.id());
    appendEvent(order.id(), from, OrderStatus.CANCELLED, actorType, actorId, notes, now);
    RefundPlan plan = refunds.initiate(order, cancelReason, actorType, actorId);
    publishCancelNotifications(order, cancelReason, plan);
    if (plan.initiated()) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("order_id", order.id().toString());
      payload.put("customer_id", order.customerId().toString());
      payload.put("amount_paise", plan.amountPaise());
      payload.put("refund_to", plan.refundTo());
      payload.put("reason", cancelReason);
      outbox.publish(DomainEvent.of("order.refund.requested", "order", order.id(), payload));
    }
    return plan;
  }

  private void publishCancelNotifications(Order order, String reason, RefundPlan plan) {
    Map<String, Object> push = new LinkedHashMap<>();
    push.put("order_id", order.id().toString());
    push.put("customer_id", order.customerId().toString());
    push.put("channel", "PUSH");
    push.put("template", "order_cancelled");
    push.put("reason", reason);
    push.put("refund_initiated", plan.initiated());
    if (plan.refundTo() != null) {
      push.put("refund_to", plan.refundTo());
    }
    push.put("title", "Order cancelled");
    push.put(
        "body",
        "Order "
            + order.orderNumber()
            + " was cancelled. Reason: "
            + reason
            + (plan.initiated() ? ". Refund in progress." : "."));
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), push));
    Map<String, Object> wa = new LinkedHashMap<>(push);
    wa.put("channel", "WHATSAPP");
    wa.remove("title");
    wa.remove("body");
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), wa));
  }

  private Map<String, Object> rejectResponse(Order order, RefundPlan plan) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("status", OrderStatus.CANCELLED.name());
    data.put("refund_initiated", plan.initiated());
    data.put("refund_amount", plan.initiated() ? CartPricing.paiseToRupees(plan.amountPaise()) : 0);
    data.put("refund_to", plan.refundTo());
    return data;
  }

  private void publishOtp(Order order, String otp) {
    deliveryOtpCache.store(order.id(), otp);
    Map<String, Object> sms = new LinkedHashMap<>();
    sms.put("order_id", order.id().toString());
    sms.put("customer_id", order.customerId().toString());
    sms.put("channel", "SMS");
    sms.put("template", "delivery_otp");
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), sms));
    Map<String, Object> push = new LinkedHashMap<>();
    push.put("order_id", order.id().toString());
    push.put("customer_id", order.customerId().toString());
    push.put("channel", "PUSH");
    push.put("template", "delivery_otp");
    push.put("title", "Delivery OTP");
    push.put("body", "Your delivery OTP was sent via SMS");
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), push));
  }

  private void appendEvent(
      UUID orderId,
      OrderStatus from,
      OrderStatus to,
      ActorType actorType,
      UUID actorId,
      String notes,
      Instant at) {
    statusEvents.append(
        new OrderStatusEvent(UUID.randomUUID(), orderId, from, to, actorType, actorId, notes, at));
  }

  private List<Map<String, Object>> trackingSteps(Order order, Map<String, Instant> stamps) {
    List<StepDef> defs =
        List.of(
            new StepDef("Order Confirmed", OrderStatus.PENDING_ACCEPTANCE),
            new StepDef("Accepted by Pharmacy", OrderStatus.ACCEPTED),
            new StepDef("Being Packed", OrderStatus.PACKING),
            new StepDef("Ready for Pickup", OrderStatus.READY_FOR_PICKUP),
            new StepDef("Out for Delivery", OrderStatus.OUT_FOR_DELIVERY),
            new StepDef("Delivered", OrderStatus.DELIVERED));
    int currentIdx = stepIndex(order.status());
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int i = 0; i < defs.size(); i++) {
      StepDef def = defs.get(i);
      boolean completed =
          order.status() == OrderStatus.CANCELLED
              ? stamps.containsKey(def.status().name())
              : currentIdx >= i;
      Instant ts = stamps.get(def.status().name());
      if (ts == null && def.status() == OrderStatus.PENDING_ACCEPTANCE) {
        ts = order.confirmedAt();
      }
      if (ts == null && def.status() == OrderStatus.ACCEPTED) {
        ts = order.acceptedAt();
      }
      if (ts == null && def.status() == OrderStatus.READY_FOR_PICKUP) {
        ts = order.readyForPickupAt();
      }
      if (ts == null && def.status() == OrderStatus.DELIVERED) {
        ts = order.deliveredAt();
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", def.name());
      row.put("completed", completed);
      row.put("timestamp", ts == null ? null : ts.toString());
      steps.add(row);
    }
    return steps;
  }

  private static int stepIndex(OrderStatus status) {
    return switch (status) {
      case PAYMENT_PENDING -> -1;
      case PENDING_ACCEPTANCE -> 0;
      case ACCEPTED -> 1;
      case PACKING -> 2;
      case READY_FOR_PICKUP -> 3;
      case OUT_FOR_DELIVERY -> 4;
      case DELIVERED -> 5;
      case CANCELLED -> -1;
    };
  }

  private static String currentStepLabel(OrderStatus status) {
    return switch (status) {
      case PAYMENT_PENDING -> "Awaiting payment";
      case PENDING_ACCEPTANCE -> "Waiting for pharmacy";
      case ACCEPTED -> "Accepted by pharmacy";
      case PACKING -> "Being packed";
      case READY_FOR_PICKUP -> "Ready for pickup";
      case OUT_FOR_DELIVERY -> "Rider on the way";
      case DELIVERED -> "Delivered";
      case CANCELLED -> "Cancelled";
    };
  }

  private Map<String, Instant> timestampsByToStatus(List<OrderStatusEvent> events, Order order) {
    Map<String, Instant> map = new LinkedHashMap<>();
    if (order.confirmedAt() != null) {
      map.put(OrderStatus.PENDING_ACCEPTANCE.name(), order.confirmedAt());
    }
    for (OrderStatusEvent e : events) {
      map.put(e.toStatus().name(), e.createdAt());
    }
    return map;
  }

  private Map<String, Object> riderView(UUID riderId) {
    if (riderId == null) {
      return null;
    }
    return riders
        .findById(riderId)
        .map(
            r -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("name", r.name());
              m.put("avatar", r.avatarUrl());
              m.put("vehicle_plate", r.vehiclePlate());
              m.put("phone", r.phone());
              return m;
            })
        .orElse(null);
  }

  private Order requirePharmacyOrder(UUID pharmacyId, UUID orderId) {
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    return orders
        .findByPharmacyAndId(pharmacyId, orderId)
        .orElseThrow(
            () -> new AppException("ORDER_NOT_FOUND", "Order not in this pharmacy's queue", 404));
  }

  private Order requireCustomerOrder(UUID customerId, UUID orderId) {
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    return orders
        .findByCustomerAndId(customerId, orderId)
        .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
  }

  private static OrderStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "status is required", 400);
    }
    try {
      return OrderStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid status", 400);
    }
  }

  private static RejectReason parseRejectReason(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    try {
      return RejectReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid reject reason", 400);
    }
  }

  private static String trimNotes(String notes, int max) {
    if (notes == null || notes.isBlank()) {
      return null;
    }
    String t = notes.trim();
    if (t.length() > max) {
      throw new AppException("VALIDATION_ERROR", "notes/message max " + max + " characters", 400);
    }
    return t;
  }

  private void requirePharmacy(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.PHARMACY_OWNER
            && principal.role() != AuthRole.PHARMACY_STAFF)
        || principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy authentication required", 401);
    }
  }

  private void requireAdmin(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.ADMIN_SUPER
            && principal.role() != AuthRole.ADMIN_OPERATIONS)) {
      throw new AppException("UNAUTHORIZED", "Admin authentication required", 401);
    }
  }

  private void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private Instant now() {
    return clock.instant();
  }

  private record StepDef(String name, OrderStatus status) {}
}
