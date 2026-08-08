package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.rider.domain.BasePayFormula;
import com.nammamedmate.rider.domain.IncentiveRules;
import com.nammamedmate.rider.domain.PayoutCycle;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderOrderService {

  /** Legacy constant; deliver uses {@link BasePayFormula}. */
  static final long BASE_PAY_PAISE = 2500L;

  static final long TIP_PAISE_STUB = 0L;

  private final OrderAssignmentStore assignments;
  private final DispatchOrderPort orders;
  private final AssignmentOtpCachePort otpCache;
  private final DistanceMatrixPort distance;
  private final RiderTripEarningsStore earnings;
  private final CodReconciliationService cod;
  private final RiderStore riders;
  private final DeliveryZoneStore zones;
  private final PlatformPricingConfigStore pricingConfig;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public RiderOrderService(
      OrderAssignmentStore assignments,
      DispatchOrderPort orders,
      AssignmentOtpCachePort otpCache,
      DistanceMatrixPort distance,
      RiderTripEarningsStore earnings,
      OutboxPublisher outbox,
      Clock clock) {
    this(assignments, orders, otpCache, distance, earnings, null, null, null, null, outbox, clock);
  }

  public RiderOrderService(
      OrderAssignmentStore assignments,
      DispatchOrderPort orders,
      AssignmentOtpCachePort otpCache,
      DistanceMatrixPort distance,
      RiderTripEarningsStore earnings,
      CodReconciliationService cod,
      OutboxPublisher outbox,
      Clock clock) {
    this(assignments, orders, otpCache, distance, earnings, cod, null, null, null, outbox, clock);
  }

  @Autowired
  public RiderOrderService(
      OrderAssignmentStore assignments,
      DispatchOrderPort orders,
      AssignmentOtpCachePort otpCache,
      DistanceMatrixPort distance,
      RiderTripEarningsStore earnings,
      CodReconciliationService cod,
      RiderStore riders,
      DeliveryZoneStore zones,
      PlatformPricingConfigStore pricingConfig,
      OutboxPublisher outbox,
      Clock clock) {
    this.assignments = assignments;
    this.orders = orders;
    this.otpCache = otpCache;
    this.distance = distance;
    this.earnings = earnings;
    this.cod = cod;
    this.riders = riders;
    this.zones = zones;
    this.pricingConfig = pricingConfig;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> current(MedmatePrincipal principal) {
    requireRider(principal);
    UUID riderId = principal.subject();
    AssignmentRecord a =
        assignments
            .findCurrentForRider(riderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "No active order", 404));
    OrderDetails order =
        orders
            .findOrder(a.orderId())
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    Map<String, Object> pharmacy = new LinkedHashMap<>();
    pharmacy.put("name", order.pharmacyName());
    pharmacy.put("address", order.pharmacyAddress());
    pharmacy.put("lat", order.pharmacyLat());
    pharmacy.put("lng", order.pharmacyLng());
    pharmacy.put("pharmacy_contact", order.pharmacyPhone());
    // Customer PII only after accept (pending shows pharmacy only).
    Map<String, Object> delivery = new LinkedHashMap<>();
    if (!"PENDING_ACCEPTANCE".equals(a.status())) {
      delivery.put("customer_name", order.customerName());
      delivery.put("address", order.deliveryAddress());
      delivery.put("lat", order.deliveryLat());
      delivery.put("lng", order.deliveryLng());
      delivery.put("customer_contact", order.customerPhone());
    }
    boolean cod = "COD".equalsIgnoreCase(order.paymentMethod());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.orderId().toString());
    data.put("order_number", order.orderNumber());
    data.put("assignment_id", a.id().toString());
    data.put("assignment_status", a.status());
    data.put("pharmacy", pharmacy);
    data.put("delivery", delivery);
    // Pickup OTP is pharmacy-screen only — never return to rider (BR-006/007).
    data.put("delivery_otp_hint", "Ask customer for 4-digit OTP");
    data.put("items_count", order.itemsCount());
    data.put("payment_method", order.paymentMethod());
    data.put("is_cod", cod);
    data.put("cod_amount", cod ? paiseToRupees(order.totalPayablePaise()) : null);
    data.put(
        "distance_km",
        round1(distance.distanceKm(riderId, order.pharmacyLat(), order.pharmacyLng())));
    data.put("base_pay", paiseToRupees(BASE_PAY_PAISE));
    return data;
  }

  @Transactional
  public Map<String, Object> accept(MedmatePrincipal principal, UUID orderId) {
    requireRider(principal);
    UUID riderId = principal.subject();
    AssignmentRecord a = requireAssignmentForRider(orderId, riderId);
    Instant now = clock.instant();
    if (!"PENDING_ACCEPTANCE".equals(a.status())) {
      throw new AppException("ALREADY_ACCEPTED", "Order already accepted", 409);
    }
    if (!now.isBefore(a.acceptDeadline())) {
      throw new AppException("ASSIGNMENT_EXPIRED", "5-minute acceptance window has elapsed", 410);
    }
    OrderDetails pendingOrder =
        orders
            .findOrder(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (cod != null && "COD".equalsIgnoreCase(pendingOrder.paymentMethod())) {
      cod.assertCanAcceptCod(riderId);
    }
    AssignmentRecord updated =
        new AssignmentRecord(
            a.id(),
            a.orderId(),
            a.riderId(),
            a.assignmentType(),
            a.assignedBy(),
            "ACCEPTED",
            a.acceptDeadline(),
            now,
            null,
            null,
            a.pickupOtpHash(),
            a.deliveryOtpHash(),
            a.reassignReason(),
            a.compositeScore(),
            a.createdAt(),
            now);
    assignments.update(updated);
    otpCache.incrConcurrent(riderId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", orderId.toString());
    data.put("assignment_id", a.id().toString());
    data.put("assignment_status", "ACCEPTED");
    data.put("accepted_at", now.toString());
    data.put("pharmacy_address", pendingOrder.pharmacyAddress());
    data.put("pharmacy_lat", pendingOrder.pharmacyLat());
    data.put("pharmacy_lng", pendingOrder.pharmacyLng());
    return data;
  }

  @Transactional
  public Map<String, Object> pickupConfirm(
      MedmatePrincipal principal, UUID orderId, String pickupOtp) {
    requireRider(principal);
    UUID riderId = principal.subject();
    AssignmentRecord a = requireAssignmentForRider(orderId, riderId);
    if (!"ACCEPTED".equals(a.status())) {
      throw new AppException(
          "ORDER_NOT_IN_READY_STATE", "Order not yet in READY_FOR_PICKUP status", 409);
    }
    OrderDetails order =
        orders
            .findOrder(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (!"READY_FOR_PICKUP".equals(order.status())) {
      throw new AppException(
          "ORDER_NOT_IN_READY_STATE", "Order not yet in READY_FOR_PICKUP status", 409);
    }
    boolean pickupOk =
        pickupOtp != null
            && !pickupOtp.isBlank()
            && AssignmentOtps.matches(pickupOtp, a.pickupOtpHash());
    if (!pickupOk) {
      int remaining = otpCache.consumePickupAttempt(orderId);
      throw new AppException(
          "INVALID_PICKUP_OTP",
          "OTP does not match",
          422,
          null,
          Map.of("remaining_attempts", remaining));
    }
    Instant now = clock.instant();
    AssignmentRecord updated =
        new AssignmentRecord(
            a.id(),
            a.orderId(),
            a.riderId(),
            a.assignmentType(),
            a.assignedBy(),
            "PICKED_UP",
            a.acceptDeadline(),
            a.acceptedAt(),
            now,
            null,
            a.pickupOtpHash(),
            a.deliveryOtpHash(),
            a.reassignReason(),
            a.compositeScore(),
            a.createdAt(),
            now);
    assignments.update(updated);
    orders.advanceStatus(
        orderId, "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "RIDER", riderId, "pickup_otp", now);
    // Customer delivery OTP already issued at READY_FOR_PICKUP; nudge via outbox (no OTP in
    // payload).
    Map<String, Object> sms = new LinkedHashMap<>();
    sms.put("order_id", orderId.toString());
    sms.put("customer_id", "unknown");
    sms.put("channel", "SMS");
    sms.put("template", "delivery_otp");
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", orderId, sms));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", orderId.toString());
    data.put("order_status", "OUT_FOR_DELIVERY");
    data.put("pickup_confirmed_at", now.toString());
    data.put("delivery_address", order.deliveryAddress());
    data.put("delivery_lat", order.deliveryLat());
    data.put("delivery_lng", order.deliveryLng());
    data.put("customer_name", order.customerName());
    data.put("customer_contact", order.customerPhone());
    return data;
  }

  @Transactional
  public Map<String, Object> deliver(MedmatePrincipal principal, UUID orderId, String deliveryOtp) {
    requireRider(principal);
    UUID riderId = principal.subject();
    AssignmentRecord a = requireAssignmentForRider(orderId, riderId);
    if (!"PICKED_UP".equals(a.status())) {
      throw new AppException(
          "ORDER_NOT_OUT_FOR_DELIVERY", "Order not in OUT_FOR_DELIVERY state", 409);
    }
    OrderDetails order =
        orders
            .findOrder(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (!"OUT_FOR_DELIVERY".equals(order.status())) {
      throw new AppException(
          "ORDER_NOT_OUT_FOR_DELIVERY", "Order not in OUT_FOR_DELIVERY state", 409);
    }
    if (deliveryOtp == null) {
      throw new AppException("INVALID_DELIVERY_OTP", "OTP from customer does not match", 422);
    }
    boolean ok =
        AssignmentOtps.matches(deliveryOtp, a.deliveryOtpHash())
            || otpCache.getDeliveryOtp(orderId).map(deliveryOtp::equals).orElse(false)
            || orders.verifyDeliveryOtp(orderId, deliveryOtp);
    if (!ok) {
      throw new AppException("INVALID_DELIVERY_OTP", "OTP from customer does not match", 422);
    }
    Instant now = clock.instant();
    AssignmentRecord updated =
        new AssignmentRecord(
            a.id(),
            a.orderId(),
            a.riderId(),
            a.assignmentType(),
            a.assignedBy(),
            "DELIVERED",
            a.acceptDeadline(),
            a.acceptedAt(),
            a.pickupConfirmedAt(),
            now,
            a.pickupOtpHash(),
            a.deliveryOtpHash(),
            a.reassignReason(),
            a.compositeScore(),
            a.createdAt(),
            now);
    assignments.update(updated);
    orders.advanceStatus(
        orderId, "OUT_FOR_DELIVERY", "DELIVERED", "RIDER", riderId, "delivery_otp", now);
    otpCache.decrConcurrent(riderId);
    otpCache.evict(orderId);
    int deliveryMinutes =
        a.acceptedAt() == null
            ? 0
            : (int) Math.max(0, Duration.between(a.acceptedAt(), now).toMinutes());
    BigDecimal distanceKm = resolveDistanceKm(order);
    boolean onTime = isOnTime(order, a, now, deliveryMinutes);
    long basePay = BasePayFormula.computePaise(distanceKm, pricingConfig);
    long tip = TIP_PAISE_STUB;
    long incentive = IncentiveRules.tripIncentiveBonusPaise();
    long total = basePay + tip + incentive;
    LocalDate deliveryDate = PayoutCycle.istDate(now);
    earnings.insert(
        new EarningsRecord(
            Ids.newId(),
            riderId,
            orderId,
            a.id(),
            deliveryDate,
            basePay,
            tip,
            incentive,
            total,
            onTime,
            null,
            distanceKm,
            deliveryMinutes,
            now));
    if (riders != null) {
      riders.adjustEarningsWallet(riderId, total, now);
      applyStreak(riderId, deliveryDate, now);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", orderId.toString());
    data.put("order_status", "DELIVERED");
    data.put("delivered_at", now.toString());
    data.put("delivery_minutes", deliveryMinutes);
    data.put("on_time", onTime);
    data.put("base_pay_earned", paiseToRupees(basePay));
    data.put("tip_earned", paiseToRupees(tip));
    data.put("total_earned_this_trip", paiseToRupees(total));
    return data;
  }

  private BigDecimal resolveDistanceKm(OrderDetails order) {
    Double pLat = order.pharmacyLat();
    Double pLng = order.pharmacyLng();
    Double dLat = order.deliveryLat();
    Double dLng = order.deliveryLng();
    if (pLat == null || pLng == null || dLat == null || dLng == null) {
      return BigDecimal.valueOf(distance.distanceKm(order.orderId(), pLat, pLng))
          .setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(distance.estimateDriving(pLat, pLng, dLat, dLng).distanceKm())
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** BR-003: on_time if delivered within zone.sla_minutes of accepted_at. */
  private boolean isOnTime(
      OrderDetails order, AssignmentRecord a, Instant now, int deliveryMinutes) {
    if (a.acceptedAt() != null && zones != null && order.zoneId() != null) {
      Integer sla = zones.findById(order.zoneId()).map(z -> z.slaMinutes()).orElse(null);
      if (sla != null) {
        return deliveryMinutes <= sla;
      }
    }
    Instant deadline =
        order.estimatedDeliveryAt() != null ? order.estimatedDeliveryAt() : order.slaDeadline();
    return deadline == null || !now.isAfter(deadline);
  }

  private void applyStreak(UUID riderId, LocalDate deliveryDate, Instant now) {
    var rider = riders.findById(riderId).orElse(null);
    if (rider == null) {
      return;
    }
    LocalDate last = riders.lastDeliveryDate(riderId).orElse(null);
    int streak;
    if (last == null) {
      streak = 1;
    } else if (last.equals(deliveryDate)) {
      streak = rider.dailyStreakDays();
    } else if (last.plusDays(1).equals(deliveryDate)) {
      streak = rider.dailyStreakDays() + 1;
    } else {
      streak = 1;
    }
    int required = IncentiveRules.streakDaysRequired(pricingConfig);
    boolean bonusPending = streak >= required;
    riders.updateStreak(riderId, streak, deliveryDate, bonusPending, now);
  }

  private AssignmentRecord requireAssignmentForRider(UUID orderId, UUID riderId) {
    AssignmentRecord a =
        assignments
            .findActiveByOrder(orderId)
            .or(
                () ->
                    assignments
                        .findCurrentForRider(riderId)
                        .filter(x -> x.orderId().equals(orderId)))
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "order_id does not exist", 404));
    if (!a.riderId().equals(riderId)) {
      throw new AppException("NOT_YOUR_ORDER", "Order assigned to a different rider", 403);
    }
    return a;
  }

  private void requireRider(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  private static double round1(double v) {
    return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }
}
