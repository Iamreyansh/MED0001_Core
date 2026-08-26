package com.nammamedmate.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.CashfreeBeneficiaryStore;
import com.nammamedmate.integration.application.port.out.CashfreeClientPort;
import com.nammamedmate.integration.application.port.out.CashfreePaymentRecordStore;
import com.nammamedmate.integration.application.port.out.CashfreePayoutClientPort;
import com.nammamedmate.integration.application.port.out.CashfreePayoutRecordStore;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import com.nammamedmate.integration.domain.EntityTypes;
import com.nammamedmate.integration.domain.PaymentStatuses;
import com.nammamedmate.integration.domain.PayoutModes;
import com.nammamedmate.integration.domain.PayoutStatuses;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashfreeIntegrationService {

  private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
  private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{9,18}$");
  private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");
  private static final Duration RETRY_AFTER = Duration.ofHours(1);

  private final CashfreeClientPort cashfree;
  private final CashfreePayoutClientPort cashfreeX;
  private final CashfreePaymentRecordStore payments;
  private final CashfreeBeneficiaryStore fundAccounts;
  private final CashfreePayoutRecordStore payouts;
  private final IntegrationEventPort events;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CashfreeIntegrationService(
      CashfreeClientPort cashfree,
      CashfreePayoutClientPort cashfreeX,
      CashfreePaymentRecordStore payments,
      CashfreeBeneficiaryStore fundAccounts,
      CashfreePayoutRecordStore payouts,
      IntegrationEventPort events,
      ObjectMapper mapper,
      Clock clock) {
    this.cashfree = cashfree;
    this.cashfreeX = cashfreeX;
    this.payments = payments;
    this.fundAccounts = fundAccounts;
    this.payouts = payouts;
    this.events = events;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    if (amountPaise < 100) {
      throw new AppException("AMOUNT_TOO_SMALL", "amount_paise must be at least 100 (Rs 1)", 400);
    }
    String curr = blankToDefault(currency, "INR");
    String rcpt = nullToEmpty(receipt).trim();
    Map<String, String> noteMap = notes == null ? Map.of() : Map.copyOf(notes);
    CashfreeClientPort.CreateOrderResult created;
    try {
      created = cashfree.createOrder(amountPaise, curr, rcpt, noteMap);
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree API returned error", 503);
    }
    UUID platformOrderId = parseUuid(noteMap.get("platform_order_id")).orElseGet(UUID::randomUUID);
    Instant now = clock.instant();
    CashfreePaymentRecord record =
        new CashfreePaymentRecord(
            UUID.randomUUID(),
            platformOrderId,
            created.gatewayOrderId(),
            null,
            (int) amountPaise,
            created.currency(),
            null,
            PaymentStatuses.CREATED,
            now,
            null);
    payments.insert(record);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cashfree_order_id", created.gatewayOrderId());
    data.put("payment_session_id", created.paymentSessionId());
    data.put("amount_paise", amountPaise);
    data.put("currency", created.currency());
    data.put("receipt", blankToDefault(created.receipt(), rcpt));
    data.put("status", created.status());
    data.put("created_at", now);
    return data;
  }

  @Transactional
  public void handleWebhook(String signatureHeader, byte[] rawBody) {
    handleWebhook(signatureHeader, null, rawBody);
  }

  @Transactional
  public void handleWebhook(String signatureHeader, String timestampHeader, byte[] rawBody) {
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    if (!cashfree.verifyWebhookSignature(signatureHeader, timestampHeader, body)) {
      throw new AppException("INVALID_SIGNATURE", "x-webhook-signature verification failed", 400);
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (Exception e) {
      throw new AppException("VALIDATION_ERROR", "Invalid webhook payload", 400);
    }
    String event = text(root, "event");
    if (isBlank(event)) {
      event = text(root, "type");
    }
    if (isBlank(event)) {
      return;
    }
    String normalized = event.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
    if ("payment.authorized".equals(event) || "PAYMENT_AUTHORIZED".equals(normalized)) {
      handlePaymentAuthorized(root);
    } else if ("payment.captured".equals(event)
        || "PAYMENT_SUCCESS_WEBHOOK".equals(normalized)
        || "PAYMENT_SUCCESS".equals(normalized)) {
      handlePaymentCaptured(root);
    } else if ("payment.failed".equals(event) || "PAYMENT_FAILED_WEBHOOK".equals(normalized)) {
      handlePaymentFailed(root);
    } else if ("refund.created".equals(event)
        || "refund.processed".equals(event)
        || "REFUND_STATUS_WEBHOOK".equals(normalized)) {
      handleRefund(root, event);
    } else if ("payout.processed".equals(event)
        || "TRANSFER_SUCCESS".equals(normalized)
        || "TRANSFER_ACKNOWLEDGED".equals(normalized)) {
      handlePayoutProcessed(root);
    } else if ("payout.failed".equals(event) || "TRANSFER_FAILED".equals(normalized)) {
      handlePayoutFailed(root);
    }
  }

  @Transactional
  public Map<String, Object> initiatePayout(
      String beneficiaryId,
      long amountPaise,
      String modeOverride,
      String purpose,
      String referenceId,
      Map<String, String> notes) {
    if (isBlank(beneficiaryId)) {
      throw new AppException("BENEFICIARY_NOT_FOUND", "beneficiary_id is required", 422);
    }
    CashfreeBeneficiary fa =
        fundAccounts
            .findByBeneficiaryId(beneficiaryId.trim())
            .orElseThrow(
                () -> new AppException("BENEFICIARY_NOT_FOUND", "beneficiary_id not found", 422));
    if (!fa.active()) {
      throw new AppException("BENEFICIARY_NOT_FOUND", "beneficiary_id not found", 422);
    }
    String mode =
        isBlank(modeOverride)
            ? PayoutModes.autoSelect(amountPaise)
            : modeOverride.trim().toUpperCase();
    if (!isPayoutMode(mode)) {
      mode = PayoutModes.autoSelect(amountPaise);
    }
    String ref = isBlank(referenceId) ? "PAYOUT-" + UUID.randomUUID() : referenceId.trim();
    Optional<CashfreePayoutRecord> existing = payouts.findByReferenceId(ref);
    if (existing.isPresent()) {
      return payoutView(existing.get());
    }
    String entityType = noteOr(notes, "entity_type", fa.entityType());
    UUID entityId =
        parseUuid(noteOr(notes, "entity_id", fa.entityId().toString())).orElse(fa.entityId());
    Instant now = clock.instant();
    CashfreePayoutClientPort.PayoutResult result;
    try {
      result =
          cashfreeX.createPayout(
              new CashfreePayoutClientPort.CreatePayoutRequest(
                  fa.beneficiaryId(),
                  amountPaise,
                  mode,
                  blankToDefault(purpose, "payout"),
                  ref,
                  notes == null ? Map.of() : Map.copyOf(notes)));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "CashfreePayout API error", 503);
    }
    CashfreePayoutRecord record =
        new CashfreePayoutRecord(
            UUID.randomUUID(),
            entityType,
            entityId,
            fa.beneficiaryId(),
            result.payoutId(),
            ref,
            amountPaise,
            mode,
            blankToDefault(result.status(), PayoutStatuses.PROCESSING),
            0,
            now,
            null,
            null);
    payouts.insert(record);
    return payoutView(record);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> verifyUpi(String vpa) {
    if (isBlank(vpa) || !VPA.matcher(vpa.trim()).matches()) {
      throw new AppException("VALIDATION_ERROR", "Invalid UPI VPA format", 400);
    }
    CashfreeClientPort.UpiVerifyResult result = cashfree.verifyUpi(vpa.trim());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("vpa", result.vpa());
    data.put("valid", result.valid());
    data.put("name", result.name());
    return data;
  }

  @Transactional
  public Map<String, Object> createBeneficiary(
      String entityType,
      UUID entityId,
      String bankName,
      String accountNumber,
      String ifsc,
      String accountHolderName) {
    if (!EntityTypes.isValid(entityType)) {
      throw new AppException("VALIDATION_ERROR", "entity_type must be PHARMACY or RIDER", 400);
    }
    if (entityId == null) {
      throw new AppException("VALIDATION_ERROR", "entity_id is required", 400);
    }
    String ifscNorm = nullToEmpty(ifsc).trim().toUpperCase();
    if (!IFSC.matcher(ifscNorm).matches()) {
      throw new AppException("INVALID_IFSC", "IFSC code format invalid", 422);
    }
    String acct = nullToEmpty(accountNumber).trim();
    if (!ACCOUNT.matcher(acct).matches()) {
      throw new AppException(
          "INVALID_ACCOUNT_NUMBER", "Account number fails basic validation", 422);
    }
    String last4 = acct.substring(acct.length() - 4);
    Optional<CashfreeBeneficiary> existing = fundAccounts.findActiveByEntity(entityType, entityId);
    if (sameBankAccount(existing, ifscNorm, last4)) {
      return beneficiaryView(existing.get());
    }
    existing.ifPresent(fa -> fundAccounts.deactivate(fa.id()));
    String bank = nullToEmpty(bankName).trim();
    String holder = nullToEmpty(accountHolderName).trim();
    CashfreePayoutClientPort.BeneficiaryResult created;
    try {
      created =
          cashfreeX.createBeneficiary(
              new CashfreePayoutClientPort.CreateBeneficiaryRequest(
                  entityType, entityId.toString(), bank, acct, ifscNorm, holder));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_PAYOUTS_UNAVAILABLE", "CashfreePayout API error", 503);
    }
    Instant now = clock.instant();
    CashfreeBeneficiary record =
        new CashfreeBeneficiary(
            UUID.randomUUID(),
            entityType,
            entityId,
            created.contactId(),
            created.beneficiaryId(),
            bank,
            last4,
            ifscNorm,
            holder,
            true,
            now);
    fundAccounts.insert(record);
    return beneficiaryView(record);
  }

  /** AC-006: retry failed payouts once after 1 hour; second failure → manual-review alert. */
  @Transactional
  public int retryFailedPayouts() {
    Instant cutoff = clock.instant().minus(RETRY_AFTER);
    List<CashfreePayoutRecord> due = payouts.findRetryEligible(cutoff, 50);
    int retried = 0;
    for (CashfreePayoutRecord record : due) {
      CashfreePayoutRecord attempting =
          new CashfreePayoutRecord(
              record.id(),
              record.entityType(),
              record.entityId(),
              record.beneficiaryId(),
              record.cashfreeTransferId(),
              record.referenceId(),
              record.amountPaise(),
              record.mode(),
              PayoutStatuses.PROCESSING,
              1,
              record.initiatedAt(),
              null,
              null);
      payouts.update(attempting);
      try {
        CashfreePayoutClientPort.PayoutResult result =
            cashfreeX.createPayout(
                new CashfreePayoutClientPort.CreatePayoutRequest(
                    record.beneficiaryId(),
                    record.amountPaise(),
                    record.mode(),
                    "payout",
                    record.referenceId() + "-retry",
                    Map.of(
                        "entity_type",
                        record.entityType(),
                        "entity_id",
                        record.entityId().toString())));
        payouts.update(
            new CashfreePayoutRecord(
                attempting.id(),
                attempting.entityType(),
                attempting.entityId(),
                attempting.beneficiaryId(),
                result.payoutId(),
                attempting.referenceId(),
                attempting.amountPaise(),
                attempting.mode(),
                PayoutStatuses.PROCESSING,
                1,
                attempting.initiatedAt(),
                null,
                null));
      } catch (RuntimeException e) {
        markPayoutFailed(attempting, blankToDefault(e.getMessage(), "retry failed"), true);
      }
      retried++;
    }
    return retried;
  }

  public String cashfreeMode() {
    return cashfree.mode();
  }

  private void handlePaymentAuthorized(JsonNode root) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String orderId = text(entity, "order_id");
    long amount = entity.path("amount").asLong(0);
    if (paymentId == null || orderId == null) {
      return;
    }
    Optional<CashfreePaymentRecord> existingPay = payments.findByGatewayPaymentId(paymentId);
    if (existingPay.isPresent() && PaymentStatuses.CAPTURED.equals(existingPay.get().status())) {
      return;
    }
    CashfreePaymentRecord record =
        payments
            .findByGatewayOrderId(orderId)
            .orElseGet(
                () ->
                    new CashfreePaymentRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        orderId,
                        paymentId,
                        (int) amount,
                        "INR",
                        text(entity, "method"),
                        PaymentStatuses.AUTHORIZED,
                        clock.instant(),
                        null));
    CashfreePaymentRecord authorized =
        new CashfreePaymentRecord(
            record.id(),
            record.platformOrderId(),
            record.gatewayOrderId(),
            paymentId,
            record.amountPaise(),
            record.currency(),
            firstNonBlank(text(entity, "method"), record.paymentMethod()),
            PaymentStatuses.AUTHORIZED,
            record.createdAt(),
            null);
    if (payments.findById(record.id()).isEmpty()) {
      payments.insert(authorized);
    } else {
      payments.update(authorized);
    }
    CashfreeClientPort.CaptureResult captured;
    try {
      captured = cashfree.capturePayment(paymentId, authorized.amountPaise());
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree capture failed", 503);
    }
    Instant now = clock.instant();
    CashfreePaymentRecord done =
        new CashfreePaymentRecord(
            authorized.id(),
            authorized.platformOrderId(),
            authorized.gatewayOrderId(),
            paymentId,
            authorized.amountPaise(),
            authorized.currency(),
            authorized.paymentMethod(),
            PaymentStatuses.CAPTURED,
            authorized.createdAt(),
            now);
    payments.update(done);
    events.publish(
        "PAYMENT_CAPTURED",
        "cashfree_payment",
        done.id(),
        Map.of(
            "gateway_payment_id",
            paymentId,
            "gateway_order_id",
            done.gatewayOrderId(),
            "platform_order_id",
            done.platformOrderId().toString(),
            "amount_paise",
            done.amountPaise(),
            "capture_status",
            blankToDefault(captured.status(), PaymentStatuses.CAPTURED)));
  }

  private void handlePaymentCaptured(JsonNode root) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String orderId = text(entity, "order_id");
    if (paymentId == null) {
      return;
    }
    Optional<CashfreePaymentRecord> byPay = payments.findByGatewayPaymentId(paymentId);
    if (byPay.isPresent() && PaymentStatuses.CAPTURED.equals(byPay.get().status())) {
      // AC-002: duplicate ignored
      return;
    }
    CashfreePaymentRecord record =
        byPay.orElseGet(
            () -> orderId == null ? null : payments.findByGatewayOrderId(orderId).orElse(null));
    Instant now = clock.instant();
    if (record == null) {
      long amount = entity.path("amount").asLong(0);
      record =
          new CashfreePaymentRecord(
              UUID.randomUUID(),
              UUID.randomUUID(),
              orderId == null ? "order_unknown" : orderId,
              paymentId,
              (int) amount,
              "INR",
              text(entity, "method"),
              PaymentStatuses.CAPTURED,
              now,
              now);
      payments.insert(record);
    } else {
      payments.update(
          new CashfreePaymentRecord(
              record.id(),
              record.platformOrderId(),
              record.gatewayOrderId(),
              paymentId,
              record.amountPaise(),
              record.currency(),
              firstNonBlank(text(entity, "method"), record.paymentMethod()),
              PaymentStatuses.CAPTURED,
              record.createdAt(),
              now));
    }
    events.publish(
        "PAYMENT_CAPTURED",
        "cashfree_payment",
        record.id(),
        Map.of(
            "gateway_payment_id",
            paymentId,
            "gateway_order_id",
            record.gatewayOrderId(),
            "platform_order_id",
            record.platformOrderId().toString(),
            "amount_paise",
            record.amountPaise()));
  }

  private void handlePaymentFailed(JsonNode root) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String orderId = text(entity, "order_id");
    if (isBlank(orderId) && isBlank(paymentId)) {
      return;
    }
    Optional<CashfreePaymentRecord> record =
        isBlank(paymentId)
            ? payments.findByGatewayOrderId(orderId)
            : payments.findByGatewayPaymentId(paymentId);
    if (record.isEmpty() && !isBlank(orderId)) {
      record = payments.findByGatewayOrderId(orderId);
    }
    record.ifPresent(
        r ->
            payments.update(
                new CashfreePaymentRecord(
                    r.id(),
                    r.platformOrderId(),
                    r.gatewayOrderId(),
                    isBlank(paymentId) ? r.gatewayPaymentId() : paymentId,
                    r.amountPaise(),
                    r.currency(),
                    r.paymentMethod(),
                    PaymentStatuses.FAILED,
                    r.createdAt(),
                    r.capturedAt())));
  }

  private void handleRefund(JsonNode root, String event) {
    JsonNode entity = root.path("payload").path("refund").path("entity");
    String paymentId = text(entity, "payment_id");
    if (paymentId == null) {
      return;
    }
    payments
        .findByGatewayPaymentId(paymentId)
        .ifPresent(
            r -> {
              if ("refund.processed".equals(event)) {
                payments.update(
                    new CashfreePaymentRecord(
                        r.id(),
                        r.platformOrderId(),
                        r.gatewayOrderId(),
                        r.gatewayPaymentId(),
                        r.amountPaise(),
                        r.currency(),
                        r.paymentMethod(),
                        PaymentStatuses.REFUNDED,
                        r.createdAt(),
                        r.capturedAt()));
              }
              events.publish(
                  event.equals("refund.processed") ? "REFUND_PROCESSED" : "REFUND_CREATED",
                  "cashfree_payment",
                  r.id(),
                  Map.of("gateway_payment_id", paymentId, "event", event));
            });
  }

  private void handlePayoutProcessed(JsonNode root) {
    JsonNode entity = root.path("payload").path("payout").path("entity");
    String payoutId = text(entity, "id");
    if (payoutId == null) {
      return;
    }
    Optional<CashfreePayoutRecord> existing = payouts.findByCashfreexPayoutId(payoutId);
    if (existing.filter(r -> PayoutStatuses.PROCESSED.equals(r.status())).isPresent()) {
      return;
    }
    existing.ifPresent(
        r ->
            payouts.update(
                new CashfreePayoutRecord(
                    r.id(),
                    r.entityType(),
                    r.entityId(),
                    r.beneficiaryId(),
                    r.cashfreeTransferId(),
                    r.referenceId(),
                    r.amountPaise(),
                    r.mode(),
                    PayoutStatuses.PROCESSED,
                    r.retryCount(),
                    r.initiatedAt(),
                    clock.instant(),
                    null)));
  }

  private void handlePayoutFailed(JsonNode root) {
    JsonNode entity = root.path("payload").path("payout").path("entity");
    String payoutId = text(entity, "id");
    String reason = text(entity, "failure_reason");
    if (payoutId == null) {
      return;
    }
    payouts
        .findByCashfreexPayoutId(payoutId)
        .ifPresent(
            r -> markPayoutFailed(r, blankToDefault(reason, "payout failed"), r.retryCount() >= 1));
  }

  private void markPayoutFailed(CashfreePayoutRecord record, String reason, boolean alert) {
    CashfreePayoutRecord failed =
        new CashfreePayoutRecord(
            record.id(),
            record.entityType(),
            record.entityId(),
            record.beneficiaryId(),
            record.cashfreeTransferId(),
            record.referenceId(),
            record.amountPaise(),
            record.mode(),
            PayoutStatuses.FAILED,
            record.retryCount(),
            record.initiatedAt(),
            clock.instant(),
            reason);
    payouts.update(failed);
    if (alert) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("payout_record_id", record.id().toString());
      payload.put("cashfree_transfer_id", record.cashfreeTransferId());
      payload.put("entity_type", record.entityType());
      payload.put("entity_id", record.entityId().toString());
      payload.put("reference_id", record.referenceId());
      payload.put("retry_count", record.retryCount());
      events.publish("PAYOUT_MANUAL_REVIEW", "cashfree_payouts_payout", record.id(), payload);
    }
  }

  private static Map<String, Object> payoutView(CashfreePayoutRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cashfree_transfer_id", r.cashfreeTransferId());
    data.put("beneficiary_id", r.beneficiaryId());
    data.put("amount_paise", r.amountPaise());
    data.put("mode", r.mode());
    data.put("status", r.status());
    data.put("reference_id", r.referenceId());
    data.put("initiated_at", r.initiatedAt());
    return data;
  }

  private static Map<String, Object> beneficiaryView(CashfreeBeneficiary fa) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("beneficiary_id", fa.beneficiaryId());
    data.put("entity_type", fa.entityType());
    data.put("entity_id", fa.entityId());
    data.put("bank_name", fa.bankName());
    data.put("account_last4", fa.accountLast4());
    data.put("ifsc", fa.ifsc());
    data.put("account_holder_name", fa.accountHolderName());
    data.put("cashfree_contact_id", fa.cashfreeContactId());
    data.put("created_at", fa.createdAt());
    return data;
  }

  private static String noteOr(Map<String, String> notes, String key, String fallback) {
    if (notes == null) {
      return fallback;
    }
    String v = notes.get(key);
    return isBlank(v) ? fallback : v.trim();
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (isBlank(raw)) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    String s = v.asText("");
    return isBlank(s) ? null : s;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String blankToDefault(String s, String defaultValue) {
    return isBlank(s) ? defaultValue : s.trim();
  }

  private static String firstNonBlank(String primary, String fallback) {
    return isBlank(primary) ? fallback : primary;
  }

  private static boolean isPayoutMode(String mode) {
    return switch (mode) {
      case PayoutModes.IMPS, PayoutModes.NEFT, PayoutModes.UPI -> true;
      default -> false;
    };
  }

  private static boolean sameBankAccount(
      Optional<CashfreeBeneficiary> existing, String ifsc, String last4) {
    if (existing.isEmpty()) {
      return false;
    }
    CashfreeBeneficiary fa = existing.get();
    if (!fa.ifsc().equals(ifsc)) {
      return false;
    }
    return fa.accountLast4().equals(last4);
  }
}
