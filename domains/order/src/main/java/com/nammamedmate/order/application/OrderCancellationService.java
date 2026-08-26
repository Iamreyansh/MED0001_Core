package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort.RefundPlan;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.CustomerCancelReason;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundTo;
import com.nammamedmate.order.domain.RxQuotePricing;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCancellationService {

  private final OrderStore orders;
  private final OrderStatusEventStore statusEvents;
  private final RefundService refunds;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private InventoryAvailabilityPort inventory = new InventoryAvailabilityPort() {};

  public OrderCancellationService(
      OrderStore orders,
      OrderStatusEventStore statusEvents,
      RefundService refunds,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock) {
    this.orders = orders;
    this.statusEvents = statusEvents;
    this.refunds = refunds;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setInventory(InventoryAvailabilityPort inventory) {
    this.inventory = inventory == null ? new InventoryAvailabilityPort() {} : inventory;
  }

  @Transactional
  public Map<String, Object> customerCancel(
      MedmatePrincipal principal, UUID orderId, String reasonRaw) {
    requireCustomer(principal);
    rateLimit("order:cancel:" + principal.subject(), 5, 60);
    CustomerCancelReason reason = parseCustomerReason(reasonRaw);
    Order order = requireCustomerOrder(principal.subject(), orderId);
    Instant now = clock.instant();
    if (order.status() == OrderStatus.CANCELLED) {
      throw new AppException("ORDER_ALREADY_CANCELLED", "Order is already cancelled", 409);
    }
    if (order.status() != OrderStatus.PENDING_ACCEPTANCE
        && order.status() != OrderStatus.ACCEPTED) {
      throw new AppException(
          "ORDER_CANNOT_CANCEL", "Customer can cancel only before packing starts", 409);
    }
    OrderStatus from = order.status();
    order.cancel(reason.name(), now);
    orders.update(order);
    inventory.releaseForOrder(order.id());
    appendEvent(order.id(), from, ActorType.CUSTOMER, principal.subject(), reason.name(), now);
    // initiate persists cancellation + auto refund; skip double-cancel record path via actor
    RefundPlan plan =
        refunds.initiate(order, reason.name(), ActorType.CUSTOMER, principal.subject());
    publishCancelNotifications(order, reason.name(), plan);
    if (plan.initiated()) {
      publishRefundRequested(order, plan, reason.name());
    }
    return customerCancelView(order, plan, now);
  }

  @Transactional
  public Map<String, Object> adminCancel(
      MedmatePrincipal principal,
      UUID orderId,
      String reason,
      Object refundAmount,
      String refundToRaw) {
    requireCancelAdmin(principal);
    rateLimit("order:admin-cancel:" + principal.subject(), 10, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    String trimmedReason = reason.trim();
    if (trimmedReason.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "reason max 300 characters", 400);
    }
    RefundTo refundTo = RefundService.parseRefundTo(refundToRaw);
    long amountPaise = parseAmountPaise(refundAmount);
    Order order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    Instant now = clock.instant();
    if (order.status() == OrderStatus.DELIVERED) {
      throw new AppException("ORDER_DELIVERED", "Cannot cancel a delivered order", 409);
    }
    if (order.status() == OrderStatus.CANCELLED) {
      throw new AppException("ORDER_ALREADY_CANCELLED", "Order is already cancelled", 409);
    }
    if (amountPaise > order.totalPayablePaise()) {
      throw new AppException(
          "REFUND_EXCEEDS_ORDER_TOTAL", "refund_amount exceeds order total payable", 422);
    }
    OrderStatus from = order.status();
    order.cancel(trimmedReason, now);
    orders.update(order);
    inventory.releaseForOrder(order.id());
    appendEvent(order.id(), from, ActorType.ADMIN, principal.subject(), trimmedReason, now);
    refunds.persistCancellation(
        order, trimmedReason, CancelledByType.ADMIN, principal.subject(), now);

    Refund refund = null;
    boolean codNoRefund =
        order.paymentMethod() == PaymentMethod.COD
            && order.paymentStatus() != PaymentStatus.COLLECTED;
    if (!codNoRefund && amountPaise > 0) {
      refund =
          refunds.issueOnAdminCancel(
              order, amountPaise, refundTo, trimmedReason, principal.subject(), now);
    }
    if (order.walletAppliedPaise() > 0) {
      refunds.reverseWalletApplied(order, trimmedReason, principal.subject(), now);
    }

    RefundPlan plan =
        refund == null
            ? new RefundPlan(false, 0L, null)
            : new RefundPlan(true, refund.amountPaise(), refund.refundTo().name());
    publishCancelNotifications(order, trimmedReason, plan);
    if (plan.initiated()) {
      publishRefundRequested(order, plan, trimmedReason);
    }
    return adminCancelView(order, principal.subject(), refund, plan, now);
  }

  @Transactional
  public Map<String, Object> adminRefund(
      MedmatePrincipal principal,
      UUID orderId,
      Object amount,
      String refundToRaw,
      String reason,
      String notes,
      String idempotencyKey) {
    requireRefundAdmin(principal);
    rateLimit("order:admin-refund:" + principal.subject(), 10, 60);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    String trimmedReason = reason.trim();
    if (trimmedReason.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "reason max 300 characters", 400);
    }
    if (notes != null && notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 characters", 400);
    }
    RefundTo refundTo = RefundService.parseRefundTo(refundToRaw);
    long amountPaise = parseAmountPaise(amount);
    Order order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    Refund refund =
        refunds.issueManual(
            order,
            amountPaise,
            refundTo,
            trimmedReason,
            notes,
            principal.subject(),
            idempotencyKey);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refund_id", refund.id().toString());
    data.put("order_id", order.id().toString());
    data.put("amount", CartPricing.paiseToRupees(refund.amountPaise()));
    data.put("refund_to", refund.refundTo().name());
    data.put("status", refund.status().name());
    data.put("processed_at", refund.processedAt() == null ? null : refund.processedAt().toString());
    data.put("issued_by", principal.subject().toString());
    data.put("gateway_refund_id", refund.gatewayRefundId());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> refundEligibility(MedmatePrincipal principal, UUID orderId) {
    requireRefundAdmin(principal);
    rateLimit("order:refund-elig:" + principal.subject(), 30, 60);
    Order order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    long already = refunds.alreadyRefundedPaise(order.id());
    long max = refunds.remainingRefundablePaise(order);
    RefundTo recommended = refunds.recommendRefundTo(order);
    boolean cancelEligible =
        order.status() != OrderStatus.DELIVERED && order.status() != OrderStatus.CANCELLED;
    Map<String, Object> recommendation = new LinkedHashMap<>();
    recommendation.put("refund_to", recommended.name());
    recommendation.put("message", recommendationMessage(order, recommended));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("order_total", CartPricing.paiseToRupees(order.totalPayablePaise()));
    data.put("already_refunded", CartPricing.paiseToRupees(already));
    data.put("max_refundable", CartPricing.paiseToRupees(max));
    data.put("eligible", max > 0);
    data.put("payment_method", order.paymentMethod().name());
    data.put("original_payment_status", order.paymentStatus().name());
    data.put("recommendation", recommendation);
    data.put("cancellation_eligible", cancelEligible);
    data.put("cancellation_reason", cancellationReasonMessage(order));
    return data;
  }

  private Map<String, Object> customerCancelView(Order order, RefundPlan plan, Instant now) {
    Map<String, Object> refund = new LinkedHashMap<>();
    refund.put("initiated", plan.initiated());
    if (plan.initiated()) {
      refund.put("amount", CartPricing.paiseToRupees(plan.amountPaise()));
      refund.put("refund_to", plan.refundTo());
      boolean source = "SOURCE".equals(plan.refundTo());
      refund.put("estimated_days", source ? 5 : 0);
      refund.put(
          "message",
          source
              ? "Refund of Rs "
                  + CartPricing.paiseToRupees(plan.amountPaise())
                  + " will be credited to your original payment method in 3-5 business days."
              : "Refund of Rs "
                  + CartPricing.paiseToRupees(plan.amountPaise())
                  + " has been credited to your Namma Money wallet.");
    } else {
      refund.put("amount", 0);
      refund.put("refund_to", null);
      refund.put("estimated_days", 0);
      refund.put("message", "No refund due for this cancellation.");
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("order_number", order.orderNumber());
    data.put("status", OrderStatus.CANCELLED.name());
    data.put("cancelled_by", "customer");
    data.put("cancelled_at", now.toString());
    data.put("refund", refund);
    return data;
  }

  private Map<String, Object> adminCancelView(
      Order order, UUID adminId, Refund refund, RefundPlan plan, Instant now) {
    Map<String, Object> refundView = new LinkedHashMap<>();
    if (refund != null) {
      refundView.put("refund_id", refund.id().toString());
      refundView.put("amount", CartPricing.paiseToRupees(refund.amountPaise()));
      refundView.put("refund_to", refund.refundTo().name());
      refundView.put("status", refund.status().name());
      refundView.put("initiated", true);
    } else {
      refundView.put("initiated", false);
      refundView.put("amount", 0);
      refundView.put("refund_to", null);
      refundView.put("status", null);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id().toString());
    data.put("status", OrderStatus.CANCELLED.name());
    data.put("cancelled_by", adminId.toString());
    data.put("cancelled_at", now.toString());
    data.put("refund", refundView);
    return data;
  }

  void publishCancelNotifications(Order order, String reason, RefundPlan plan) {
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

  private void publishRefundRequested(Order order, RefundPlan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("order_id", order.id().toString());
    payload.put("customer_id", order.customerId().toString());
    payload.put("amount_paise", plan.amountPaise());
    payload.put("refund_to", plan.refundTo());
    payload.put("reason", reason);
    outbox.publish(DomainEvent.of("order.refund.requested", "order", order.id(), payload));
  }

  private void appendEvent(
      UUID orderId, OrderStatus from, ActorType actorType, UUID actorId, String notes, Instant at) {
    statusEvents.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            orderId,
            from,
            OrderStatus.CANCELLED,
            actorType,
            actorId,
            notes,
            at));
  }

  private Order requireCustomerOrder(UUID customerId, UUID orderId) {
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    return orders
        .findByCustomerAndId(customerId, orderId)
        .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
  }

  private static CustomerCancelReason parseCustomerReason(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    try {
      return CustomerCancelReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid cancel reason", 400);
    }
  }

  private static long parseAmountPaise(Object amount) {
    if (amount == null) {
      throw new AppException("VALIDATION_ERROR", "amount is required", 400);
    }
    try {
      return RxQuotePricing.rupeesToPaise(amount);
    } catch (RuntimeException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid amount", 400);
    }
  }

  private static String recommendationMessage(Order order, RefundTo to) {
    if (to == RefundTo.SOURCE) {
      return "Order was paid via "
          + order.paymentMethod().name()
          + ". Refund to source recommended (3-5 business days via Cashfree).";
    }
    if (order.paymentMethod() == PaymentMethod.COD) {
      return "COD order — any refund credits Namma Money wallet.";
    }
    return "Order was paid via wallet. Refund to Namma Money wallet recommended (instant).";
  }

  private static String cancellationReasonMessage(Order order) {
    if (order.status() == OrderStatus.DELIVERED) {
      return "Order is DELIVERED — cancellation not allowed; refund only";
    }
    if (order.status() == OrderStatus.CANCELLED) {
      return "Order is already CANCELLED";
    }
    if (order.status() == OrderStatus.PENDING_ACCEPTANCE
        || order.status() == OrderStatus.ACCEPTED) {
      return "Order is in "
          + order.status().name()
          + " status - eligible for customer or admin cancellation";
    }
    return "Order is in " + order.status().name() + " status - admin cancellation only";
  }

  private void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  private void requireCancelAdmin(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.ADMIN_SUPER
            && principal.role() != AuthRole.ADMIN_OPERATIONS)) {
      throw new AppException("UNAUTHORIZED", "Admin authentication required", 401);
    }
  }

  private void requireRefundAdmin(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.ADMIN_SUPER
            && principal.role() != AuthRole.ADMIN_OPERATIONS
            && principal.role() != AuthRole.ADMIN_FINANCE)) {
      throw new AppException("UNAUTHORIZED", "Admin authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
