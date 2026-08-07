package com.nammamedmate.pharmacy.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort.PayoutRequest;
import com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort.PayoutResult;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListResult;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.SettlementRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacySettlementService {

  static final String LOCAL_WEBHOOK_SECRET = "local-razorpayx-webhook-secret";

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int READ_LIMIT = 60;
  private static final int MUTATE_LIMIT = 20;
  private static final int WINDOW = 60;
  private static final int DEFAULT_PAGE_LIMIT = 20;
  private static final int MAX_PAGE_LIMIT = 50;
  private static final Set<String> STATUSES =
      Set.of("PENDING_RELEASE", "RELEASED", "PAID", "HELD", "FAILED", "ALL");

  private final AdminPharmacyStore pharmacies;
  private final SettlementStore settlements;
  private final PharmacyProfileStore profiles;
  private final RazorpayXPayoutPort razorpayx;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final String webhookSecret;

  public AdminPharmacySettlementService(
      AdminPharmacyStore pharmacies,
      SettlementStore settlements,
      PharmacyProfileStore profiles,
      RazorpayXPayoutPort razorpayx,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper,
      @Value("${medmate.razorpayx.webhook-secret:}") String webhookSecret) {
    this.pharmacies = pharmacies;
    this.settlements = settlements;
    this.profiles = profiles;
    this.razorpayx = razorpayx;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
  }

  public static void validateWebhookSecretForDeployedProfile(
      String secret, boolean deployedProfile) {
    if (!deployedProfile) {
      return;
    }
    if (secret == null || secret.isBlank() || LOCAL_WEBHOOK_SECRET.equals(secret)) {
      throw new IllegalStateException(
          "medmate.razorpayx.webhook-secret must be injected via Secrets Manager for staging/prod");
    }
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PagedResult listSettlements(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String status,
      LocalDate fromDate,
      LocalDate toDate,
      Integer page,
      Integer limit) {
    AdminPharmacyCommissionService.requireReadRole(principal);
    rateLimit("admin:pharmacies:settlements:get:" + principal.subject(), READ_LIMIT);
    requirePharmacy(pharmacyId);

    String normalisedStatus = normaliseStatus(status);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit =
        limit == null || limit < 1 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
    LocalDate today = LocalDate.now(clock.withZone(IST));
    LocalDate from = fromDate == null ? today.minusDays(90) : fromDate;
    LocalDate to = toDate == null ? today : toDate;

    ListResult result =
        settlements.list(
            pharmacyId,
            new ListFilter(normalisedStatus, from, to, pageLimit, (pageNum - 1) * pageLimit));

    List<Map<String, Object>> items = new ArrayList<>();
    for (SettlementRow row : result.settlements()) {
      items.add(toSettlementMap(row));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("settlements", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  @Transactional
  public Map<String, Object> release(
      MedmatePrincipal principal,
      UUID pharmacyId,
      UUID settlementId,
      String notes,
      String idempotencyKey) {
    requireFinanceRole(principal);
    rateLimit("admin:pharmacies:settlements:release:" + principal.subject(), MUTATE_LIMIT);
    requirePharmacy(pharmacyId);

    String key = requireIdempotencyKey(idempotencyKey);
    var existing = settlements.findByIdempotencyKey(key);
    if (existing.isPresent()) {
      SettlementRow row = existing.get();
      if (!row.id().equals(settlementId) || !row.pharmacyId().equals(pharmacyId)) {
        throw new AppException(
            "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used for another settlement", 409);
      }
      return releaseResponse(row, principal.subject(), !"FAILED".equals(row.status()));
    }

    SettlementRow settlement =
        settlements
            .findByIdForPharmacy(pharmacyId, settlementId)
            .orElseThrow(
                () -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found", 404));

    rejectInvalidReleaseState(settlement);

    BankAccountRecord bank =
        profiles
            .findActiveBankAccount(pharmacyId)
            .filter(b -> "VERIFIED".equals(b.verificationStatus()))
            .orElseThrow(
                () ->
                    new AppException(
                        "BANK_ACCOUNT_NOT_VERIFIED", "Pharmacy has no verified bank account", 422));

    Instant now = clock.instant();
    if (!settlements.claimForRelease(settlementId, pharmacyId, key, now)) {
      var replay = settlements.findByIdempotencyKey(key);
      if (replay.isPresent()) {
        return releaseResponse(
            replay.get(), principal.subject(), !"FAILED".equals(replay.get().status()));
      }
      SettlementRow current =
          settlements
              .findByIdForPharmacy(pharmacyId, settlementId)
              .orElseThrow(
                  () -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found", 404));
      rejectInvalidReleaseState(current);
      throw new AppException("SETTLEMENT_CONFLICT", "Could not claim settlement for release", 409);
    }

    try {
      PayoutResult payout =
          razorpayx.initiatePayout(
              new PayoutRequest(
                  pharmacyId,
                  settlementId,
                  settlement.netPaidPaise(),
                  bank.accountNumberLast4(),
                  bank.ifscCode()));

      if (!settlements.finalizeRelease(
          settlementId, principal.subject(), now, payout.razorpayxPayoutId(), key, now)) {
        throw new AppException("SETTLEMENT_CONFLICT", "Could not finalize settlement release", 409);
      }

      notifications.dispatchSettlementReleased(pharmacyId, settlementId, settlement.netPaidPaise());
    } catch (RuntimeException ex) {
      settlements.markReleaseFailed(settlementId, key, now);
      if (ex instanceof AppException app) {
        throw app;
      }
      throw new AppException("PAYOUT_FAILED", "Failed to initiate payout", 502);
    }

    SettlementRow updated = settlements.findByIdForPharmacy(pharmacyId, settlementId).orElseThrow();
    return releaseResponse(updated, principal.subject(), true);
  }

  @Transactional
  public Map<String, Object> hold(
      MedmatePrincipal principal, UUID pharmacyId, UUID settlementId, String reason) {
    requireFinanceRole(principal);
    rateLimit("admin:pharmacies:settlements:hold:" + principal.subject(), MUTATE_LIMIT);
    requirePharmacy(pharmacyId);

    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    if (reason.length() > 500) {
      throw new AppException("REASON_REQUIRED", "reason must be at most 500 characters", 400);
    }

    SettlementRow settlement =
        settlements
            .findByIdForPharmacy(pharmacyId, settlementId)
            .orElseThrow(
                () -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found", 404));

    if ("PAID".equals(settlement.status())) {
      throw new AppException(
          "SETTLEMENT_ALREADY_PAID", "Cannot hold a settlement that is already paid", 409);
    }

    Instant now = clock.instant();
    settlements.updateHeld(settlementId, reason.trim(), now);
    notifications.dispatchSettlementHeld(pharmacyId, settlementId, reason.trim());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", settlementId.toString());
    data.put("status", "HELD");
    data.put("held_at", now.toString());
    data.put("held_by", principal.subject().toString());
    data.put("reason", reason.trim());
    data.put("pharmacy_notified", true);
    return data;
  }

  @Transactional
  public Map<String, Object> handlePayoutWebhook(String signatureHeader, byte[] rawBody) {
    verifyWebhookSignature(signatureHeader, rawBody);
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      String event = text(root, "event");
      if (!"payout.processed".equals(event) && !"payout.updated".equals(event)) {
        return Map.of("ignored", true);
      }
      JsonNode payload = root.path("payload").path("payout").path("entity");
      String status = text(payload, "status");
      if (!"processed".equalsIgnoreCase(status)) {
        return Map.of("ignored", true);
      }
      String payoutId = text(payload, "id");
      String utr = text(payload, "utr");
      String referenceId = text(payload, "reference_id");
      if (payoutId == null || payoutId.isBlank()) {
        return Map.of("ignored", true);
      }

      UUID settlementId = parseSettlementId(referenceId, payoutId);
      SettlementRow settlement =
          settlements
              .findById(settlementId)
              .orElseThrow(
                  () -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found", 404));

      if ("PAID".equals(settlement.status())) {
        return Map.of("settlement_id", settlementId.toString(), "status", "PAID");
      }
      if (!"RELEASED".equals(settlement.status())) {
        return Map.of("ignored", true);
      }

      Instant now = clock.instant();
      String receiptUrl = "https://cdn.example.com/receipts/settlement-" + settlementId + ".pdf";
      String resolvedUtr = utr == null ? "" : utr;
      settlements.updatePaid(
          settlementId, resolvedUtr.isBlank() ? null : resolvedUtr, receiptUrl, now, now);
      notifications.dispatchSettlementPaid(
          settlement.pharmacyId(), settlementId, settlement.netPaidPaise(), resolvedUtr);

      return Map.of(
          "settlement_id", settlementId.toString(), "status", "PAID", "utr_number", resolvedUtr);
    } catch (AppException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AppException("WEBHOOK_INVALID", "Invalid webhook payload", 400);
    }
  }

  private Map<String, Object> releaseResponse(
      SettlementRow settlement, UUID releasedBy, boolean payoutInitiated) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", settlement.id().toString());
    data.put("status", settlement.status());
    data.put(
        "released_at", settlement.releasedAt() == null ? null : settlement.releasedAt().toString());
    data.put("released_by", releasedBy.toString());
    data.put("payout_initiated", payoutInitiated);
    data.put("razorpayx_payout_id", settlement.razorpayxPayoutId());
    data.put("estimated_credit_hours", 4);
    data.put("message", "Settlement released. Payout initiated to pharmacy bank account.");
    return data;
  }

  static Map<String, Object> toSettlementMap(SettlementRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("settlement_id", row.id().toString());
    m.put(
        "period_label",
        com.nammamedmate.pharmacy.domain.SettlementPeriod.label(
            row.periodStart(), row.periodEnd()));
    m.put("period_start", row.periodStart().toString());
    m.put("period_end", row.periodEnd().toString());
    m.put("gmv", AdminPharmacyCommissionService.paiseToRupees(row.gmvPaise()));
    m.put("commission_pct", AdminPharmacyCommissionService.scalePct(row.commissionPct()));
    m.put(
        "commission_earned",
        AdminPharmacyCommissionService.paiseToRupees(row.commissionEarnedPaise()));
    m.put("tcs_rate_pct", row.tcsRatePct());
    m.put("tcs_deducted", AdminPharmacyCommissionService.paiseToRupees(row.tcsDeductedPaise()));
    m.put("net_paid", AdminPharmacyCommissionService.paiseToRupees(row.netPaidPaise()));
    m.put("status", row.status());
    m.put("released_at", row.releasedAt() == null ? null : row.releasedAt().toString());
    m.put("paid_at", row.paidAt() == null ? null : row.paidAt().toString());
    m.put("utr_number", row.utrNumber());
    m.put("hold_reason", row.holdReason());
    m.put("receipt_url", row.receiptUrl());
    return m;
  }

  UUID parseSettlementId(String referenceId, String payoutId) {
    if (referenceId != null && !referenceId.isBlank()) {
      try {
        return UUID.fromString(referenceId.trim());
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    return settlements
        .findByRazorpayxPayoutId(payoutId)
        .map(SettlementRow::id)
        .orElseThrow(
            () -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found for payout", 404));
  }

  private String normaliseStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ALL";
    }
    String s = status.trim().toUpperCase();
    if (!STATUSES.contains(s)) {
      throw new AppException("INVALID_STATUS", "Invalid settlement status filter", 400);
    }
    return s;
  }

  private void requirePharmacy(UUID pharmacyId) {
    pharmacies
        .findDetail(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  static void requireFinanceRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException(
          "FORBIDDEN", "Only admin_finance or admin_super may release or hold settlements", 403);
    }
  }

  private void rejectInvalidReleaseState(SettlementRow settlement) {
    switch (settlement.status()) {
      case "RELEASED", "PAID" ->
          throw new AppException(
              "SETTLEMENT_ALREADY_RELEASED", "Settlement already released or paid", 409);
      case "HELD" ->
          throw new AppException(
              "SETTLEMENT_HELD", "Settlement is held; remove hold before releasing", 409);
      default -> {
        // continue
      }
    }
  }

  private void verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new AppException("INVALID_WEBHOOK_SIGNATURE", "Missing webhook signature", 401);
    }
    String expected = AutoKycService.hmacSha256Hex(webhookSecret, rawBody);
    String provided = signatureHeader.trim();
    if (provided.startsWith("sha256=")) {
      provided = provided.substring("sha256=".length());
    }
    if (!constantTimeEquals(expected.toLowerCase(Locale.ROOT), provided.toLowerCase(Locale.ROOT))) {
      throw new AppException("INVALID_WEBHOOK_SIGNATURE", "HMAC signature does not match", 401);
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }

  static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 128) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key must be at most 128 characters", 400);
    }
    return trimmed;
  }

  private static String text(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child == null || child.isNull() ? null : child.asText();
  }
}
