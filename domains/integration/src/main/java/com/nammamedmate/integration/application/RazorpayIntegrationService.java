package com.nammamedmate.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayPaymentRecordStore;
import com.nammamedmate.integration.application.port.out.RazorpayXClientPort;
import com.nammamedmate.integration.application.port.out.RazorpayXFundAccountStore;
import com.nammamedmate.integration.application.port.out.RazorpayXPayoutRecordStore;
import com.nammamedmate.integration.domain.EntityTypes;
import com.nammamedmate.integration.domain.PaymentStatuses;
import com.nammamedmate.integration.domain.PayoutModes;
import com.nammamedmate.integration.domain.PayoutStatuses;
import com.nammamedmate.integration.domain.RazorpayPaymentRecord;
import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
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
public class RazorpayIntegrationService {

  private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
  private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{9,18}$");
  private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");
  private static final Duration RETRY_AFTER = Duration.ofHours(1);

  private final RazorpayClientPort razorpay;
  private final RazorpayXClientPort razorpayX;
  private final RazorpayPaymentRecordStore payments;
  private final RazorpayXFundAccountStore fundAccounts;
  private final RazorpayXPayoutRecordStore payouts;
  private final IntegrationEventPort events;
  private final ObjectMapper mapper;
  private final Clock clock;

  public RazorpayIntegrationService(
      RazorpayClientPort razorpay,
      RazorpayXClientPort razorpayX,
      RazorpayPaymentRecordStore payments,
      RazorpayXFundAccountStore fundAccounts,
      RazorpayXPayoutRecordStore payouts,
      IntegrationEventPort events,
      ObjectMapper mapper,
      Clock clock) {
    this.razorpay = razorpay;
    this.razorpayX = razorpayX;
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
    RazorpayClientPort.CreateOrderResult created;
    try {
      created = razorpay.createOrder(amountPaise, curr, rcpt, noteMap);
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay API returned error", 503);
    }
    UUID platformOrderId = parseUuid(noteMap.get("platform_order_id")).orElseGet(UUID::randomUUID);
    Instant now = clock.instant();
    RazorpayPaymentRecord record =
        new RazorpayPaymentRecord(
            UUID.randomUUID(),
            platformOrderId,
            created.razorpayOrderId(),
            null,
            (int) amountPaise,
            created.currency(),
            null,
            PaymentStatuses.CREATED,
            now,
            null);
    payments.insert(record);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("razorpay_order_id", created.razorpayOrderId());
    data.put("amount_paise", amountPaise);
    data.put("currency", created.currency());
    data.put("receipt", blankToDefault(created.receipt(), rcpt));
    data.put("status", created.status());
    data.put("created_at", now);
    return data;
  }

