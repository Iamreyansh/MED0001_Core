package com.nammamedmate.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.order.application.port.out.CashfreePaymentPort;
import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderCancellation;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService implements RefundInitiatorPort {

  /** EPIC-012 STORY-005 BR-002: auto-process ≤ ₹500 for pharmacy/system cancels. */
  public static final long AUTO_REFUND_MAX_PAISE = 50_000L;

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final RefundStore refunds;
  private final OrderCancellationStore cancellations;
  private final OrderStore orders;
  private final CashfreePaymentPort cashfree;
  private final WalletPort wallet;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ProviderOperationStore providerOps;

  public RefundService(
      RefundStore refunds,
      OrderCancellationStore cancellations,
      OrderStore orders,
      CashfreePaymentPort cashfree,
      WalletPort wallet,
      Clock clock) {
    this(refunds, cancellations, orders, cashfree, wallet, clock, null, null);
  }

  public RefundService(
      RefundStore refunds,
      OrderCancellationStore cancellations,
      OrderStore orders,
      CashfreePaymentPort cashfree,
      WalletPort wallet,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager) {
    this(refunds, cancellations, orders, cashfree, wallet, clock, transactionManager, null);
  }

  @Autowired
  public RefundService(
      RefundStore refunds,
      OrderCancellationStore cancellations,
      OrderStore orders,
      CashfreePaymentPort cashfree,
      WalletPort wallet,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager,
      @Nullable ProviderOperationStore providerOps) {
    this.refunds = refunds;
    this.cancellations = cancellations;
    this.orders = orders;
    this.cashfree = cashfree;
    this.wallet = wallet;
    this.clock = clock;
    this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    this.providerOps = providerOps;
  }

  private <T> T inTx(Supplier<T> action) {
    if (tx == null) {
      return action.get();
    }
    return tx.execute(status -> action.get());
  }

  private void inTx(Runnable action) {
    inTx(
        () -> {
          action.run();
          return null;
        });
  }

  @Override
  public RefundInitiatorPort.RefundPlan initiate(
      Order order, String reason, ActorType cancelledByType, UUID cancelledById) {
    Instant now = clock.instant();
    inTx(
        () ->
            persistCancellation(order, reason, toCancelledBy(cancelledByType), cancelledById, now));
    if (!isAutoRefundDue(order)) {
      return new RefundInitiatorPort.RefundPlan(false, 0L, null);
    }
    Refund primary = null;
    if (order.walletAppliedPaise() > 0) {
      primary =
          executeRefund(
              order,
              order.walletAppliedPaise(),
              RefundTo.WALLET,
              reason,
              null,
              cancelledById,
              issuedByType(cancelledByType),
              null,
              now,
              true,
              true);
    }
    long online = order.totalPayablePaise();
    if (online > 0 && isPayableRefundable(order)) {
      RefundTo to = defaultRefundTo(order);
      boolean autoEligible =
          cancelledByType == ActorType.PHARMACY || cancelledByType == ActorType.SYSTEM;
      primary =
          executeRefund(
              order,
              online,
              to,
              reason,
              null,
              cancelledById,
              issuedByType(cancelledByType),
              null,
              now,
              false,
              autoEligible);
    }
    if (primary == null) {
      return new RefundInitiatorPort.RefundPlan(false, 0L, null);
    }
    return new RefundInitiatorPort.RefundPlan(
        true, primary.amountPaise(), primary.refundTo().name());
  }

  public Refund issueManual(
      Order order,
      long amountPaise,
      RefundTo refundTo,
      String reason,
      String notes,
      UUID issuedBy,
      String idempotencyKey) {
    String key = requireIdempotencyKey(idempotencyKey);
    var replay = refunds.findByIdempotencyKey(key);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    long remaining = remainingRefundablePaise(order);
    if (amountPaise > remaining) {
      throw new AppException(
          "REFUND_EXCEEDS_REMAINING_REFUNDABLE", "Refund exceeds remaining refundable amount", 422);
    }
    if (amountPaise < order.totalPayablePaise() && (notes == null || notes.isBlank())) {
      throw new AppException("VALIDATION_ERROR", "notes are required for partial refunds", 400);
    }
    if (refundTo == RefundTo.SOURCE) {
      long maxSource = onlinePortionMax(order) - sourceRefundedPaise(order.id());
      if (amountPaise > maxSource) {
        throw new AppException(
            "REFUND_EXCEEDS_REMAINING_REFUNDABLE",
            "SOURCE refund exceeds online payment portion",
            422);
      }
    }
    return executeRefund(
        order,
        amountPaise,
        refundTo,
        reason,
        notes,
        issuedBy,
        RefundIssuedByType.ADMIN,
        key,
        clock.instant(),
        true,
        false);
  }

  public Refund issueOnAdminCancel(
      Order order, long amountPaise, RefundTo refundTo, String reason, UUID issuedBy, Instant now) {
    if (amountPaise <= 0) {
      return null;
    }
    if (order.paymentMethod() == PaymentMethod.COD
        && order.paymentStatus() != PaymentStatus.COLLECTED) {
      return null;
    }
    if (amountPaise > order.totalPayablePaise()) {
      throw new AppException(
          "REFUND_EXCEEDS_ORDER_TOTAL", "refund_amount exceeds order total payable", 422);
    }
    if (refundTo == RefundTo.SOURCE && amountPaise > onlinePortionMax(order)) {
      throw new AppException(
          "REFUND_EXCEEDS_ORDER_TOTAL", "SOURCE refund exceeds online payment portion", 422);
    }
    return executeRefund(
        order,
        amountPaise,
        refundTo,
        reason,
        null,
        issuedBy,
        RefundIssuedByType.ADMIN,
        null,
        now,
        true,
        false);
  }

  /** Reverse wallet debit applied at checkout (not bounded by total_payable). */
  public Refund reverseWalletApplied(Order order, String reason, UUID issuedBy, Instant now) {
    if (order.walletAppliedPaise() <= 0) {
      return null;
    }
    return executeRefund(
        order,
        order.walletAppliedPaise(),
        RefundTo.WALLET,
        reason,
        null,
        issuedBy,
        issuedBy == null ? RefundIssuedByType.SYSTEM : RefundIssuedByType.ADMIN,
        null,
        now,
        true,
        true);
  }

  public void persistCancellation(
      Order order, String reason, CancelledByType byType, UUID byId, Instant at) {
    if (cancellations.findByOrderId(order.id()).isPresent()) {
      return;
    }
    String trimmed = reason == null || reason.isBlank() ? "CANCELLED" : reason.trim();
    if (trimmed.length() > 300) {
      trimmed = trimmed.substring(0, 300);
    }
    cancellations.insert(new OrderCancellation(Ids.newId(), order.id(), byType, byId, trimmed, at));
  }

  public long remainingRefundablePaise(Order order) {
    return Math.max(0L, order.totalPayablePaise() - refunds.sumSuccessfulPaise(order.id()));
  }

  public long alreadyRefundedPaise(UUID orderId) {
    return refunds.sumSuccessfulPaise(orderId);
  }

  public RefundTo recommendRefundTo(Order order) {
    return defaultRefundTo(order);
  }

  public static long onlinePortionMax(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD
        || order.paymentMethod() == PaymentMethod.WALLET) {
      return 0L;
    }
    return order.totalPayablePaise();
  }

  @Transactional
  public Map<String, Object> handleRefundProcessed(JsonNode root) {
    JsonNode entity = root.path("payload").path("refund").path("entity");
    String gatewayRefundId = text(entity, "id");
    if (gatewayRefundId == null) {
      return Map.of("ignored", true);
    }
    Instant now = clock.instant();
    Refund refund = refunds.findByGatewayRefundId(gatewayRefundId).orElse(null);
    if (refund == null) {
      return Map.of("ignored", true);
    }
    if (refund.status() == RefundStatus.PROCESSED) {
      Map<String, Object> done = new LinkedHashMap<>();
      done.put("refund_id", refund.id().toString());
      done.put("status", refund.status().name());
      return done;
    }
    refund.markProcessed(now);
    refunds.update(refund);
    refreshOrderPaymentStatus(refund.orderId(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refund_id", refund.id().toString());
    data.put("status", RefundStatus.PROCESSED.name());
    return data;
  }

  private Refund executeRefund(
      Order order,
      long amountPaise,
      RefundTo refundTo,
      String reason,
      String notes,
      UUID issuedBy,
      RefundIssuedByType issuedByType,
      String idempotencyKey,
      Instant now,
      boolean forceProcess,
      boolean autoEligible) {
    UUID id = Ids.newId();
    String reasonText = reason == null ? "REFUND" : truncate(reason, 300);
    String notesText = notes == null || notes.isBlank() ? null : truncate(notes, 500);

    boolean queuePending =
        !forceProcess
            && refundTo == RefundTo.SOURCE
            && !(autoEligible && amountPaise <= AUTO_REFUND_MAX_PAISE);

    if (queuePending) {
      Refund refund =
          new Refund(
              id,
              order.id(),
              amountPaise,
              refundTo,
              reasonText,
              notesText,
              RefundStatus.PENDING,
              issuedBy,
              issuedByType,
              null,
              null,
              null,
              null,
              idempotencyKey,
              now);
      inTx(
          () -> {
            refunds.insert(refund);
            refreshOrderPaymentStatus(order.id(), now);
          });
      return refund;
    }

    if (refundTo == RefundTo.WALLET) {
      String key =
          idempotencyKey != null ? idempotencyKey : "order-refund-" + order.id() + "-" + id;
      return inTx(
          () -> {
            UUID walletTxId =
                wallet.creditForRefund(
                    order.customerId(),
                    order.id(),
                    amountPaise,
                    reason == null ? "Order refund" : reason,
                    key);
            Refund refund =
                new Refund(
                    id,
                    order.id(),
                    amountPaise,
                    refundTo,
                    reasonText,
                    notesText,
                    RefundStatus.PROCESSED,
                    issuedBy,
                    issuedByType,
                    null,
                    walletTxId,
                    now,
                    null,
                    idempotencyKey,
                    now);
            refund.setAutoProcessed(autoEligible);
            refund.setCompletedAt(now);
            if (issuedBy != null) {
              refund.setProcessedBy(issuedBy);
            }
            refunds.insert(refund);
            refreshOrderPaymentStatus(order.id(), now);
            return refund;
          });
    }

    // SOURCE auto/manual process: persist INITIATED, then Cashfree outside TX, then attach id
    String paymentId = order.gatewayPaymentId();
    if (paymentId == null || paymentId.isBlank()) {
      throw new AppException(
          "VALIDATION_ERROR", "Order has no Cashfree payment for SOURCE refund", 422);
    }

    Refund refund =
        new Refund(
            id,
            order.id(),
            amountPaise,
            refundTo,
            reasonText,
            notesText,
            RefundStatus.INITIATED,
            issuedBy,
            issuedByType,
            null,
            null,
            now,
            null,
            idempotencyKey,
            now);
    refund.setAutoProcessed(autoEligible);
    if (issuedBy != null) {
      refund.setProcessedBy(issuedBy);
    }
    inTx(() -> refunds.insert(refund));

    try {
      String opKey = "order-refund:" + id;
      CashfreePaymentPort.RefundResult rz = replayRefund(opKey, amountPaise);
      if (rz == null) {
        if (providerOps != null) {
          providerOps.ensurePending("REFUND", opKey, "cashfree");
        }
        rz = cashfree.refund(paymentId, amountPaise);
        if (providerOps != null) {
          providerOps.markSent("REFUND", opKey, rz.gatewayRefundId());
        }
      }
      final CashfreePaymentPort.RefundResult gateway = rz;
      LocalDate expectedBy = addBusinessDays(LocalDate.ofInstant(now, IST), 5);
      inTx(
          () -> {
            refund.setCashfreeRefundId(gateway.gatewayRefundId());
            refund.setExpectedBy(expectedBy);
            refunds.update(refund);
            refreshOrderPaymentStatus(order.id(), now);
          });
    } catch (RuntimeException ex) {
      inTx(
          () -> {
            refund.markFailed(
                ex.getMessage() == null ? "cashfree refund failed" : ex.getMessage(), now);
            refunds.update(refund);
          });
      if (ex instanceof AppException app) {
        throw app;
      }
      throw new AppException("CASHFREE_REFUND_FAILED", "Failed to initiate Cashfree refund", 502);
    }
    return refund;
  }

  private CashfreePaymentPort.RefundResult replayRefund(String opKey, long amountPaise) {
    if (providerOps == null) {
      return null;
    }
    return providerOps
        .find("REFUND", opKey)
        .filter(ProviderOperationStore.Operation::hasProviderRef)
        .map(op -> new CashfreePaymentPort.RefundResult(op.providerRef(), amountPaise))
        .orElse(null);
  }

  static LocalDate addBusinessDays(LocalDate start, int days) {
    LocalDate d = start;
    int added = 0;
    while (added < days) {
      d = d.plusDays(1);
      DayOfWeek dow = d.getDayOfWeek();
      if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
        added++;
      }
    }
    return d;
  }

  private void refreshOrderPaymentStatus(UUID orderId, Instant now) {
    Order order = orders.findById(orderId).orElse(null);
    if (order == null) {
      return;
    }
    long refunded = refunds.sumSuccessfulPaise(orderId);
    if (refunded <= 0) {
      return;
    }
    boolean partial = refunded < order.totalPayablePaise();
    order.markRefunded(partial, now);
    orders.update(order);
  }

  private static boolean isAutoRefundDue(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD) {
      return order.paymentStatus() == PaymentStatus.COLLECTED || order.walletAppliedPaise() > 0;
    }
    return order.paymentStatus() == PaymentStatus.PAID
        || order.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED
        || order.paymentStatus() == PaymentStatus.REFUNDED
        || order.walletAppliedPaise() > 0;
  }

  private static boolean isPayableRefundable(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD) {
      return order.paymentStatus() == PaymentStatus.COLLECTED;
    }
    return order.paymentStatus() == PaymentStatus.PAID
        || order.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED;
  }

  static RefundTo defaultRefundTo(Order order) {
    if (order.paymentMethod() == PaymentMethod.WALLET
        || order.paymentMethod() == PaymentMethod.COD) {
      return RefundTo.WALLET;
    }
    return RefundTo.SOURCE;
  }

  private long sourceRefundedPaise(UUID orderId) {
    return refunds.listByOrderId(orderId).stream()
        .filter(r -> r.refundTo() == RefundTo.SOURCE)
        .filter(
            r ->
                r.status() == RefundStatus.INITIATED
                    || r.status() == RefundStatus.PROCESSED
                    || r.status() == RefundStatus.PENDING)
        .mapToLong(Refund::amountPaise)
        .sum();
  }

  private static RefundIssuedByType issuedByType(ActorType actorType) {
    if (actorType == ActorType.ADMIN) {
      return RefundIssuedByType.ADMIN;
    }
    if (actorType == ActorType.PHARMACY) {
      return RefundIssuedByType.PHARMACY;
    }
    return RefundIssuedByType.SYSTEM;
  }

  private static CancelledByType toCancelledBy(ActorType actorType) {
    if (actorType == null) {
      return CancelledByType.SYSTEM;
    }
    return switch (actorType) {
      case CUSTOMER -> CancelledByType.CUSTOMER;
      case PHARMACY -> CancelledByType.PHARMACY;
      case ADMIN -> CancelledByType.ADMIN;
      case SYSTEM, RIDER -> CancelledByType.SYSTEM;
    };
  }

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 128) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key max 128 characters", 400);
    }
    return trimmed;
  }

  private static String truncate(String value, int max) {
    if (value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  static String text(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || child.isNull()) {
      return null;
    }
    String v = child.asText();
    return v == null || v.isBlank() ? null : v;
  }

  static RefundTo parseRefundTo(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "refund_to is required", 400);
    }
    try {
      return RefundTo.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "refund_to must be SOURCE or WALLET", 400);
    }
  }
}
