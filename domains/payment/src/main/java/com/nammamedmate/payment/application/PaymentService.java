package com.nammamedmate.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort.OrderSnapshot;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.PaymentStore;
import com.nammamedmate.payment.application.port.out.WalletPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.Payment;
import com.nammamedmate.payment.domain.PaymentMethod;
import com.nammamedmate.payment.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

  public static final Duration CASHFREE_ORDER_TTL = Duration.ofMinutes(15);
  private static final BigDecimal DEFAULT_COMMISSION_PCT = new BigDecimal("8.00");

  private final PaymentStore payments;
  private final CashfreeGatewayPort cashfree;
  private final WalletPort wallet;
  private final OrderLookupPort orders;
  private final OrderPaymentStatusPort orderStatus;
  private final FinancialLedgerWriterPort ledger;
  private final RefundFacadeService refundFacade;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final BigDecimal commissionPct;

  public PaymentService(
      PaymentStore payments,
      CashfreeGatewayPort cashfree,
      WalletPort wallet,
      OrderLookupPort orders,
      OrderPaymentStatusPort orderStatus,
      FinancialLedgerWriterPort ledger,
      RefundFacadeService refundFacade,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${medmate.payment.commission-pct:8.00}") BigDecimal commissionPct) {
    this.payments = payments;
    this.cashfree = cashfree;
    this.wallet = wallet;
    this.orders = orders;
    this.orderStatus = orderStatus;
    this.ledger = ledger;
    this.refundFacade = refundFacade;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.commissionPct = commissionPct == null ? DEFAULT_COMMISSION_PCT : commissionPct;
  }

  @Transactional
  public Map<String, Object> initiate(
      MedmatePrincipal principal, UUID orderId, Long amountPaise, String currency, String method) {
    return initiate(principal, orderId, amountPaise, currency, method, null);
  }

  @Transactional
  public Map<String, Object> initiate(
      MedmatePrincipal principal,
      UUID orderId,
      Long amountPaise,
      String currency,
      String method,
      String idempotencyKey) {
    requireCustomer(principal);
    String idem =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? Ids.newId().toString()
            : idempotencyKey.trim();
    var replay = payments.findByIdempotencyKey(idem);
    if (replay.isPresent()) {
      Payment existingKey = replay.get();
      if (orderId != null && !existingKey.orderId().equals(orderId)) {
        throw new AppException(
            "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used for another payment", 409);
      }
      return initiateView(existingKey, cashfree.keyId());
    }
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    if (amountPaise == null || amountPaise <= 0) {
      throw new AppException("INVALID_AMOUNT", "amount_paise must be positive", 422);
    }
    OrderSnapshot order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    if (!order.customerId().equals(principal.subject())) {
      throw new AppException("ORDER_NOT_YOURS", "Order belongs to a different customer", 403);
    }

    PaymentMethod orderMethod = PaymentMethod.fromOrderMethod(order.paymentMethod());
    if (orderMethod == PaymentMethod.COD) {
      throw new AppException(
          "COD_ORDER_NO_PAYMENT", "COD orders do not use the payment gateway", 422);
    }

    long gross = order.totalPayablePaise() + order.walletAppliedPaise();
    if (amountPaise != gross && amountPaise != order.totalPayablePaise()) {
      throw new AppException("INVALID_AMOUNT", "amount_paise does not match order total", 422);
    }

    PaymentMethod requested =
        method == null || method.isBlank() ? orderMethod : PaymentMethod.fromOrderMethod(method);
    if (requested == PaymentMethod.COD) {
      throw new AppException(
          "COD_ORDER_NO_PAYMENT", "COD orders do not use the payment gateway", 422);
    }

    var existing = payments.findByOrderId(orderId);
    if (existing.isPresent()) {
      Payment p = existing.get();
      if (p.status() == PaymentStatus.FAILED) {
        // allow re-initiate after failure — delete path: update in place below via replace
      } else {
        throw new AppException(
            "PAYMENT_ALREADY_INITIATED", "Payment already initiated for this order", 409);
      }
    }

    Instant now = clock.instant();
    long walletPortion;
    long gatewayPortion;
    // Re-initiate after FAILED: reuse prior wallet debit — WalletService.debitForOrder is not
    // idempotent by orderId and would drain the wallet again.
    if (existing.isPresent() && existing.get().walletPortionPaise() > 0) {
      walletPortion = existing.get().walletPortionPaise();
      gatewayPortion = Math.max(0L, amountPaise - walletPortion);
    } else if (order.walletAppliedPaise() > 0 && amountPaise == order.totalPayablePaise()) {
      walletPortion = 0L;
      gatewayPortion = amountPaise;
    } else if (order.walletAppliedPaise() > 0) {
      walletPortion = order.walletAppliedPaise();
      gatewayPortion = order.totalPayablePaise();
    } else {
      walletPortion =
          wallet.debitForOrder(
              principal.subject(), orderId, amountPaise, "Payment for order " + orderId);
      gatewayPortion = amountPaise - walletPortion;
    }

    PaymentMethod effective = gatewayPortion <= 0 ? PaymentMethod.WALLET_ONLY : requested;

    String gatewayOrderId = null;
    String paymentSessionId = null;
    String keyId = cashfree.keyId();
    if (gatewayPortion > 0) {
      try {
        var rz = cashfree.createOrder(orderId, gatewayPortion);
        gatewayOrderId = rz.gatewayOrderId();
        paymentSessionId = rz.paymentSessionId();
        if (rz.keyId() != null && !rz.keyId().isBlank()) {
          keyId = rz.keyId();
        }
      } catch (AppException e) {
        if ("CASHFREE_ERROR".equals(e.code()) || "PAYMENT_INITIATION_FAILED".equals(e.code())) {
          throw new AppException("CASHFREE_ERROR", e.getMessage(), 502);
        }
        throw e;
      } catch (RuntimeException e) {
        throw new AppException("CASHFREE_ERROR", "Cashfree order creation failed", 502);
      }
    }

    Payment payment;
    if (existing.isPresent()) {
      // earlier guard ensures status == FAILED
      Payment failed = existing.get();
      payment =
          new Payment(
              failed.id(),
              orderId,
              principal.subject(),
              amountPaise,
              walletPortion,
              gatewayPortion,
              normalizeCurrency(currency),
              effective,
              gatewayPortion <= 0 ? PaymentStatus.CAPTURED : PaymentStatus.PENDING,
              gatewayOrderId,
              null,
              null,
              null,
              null,
              List.of(),
              gatewayPortion <= 0 ? now : null,
              null,
              null,
              idem,
              failed.createdAt(),
              now);
      payments.update(payment);
      if (gatewayPortion <= 0) {
        writeCaptureLedger(payment, 0L);
        orderStatus.onCaptured(orderId, null);
      }
    } else {
      UUID paymentId = Ids.newId();
      PaymentStatus status = gatewayPortion <= 0 ? PaymentStatus.CAPTURED : PaymentStatus.PENDING;
      payment =
          new Payment(
              paymentId,
              orderId,
              principal.subject(),
              amountPaise,
              walletPortion,
              gatewayPortion,
              normalizeCurrency(currency),
              effective,
              status,
              gatewayOrderId,
              null,
              null,
              null,
              null,
              List.of(),
              gatewayPortion <= 0 ? now : null,
              null,
              null,
              idem,
              now,
              now);
      payments.insert(payment);
      if (gatewayPortion <= 0) {
        writeCaptureLedger(payment, 0L);
        orderStatus.onCaptured(orderId, null);
      }
    }

    return initiateView(payment, keyId, paymentSessionId);
  }

  private Map<String, Object> initiateView(Payment payment, String keyId) {
    return initiateView(payment, keyId, null);
  }

  private Map<String, Object> initiateView(Payment payment, String keyId, String paymentSessionId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payment_id", payment.id());
    data.put("order_id", payment.orderId());
    data.put("gateway_order_id", payment.gatewayOrderId());
    data.put("payment_session_id", paymentSessionId);
    data.put("cashfree_app_id", keyId);
    data.put("amount_paise", payment.amountPaise());
    data.put("amount_rupees", MoneyFormats.paiseToRupees(payment.amountPaise()));
    data.put("currency", payment.currency());
    data.put("method", payment.method().name());
    data.put("wallet_deducted", MoneyFormats.paiseToRupees(payment.walletPortionPaise()));
    data.put("gateway_amount_paise", payment.gatewayPortionPaise());
    data.put("expires_at", payment.createdAt().plus(CASHFREE_ORDER_TTL));
    return data;
  }

  @Transactional
  public Map<String, Object> verify(
      MedmatePrincipal principal,
      String gatewayPaymentId,
      String gatewayOrderId,
      String gatewaySignature) {
    requireCustomer(principal);
    if (gatewayOrderId == null) {
      throw new AppException("VALIDATION_ERROR", "gateway_order_id is required", 400);
    }
    if (gatewayOrderId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "gateway_order_id is required", 400);
    }
    if (gatewayPaymentId == null) {
      throw new AppException("VALIDATION_ERROR", "gateway_payment_id is required", 400);
    }
    if (gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "gateway_payment_id is required", 400);
    }
    if (gatewaySignature == null) {
      throw new AppException("PAYMENT_SIGNATURE_INVALID", "HMAC signature missing", 422);
    }
    if (gatewaySignature.isBlank()) {
      throw new AppException("PAYMENT_SIGNATURE_INVALID", "HMAC signature missing", 422);
    }

    Payment payment =
        payments
            .findByGatewayOrderId(gatewayOrderId.trim())
            .orElseThrow(() -> new AppException("PAYMENT_NOT_FOUND", "Payment not found", 404));
    if (!payment.customerId().equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "Payment belongs to a different customer", 403);
    }
    if (payment.status() == PaymentStatus.CAPTURED) {
      throw new AppException("PAYMENT_ALREADY_VERIFIED", "Payment already captured", 409);
    }
    if (!cashfree.verifyPaymentSignature(
        gatewayOrderId.trim(), gatewayPaymentId.trim(), gatewaySignature.trim())) {
      throw new AppException("PAYMENT_SIGNATURE_INVALID", "HMAC verification failed", 422);
    }

    Instant now = clock.instant();
    long fee = estimateGatewayFee(payment.gatewayPortionPaise());
    payment.capture(gatewayPaymentId.trim(), gatewaySignature.trim(), fee, null, now);
    payments.update(payment);
    writeCaptureLedger(payment, fee);
    orderStatus.onCaptured(payment.orderId(), gatewayPaymentId.trim());
    return verifyView(payment);
  }

  @Transactional
  public Map<String, Object> handleWebhook(String signatureHeader, byte[] rawBody) {
    return handleWebhook(signatureHeader, null, rawBody);
  }

  @Transactional
  public Map<String, Object> handleWebhook(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    if (!cashfree.verifyWebhookSignature(signatureHeader, timestampHeader, body)) {
      // BR-007: reject + log without body/secrets
      org.slf4j.LoggerFactory.getLogger(PaymentService.class)
          .warn("Cashfree webhook signature invalid (bytes={})", body.length);
      throw new AppException("WEBHOOK_SIGNATURE_INVALID", "Webhook signature invalid", 400);
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      String event = text(root, "event");
      if (event == null) {
        event = text(root, "type");
      }
      if (event == null) {
        return webhookAck(null, null, false);
      }
      String normalized = event.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
      if (isRefundEvent(event, normalized)) {
        return refundFacade.completeFromWebhook(root);
      }
      if (isPaymentFailedEvent(event, normalized)) {
        return handlePaymentFailed(root, event);
      }
      if (isPaymentSuccessEvent(event, normalized)) {
        return handlePaymentCaptured(root, event);
      }
      // Story: UNKNOWN_EVENT at HTTP 200 — acknowledge, no action
      throw new AppException("UNKNOWN_EVENT", "Event type not handled: " + event, 200);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("VALIDATION_ERROR", "Invalid webhook payload", 400);
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getPayment(MedmatePrincipal principal, UUID paymentId) {
    if (paymentId == null) {
      throw new AppException("VALIDATION_ERROR", "payment_id is required", 400);
    }
    Payment payment =
        payments
            .findById(paymentId)
            .orElseThrow(() -> new AppException("PAYMENT_NOT_FOUND", "Payment not found", 404));
    if (principal.role() == AuthRole.CUSTOMER
        && !payment.customerId().equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "Cannot view another customer's payment", 403);
    }
    if (principal.role() != AuthRole.CUSTOMER
        && principal.role() != AuthRole.ADMIN_FINANCE
        && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    return detailView(payment);
  }

  private Map<String, Object> handlePaymentCaptured(JsonNode root, String event) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String gatewayOrderId = text(entity, "order_id");
    if (paymentId == null) {
      return webhookAck(event, null, false);
    }
    // Idempotent by gateway_payment_id
    var byPayId = payments.findByGatewayPaymentId(paymentId);
    if (byPayId.isPresent()) {
      if (byPayId.get().status() == PaymentStatus.CAPTURED) {
        return webhookAck(event, paymentId, false);
      }
    }
    Payment payment =
        byPayId.orElseGet(
            () ->
                gatewayOrderId == null
                    ? null
                    : payments.findByGatewayOrderId(gatewayOrderId).orElse(null));
    if (payment == null) {
      return webhookAck(event, paymentId, false);
    }
    if (payment.status() == PaymentStatus.CAPTURED) {
      return webhookAck(event, paymentId, false);
    }
    Instant now = clock.instant();
    Long feePaise = null;
    JsonNode feeNode = entity.get("fee");
    if (feeNode != null) {
      feePaise = feeNode.isNumber() ? feeNode.asLong() : null;
    }
    if (feePaise == null) {
      feePaise = estimateGatewayFee(payment.gatewayPortionPaise());
    }
    String gatewayJson = entity.toString();
    payment.capture(paymentId, null, feePaise, gatewayJson, now);
    payment.appendWebhookEvent(event);
    payments.update(payment);
    writeCaptureLedger(payment, feePaise);
    orderStatus.onCaptured(payment.orderId(), paymentId);
    return webhookAck(event, paymentId, true);
  }

  private Map<String, Object> handlePaymentFailed(JsonNode root, String event) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String gatewayOrderId = text(entity, "order_id");
    if (paymentId != null) {
      var byPayId = payments.findByGatewayPaymentId(paymentId);
      if (byPayId.isPresent()) {
        if (byPayId.get().status() == PaymentStatus.FAILED) {
          return webhookAck(event, paymentId, false);
        }
      }
    }
    Payment payment =
        gatewayOrderId == null ? null : payments.findByGatewayOrderId(gatewayOrderId).orElse(null);
    if (payment == null) {
      if (paymentId != null) {
        payment = payments.findByGatewayPaymentId(paymentId).orElse(null);
      }
    }
    if (payment == null) {
      return webhookAck(event, paymentId, false);
    }
    if (payment.status() == PaymentStatus.CAPTURED) {
      return webhookAck(event, paymentId, false);
    }
    Instant now = clock.instant();
    String reason = text(entity, "error_description");
    if (reason == null) {
      reason = text(entity, "error_code");
    }
    if (reason == null) {
      reason = "payment.failed";
    }
    payment.fail(paymentId, reason, entity.toString(), now);
    payment.appendWebhookEvent(event);
    payments.update(payment);
    orderStatus.onFailed(payment.orderId(), payment.failureReason());
    return webhookAck(event, paymentId, true);
  }

  private void writeCaptureLedger(Payment payment, long gatewayFeePaise) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("order_id", payment.orderId().toString());
    meta.put("payment_id", payment.id().toString());
    ledger.append(
        "ORDER_GMV", payment.id(), "PAYMENT", payment.amountPaise(), 0L, "Order GMV capture", meta);
    long commission =
        BigDecimal.valueOf(payment.amountPaise())
            .multiply(commissionPct)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .longValueExact();
    if (commission > 0) {
      // STORY-008 AC-001 / BR-003: COMMISSION is a credit (platform revenue recognition).
      ledger.append(
          "COMMISSION",
          payment.id(),
          "PAYMENT",
          commission,
          0L,
          "Platform commission " + commissionPct + "%",
          meta);
    }
    if (gatewayFeePaise > 0) {
      ledger.append(
          "GATEWAY_FEE",
          payment.id(),
          "PAYMENT",
          0L,
          gatewayFeePaise,
          "Cashfree gateway fee",
          meta);
    }
  }

  private static long estimateGatewayFee(long gatewayPortionPaise) {
    // ponytail: ~2% stub fee until live Cashfree fee field present; upgrade: use entity.fee
    long base = Math.max(0L, gatewayPortionPaise);
    return BigDecimal.valueOf(base)
        .multiply(new BigDecimal("0.02"))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static Map<String, Object> verifyView(Payment payment) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payment_id", payment.id());
    data.put("order_id", payment.orderId());
    data.put("payment_status", payment.status().name());
    data.put("amount", MoneyFormats.paiseToRupees(payment.amountPaise()));
    data.put("method", payment.method().name());
    data.put("transaction_id", payment.gatewayPaymentId());
    data.put("captured_at", payment.capturedAt());
    return data;
  }

  private static Map<String, Object> detailView(Payment payment) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payment_id", payment.id());
    data.put("order_id", payment.orderId());
    data.put("customer_id", payment.customerId());
    data.put("amount", MoneyFormats.paiseToRupees(payment.amountPaise()));
    data.put("wallet_portion", MoneyFormats.paiseToRupees(payment.walletPortionPaise()));
    data.put("gateway_portion", MoneyFormats.paiseToRupees(payment.gatewayPortionPaise()));
    data.put("method", payment.method().name());
    data.put("status", payment.status().name());
    data.put("gateway_payment_id", payment.gatewayPaymentId());
    data.put("gateway_order_id", payment.gatewayOrderId());
    data.put(
        "gateway_fee",
        payment.gatewayFeePaise() == null
            ? null
            : MoneyFormats.paiseToRupees(payment.gatewayFeePaise()));
    data.put("captured_at", payment.capturedAt());
    data.put("created_at", payment.createdAt());
    return data;
  }

  private static Map<String, Object> webhookAck(String event, String paymentId, boolean processed) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("event", event);
    data.put("payment_id", paymentId);
    data.put("processed", processed);
    return data;
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Customer role required", 403);
    }
    if (principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "Customer role required", 403);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null) {
      return null;
    }
    if (v.isNull()) {
      return null;
    }
    String s = v.asText("");
    return s.isBlank() ? null : s;
  }

  private static boolean isPaymentSuccessEvent(String raw, String normalized) {
    return "payment.captured".equals(raw)
        || "PAYMENT_SUCCESS_WEBHOOK".equals(normalized)
        || "PAYMENT_SUCCESS".equals(normalized)
        || "PAYMENT_CAPTURED".equals(normalized);
  }

  private static boolean isPaymentFailedEvent(String raw, String normalized) {
    return "payment.failed".equals(raw)
        || "PAYMENT_FAILED_WEBHOOK".equals(normalized)
        || "PAYMENT_FAILED".equals(normalized);
  }

  private static boolean isRefundEvent(String raw, String normalized) {
    return "refund.processed".equals(raw)
        || "refund.created".equals(raw)
        || "REFUND_STATUS_WEBHOOK".equals(normalized)
        || "REFUND_PROCESSED".equals(normalized)
        || "REFUND_SUCCESS".equals(normalized);
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null) {
      return "INR";
    }
    if (currency.isBlank()) {
      return "INR";
    }
    return currency.trim().toUpperCase();
  }
}