  @Transactional
  public void handleWebhook(String signatureHeader, byte[] rawBody) {
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    if (!razorpay.verifyWebhookSignature(signatureHeader, body)) {
      throw new AppException("INVALID_SIGNATURE", "X-Razorpay-Signature verification failed", 400);
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (Exception e) {
      throw new AppException("VALIDATION_ERROR", "Invalid webhook payload", 400);
    }
    String event = text(root, "event");
    if (isBlank(event)) {
      return;
    }
    switch (event) {
      case "payment.authorized" -> handlePaymentAuthorized(root);
      case "payment.captured" -> handlePaymentCaptured(root);
      case "payment.failed" -> handlePaymentFailed(root);
      case "refund.created", "refund.processed" -> handleRefund(root, event);
      case "payout.processed" -> handlePayoutProcessed(root);
      case "payout.failed" -> handlePayoutFailed(root);
      default -> {
        // acknowledge unknown events
      }
    }
  }

  @Transactional
  public Map<String, Object> initiatePayout(
      String fundAccountId,
      long amountPaise,
      String modeOverride,
      String purpose,
      String referenceId,
      Map<String, String> notes) {
    if (isBlank(fundAccountId)) {
      throw new AppException("FUND_ACCOUNT_NOT_FOUND", "fund_account_id is required", 422);
    }
    RazorpayXFundAccount fa =
        fundAccounts
            .findByFundAccountId(fundAccountId.trim())
            .orElseThrow(
                () -> new AppException("FUND_ACCOUNT_NOT_FOUND", "fund_account_id not found", 422));
    if (!fa.active()) {
      throw new AppException("FUND_ACCOUNT_NOT_FOUND", "fund_account_id not found", 422);
    }
    String mode =
        isBlank(modeOverride)
            ? PayoutModes.autoSelect(amountPaise)
            : modeOverride.trim().toUpperCase();
    if (!isPayoutMode(mode)) {
      mode = PayoutModes.autoSelect(amountPaise);
    }
    String ref = isBlank(referenceId) ? "PAYOUT-" + UUID.randomUUID() : referenceId.trim();
    Optional<RazorpayXPayoutRecord> existing = payouts.findByReferenceId(ref);
    if (existing.isPresent()) {
      return payoutView(existing.get());
    }
    String entityType = noteOr(notes, "entity_type", fa.entityType());
    UUID entityId =
        parseUuid(noteOr(notes, "entity_id", fa.entityId().toString())).orElse(fa.entityId());
    Instant now = clock.instant();
    RazorpayXClientPort.PayoutResult result;
    try {
      result =
          razorpayX.createPayout(
              new RazorpayXClientPort.CreatePayoutRequest(
                  fa.fundAccountId(),
                  amountPaise,
                  mode,
                  blankToDefault(purpose, "payout"),
                  ref,
                  notes == null ? Map.of() : Map.copyOf(notes)));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX API error", 503);
    }
    RazorpayXPayoutRecord record =
        new RazorpayXPayoutRecord(
            UUID.randomUUID(),
            entityType,
            entityId,
            fa.fundAccountId(),
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
    RazorpayClientPort.UpiVerifyResult result = razorpay.verifyUpi(vpa.trim());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("vpa", result.vpa());
    data.put("valid", result.valid());
    data.put("name", result.name());
    return data;
  }

  @Transactional
  public Map<String, Object> createFundAccount(
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
    Optional<RazorpayXFundAccount> existing = fundAccounts.findActiveByEntity(entityType, entityId);
    if (sameBankAccount(existing, ifscNorm, last4)) {
      return fundAccountView(existing.get());
    }
    existing.ifPresent(fa -> fundAccounts.deactivate(fa.id()));
    String bank = nullToEmpty(bankName).trim();
    String holder = nullToEmpty(accountHolderName).trim();
    RazorpayXClientPort.FundAccountResult created;
    try {
      created =
          razorpayX.createFundAccount(
              new RazorpayXClientPort.CreateFundAccountRequest(
                  entityType, entityId.toString(), bank, acct, ifscNorm, holder));
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAYX_UNAVAILABLE", "RazorpayX API error", 503);
    }
    Instant now = clock.instant();
    RazorpayXFundAccount record =
        new RazorpayXFundAccount(
            UUID.randomUUID(),
            entityType,
            entityId,
            created.contactId(),
            created.fundAccountId(),
            bank,
            last4,
            ifscNorm,
            holder,
            true,
            now);
    fundAccounts.insert(record);
    return fundAccountView(record);
  }

  /** AC-006: retry failed payouts once after 1 hour; second failure → manual-review alert. */
  @Transactional
  public int retryFailedPayouts() {
    Instant cutoff = clock.instant().minus(RETRY_AFTER);
    List<RazorpayXPayoutRecord> due = payouts.findRetryEligible(cutoff, 50);
    int retried = 0;
    for (RazorpayXPayoutRecord record : due) {
      RazorpayXPayoutRecord attempting =
          new RazorpayXPayoutRecord(
              record.id(),
              record.entityType(),
              record.entityId(),
              record.fundAccountId(),
              record.razorpayxPayoutId(),
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
        RazorpayXClientPort.PayoutResult result =
            razorpayX.createPayout(
                new RazorpayXClientPort.CreatePayoutRequest(
                    record.fundAccountId(),
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
            new RazorpayXPayoutRecord(
                attempting.id(),
                attempting.entityType(),
                attempting.entityId(),
                attempting.fundAccountId(),
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

  public String razorpayMode() {
    return razorpay.mode();
  }

  private void handlePaymentAuthorized(JsonNode root) {
    JsonNode entity = root.path("payload").path("payment").path("entity");
    String paymentId = text(entity, "id");
    String orderId = text(entity, "order_id");
    long amount = entity.path("amount").asLong(0);
    if (paymentId == null || orderId == null) {
      return;
    }
    Optional<RazorpayPaymentRecord> existingPay = payments.findByRazorpayPaymentId(paymentId);
    if (existingPay.isPresent() && PaymentStatuses.CAPTURED.equals(existingPay.get().status())) {
      return;
    }
    RazorpayPaymentRecord record =
        payments
            .findByRazorpayOrderId(orderId)
            .orElseGet(
                () ->
                    new RazorpayPaymentRecord(
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
    RazorpayPaymentRecord authorized =
        new RazorpayPaymentRecord(
            record.id(),
            record.platformOrderId(),
            record.razorpayOrderId(),
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
    RazorpayClientPort.CaptureResult captured;
    try {
      captured = razorpay.capturePayment(paymentId, authorized.amountPaise());
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay capture failed", 503);
    }
    Instant now = clock.instant();
    RazorpayPaymentRecord done =
        new RazorpayPaymentRecord(
            authorized.id(),
            authorized.platformOrderId(),
            authorized.razorpayOrderId(),
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
        "razorpay_payment",
        done.id(),
        Map.of(
            "razorpay_payment_id",
            paymentId,
            "razorpay_order_id",
            done.razorpayOrderId(),
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
    Optional<RazorpayPaymentRecord> byPay = payments.findByRazorpayPaymentId(paymentId);
    if (byPay.isPresent() && PaymentStatuses.CAPTURED.equals(byPay.get().status())) {
      // AC-002: duplicate ignored
      return;
    }
    RazorpayPaymentRecord record =
        byPay.orElseGet(
            () -> orderId == null ? null : payments.findByRazorpayOrderId(orderId).orElse(null));
    Instant now = clock.instant();
    if (record == null) {
      long amount = entity.path("amount").asLong(0);
      record =
          new RazorpayPaymentRecord(
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
          new RazorpayPaymentRecord(
              record.id(),
              record.platformOrderId(),
              record.razorpayOrderId(),
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
        "razorpay_payment",
        record.id(),
        Map.of(
            "razorpay_payment_id",
            paymentId,
            "razorpay_order_id",
            record.razorpayOrderId(),
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
    Optional<RazorpayPaymentRecord> record =
        isBlank(paymentId)
            ? payments.findByRazorpayOrderId(orderId)
            : payments.findByRazorpayPaymentId(paymentId);
    if (record.isEmpty() && !isBlank(orderId)) {
      record = payments.findByRazorpayOrderId(orderId);
    }
    record.ifPresent(
        r ->
            payments.update(
                new RazorpayPaymentRecord(
                    r.id(),
                    r.platformOrderId(),
                    r.razorpayOrderId(),
                    isBlank(paymentId) ? r.razorpayPaymentId() : paymentId,
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
        .findByRazorpayPaymentId(paymentId)
        .ifPresent(
            r -> {
              if ("refund.processed".equals(event)) {
                payments.update(
                    new RazorpayPaymentRecord(
                        r.id(),
                        r.platformOrderId(),
                        r.razorpayOrderId(),
                        r.razorpayPaymentId(),
                        r.amountPaise(),
                        r.currency(),
                        r.paymentMethod(),
                        PaymentStatuses.REFUNDED,
                        r.createdAt(),
                        r.capturedAt()));
              }
              events.publish(
                  event.equals("refund.processed") ? "REFUND_PROCESSED" : "REFUND_CREATED",
                  "razorpay_payment",
                  r.id(),
                  Map.of("razorpay_payment_id", paymentId, "event", event));
            });
  }

  private void handlePayoutProcessed(JsonNode root) {
    JsonNode entity = root.path("payload").path("payout").path("entity");
    String payoutId = text(entity, "id");
    if (payoutId == null) {
      return;
    }
    Optional<RazorpayXPayoutRecord> existing = payouts.findByRazorpayxPayoutId(payoutId);
    if (existing.filter(r -> PayoutStatuses.PROCESSED.equals(r.status())).isPresent()) {
      return;
    }
    existing.ifPresent(
        r ->
            payouts.update(
                new RazorpayXPayoutRecord(
                    r.id(),
                    r.entityType(),
                    r.entityId(),
                    r.fundAccountId(),
                    r.razorpayxPayoutId(),
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
        .findByRazorpayxPayoutId(payoutId)
        .ifPresent(
            r -> markPayoutFailed(r, blankToDefault(reason, "payout failed"), r.retryCount() >= 1));
  }

  private void markPayoutFailed(RazorpayXPayoutRecord record, String reason, boolean alert) {
    RazorpayXPayoutRecord failed =
        new RazorpayXPayoutRecord(
            record.id(),
            record.entityType(),
            record.entityId(),
            record.fundAccountId(),
            record.razorpayxPayoutId(),
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
      payload.put("razorpayx_payout_id", record.razorpayxPayoutId());
      payload.put("entity_type", record.entityType());
      payload.put("entity_id", record.entityId().toString());
      payload.put("reference_id", record.referenceId());
      payload.put("retry_count", record.retryCount());
      events.publish("PAYOUT_MANUAL_REVIEW", "razorpayx_payout", record.id(), payload);
    }
  }

  private static Map<String, Object> payoutView(RazorpayXPayoutRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("razorpayx_payout_id", r.razorpayxPayoutId());
    data.put("fund_account_id", r.fundAccountId());
    data.put("amount_paise", r.amountPaise());
    data.put("mode", r.mode());
    data.put("status", r.status());
    data.put("reference_id", r.referenceId());
    data.put("initiated_at", r.initiatedAt());
    return data;
  }

  private static Map<String, Object> fundAccountView(RazorpayXFundAccount fa) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("fund_account_id", fa.fundAccountId());
    data.put("entity_type", fa.entityType());
    data.put("entity_id", fa.entityId());
    data.put("bank_name", fa.bankName());
    data.put("account_last4", fa.accountLast4());
    data.put("ifsc", fa.ifsc());
    data.put("account_holder_name", fa.accountHolderName());
    data.put("razorpayx_contact_id", fa.razorpayxContactId());
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
      Optional<RazorpayXFundAccount> existing, String ifsc, String last4) {
    if (existing.isEmpty()) {
      return false;
    }
    RazorpayXFundAccount fa = existing.get();
    if (!fa.ifsc().equals(ifsc)) {
      return false;
    }
    return fa.accountLast4().equals(last4);
  }
}
