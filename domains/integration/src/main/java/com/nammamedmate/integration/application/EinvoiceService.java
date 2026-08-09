package com.nammamedmate.integration.application;

import com.nammamedmate.integration.application.port.out.EinvoiceApiCallLogStore;
import com.nammamedmate.integration.application.port.out.EinvoiceIrnRecordStore;
import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.integration.application.port.out.GspClientPort.IrnResult;
import com.nammamedmate.integration.application.port.out.GspClientPort.TokenState;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.application.port.out.PharmacyEinvoiceFlagStore;
import com.nammamedmate.integration.domain.EinvoiceApiCallLog;
import com.nammamedmate.integration.domain.EinvoiceApiTypes;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import com.nammamedmate.integration.domain.EinvoiceStatuses;
import com.nammamedmate.integration.domain.FinancialYears;
import com.nammamedmate.integration.domain.InvoiceSchemaValidator;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EinvoiceService {

  private static final Logger log = LoggerFactory.getLogger(EinvoiceService.class);

  private static final Duration CANCEL_WINDOW = Duration.ofHours(24);
  private static final Duration TOKEN_REFRESH_LEAD = Duration.ofHours(1);
  private static final Set<String> CANCEL_REASONS = Set.of("1", "2", "3", "4");

  private final GspClientPort gsp;
  private final EinvoiceIrnRecordStore records;
  private final EinvoiceApiCallLogStore callLog;
  private final PharmacyEinvoiceFlagStore pharmacyFlags;
  private final IntegrationEventPort events;
  private final Clock clock;

  public EinvoiceService(
      GspClientPort gsp,
      EinvoiceIrnRecordStore records,
      EinvoiceApiCallLogStore callLog,
      PharmacyEinvoiceFlagStore pharmacyFlags,
      IntegrationEventPort events,
      Clock clock) {
    this.gsp = gsp;
    this.records = records;
    this.callLog = callLog;
    this.pharmacyFlags = pharmacyFlags;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> generateIrn(
      UUID pharmacyId, UUID platformInvoiceId, Map<String, Object> invoiceData) {
    Instant started = clock.instant();
    if (pharmacyId != null) {
      Optional<Boolean> enabled = pharmacyFlags.findEInvoicingEnabled(pharmacyId);
      if (enabled.isPresent() && !enabled.get()) {
        logCall(
            EinvoiceApiTypes.GENERATE_IRN,
            "pharmacy=" + pharmacyId,
            200,
            "SKIPPED",
            latency(started));
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("irn", null);
        skipped.put("skipped", true);
        skipped.put("reason", "E_INVOICING_DISABLED");
        skipped.put("already_existed", false);
        return skipped;
      }
    }

    InvoiceSchemaValidator.validate(invoiceData);
    String seller = str(invoiceData.get("seller_gstin")).toUpperCase(Locale.ROOT);
    String buyer = str(invoiceData.get("buyer_gstin")).toUpperCase(Locale.ROOT);
    String invoiceNumber = str(invoiceData.get("invoice_number"));
    LocalDate invoiceDate = LocalDate.parse(str(invoiceData.get("invoice_date")).substring(0, 10));
    String documentType = str(invoiceData.get("invoice_type"));
    String fy = FinancialYears.of(invoiceDate);

    Optional<EinvoiceIrnRecord> existing =
        records.findByDocumentKey(seller, buyer, documentType, fy, invoiceNumber);
    if (existing.isPresent()) {
      EinvoiceIrnRecord row = existing.get();
      if (EinvoiceStatuses.CANCELLED.equals(row.status())) {
        logCall(
            EinvoiceApiTypes.GENERATE_IRN,
            summary(seller, invoiceNumber),
            422,
            "ERROR",
            latency(started));
        throw new AppException(
            "DUPLICATE_IRN",
            "Cancelled IRN cannot be regenerated; use a new invoice_number",
            422,
            null,
            Map.of("irn", row.irn(), "status", EinvoiceStatuses.CANCELLED));
      }
      logCall(
          EinvoiceApiTypes.GENERATE_IRN,
          summary(seller, invoiceNumber),
          200,
          "OK",
          latency(started));
      return toGenerateResponse(row, true);
    }

    IrnResult result;
    try {
      result = gsp.generateIrn(normalizeInvoice(invoiceData, seller, buyer));
    } catch (AppException e) {
      logCall(
          EinvoiceApiTypes.GENERATE_IRN,
          summary(seller, invoiceNumber),
          e.httpStatus(),
          "ERROR",
          latency(started));
      throw e;
    }

    BigDecimal total = totalValue(invoiceData);
    Instant now = clock.instant();
    EinvoiceIrnRecord record =
        new EinvoiceIrnRecord(
            Ids.newId(),
            pharmacyId,
            platformInvoiceId,
            result.irn(),
            result.ackNumber(),
            result.ackDate() == null ? now : result.ackDate(),
            seller,
            buyer,
            invoiceNumber,
            invoiceDate,
            documentType,
            fy,
            total,
            result.qrCodeUrl(),
            result.signedInvoiceJson(),
            EinvoiceStatuses.ACTIVE,
            null,
            null,
            now,
            null);
    records.insert(record);
    logCall(
        EinvoiceApiTypes.GENERATE_IRN, summary(seller, invoiceNumber), 200, "OK", latency(started));
    return toGenerateResponse(record, false);
  }

  @Transactional
  public Map<String, Object> cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {
    Instant started = clock.instant();
    String normalizedIrn = str(irn);
    if (normalizedIrn.isBlank()) {
      throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
    }
    String reason = str(cancelReasonCode);
    if (!CANCEL_REASONS.contains(reason)) {
      throw new AppException(
          "INVALID_INVOICE_SCHEMA",
          "cancel_reason_code must be 1–4",
          422,
          null,
          Map.of("field", "cancel_reason_code"));
    }

    EinvoiceIrnRecord record =
        records
            .findByIrn(normalizedIrn)
            .orElseThrow(
                () -> {
                  logCall(
                      EinvoiceApiTypes.CANCEL_IRN,
                      "irn=" + maskIrn(normalizedIrn),
                      404,
                      "NOT_FOUND",
                      latency(started));
                  return new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
                });

    if (EinvoiceStatuses.CANCELLED.equals(record.status())) {
      logCall(
          EinvoiceApiTypes.CANCEL_IRN,
          "irn=" + maskIrn(normalizedIrn),
          422,
          "ERROR",
          latency(started));
      throw new AppException("IRN_ALREADY_CANCELLED", "IRN already cancelled", 422);
    }

    Instant now = clock.instant();
    if (now.isAfter(record.generatedAt().plus(CANCEL_WINDOW))) {
      logCall(
          EinvoiceApiTypes.CANCEL_IRN,
          "irn=" + maskIrn(normalizedIrn),
          422,
          "ERROR",
          latency(started));
      throw new AppException(
          "IRN_CANCELLATION_WINDOW_EXPIRED",
          "IRN can only be cancelled within 24 hours of generation",
          422);
    }

    try {
      gsp.cancelIrn(normalizedIrn, reason, str(cancelRemark));
    } catch (AppException e) {
      logCall(
          EinvoiceApiTypes.CANCEL_IRN,
          "irn=" + maskIrn(normalizedIrn),
          e.httpStatus(),
          "ERROR",
          latency(started));
      throw e;
    }

    EinvoiceIrnRecord updated =
        new EinvoiceIrnRecord(
            record.id(),
            record.pharmacyId(),
            record.platformInvoiceId(),
            record.irn(),
            record.ackNumber(),
            record.ackDate(),
            record.sellerGstin(),
            record.buyerGstin(),
            record.invoiceNumber(),
            record.invoiceDate(),
            record.documentType(),
            record.financialYear(),
            record.totalInvoiceValue(),
            record.qrCodeUrl(),
            record.signedInvoiceJson(),
            EinvoiceStatuses.CANCELLED,
            reason,
            str(cancelRemark),
            record.generatedAt(),
            now);
    records.update(updated);
    logCall(
        EinvoiceApiTypes.CANCEL_IRN, "irn=" + maskIrn(normalizedIrn), 200, "OK", latency(started));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("irn", updated.irn());
    data.put("status", EinvoiceStatuses.CANCELLED);
    data.put("cancel_reason_code", reason);
    data.put("cancelled_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> status(String irn) {
    Instant started = clock.instant();
    String normalizedIrn = str(irn);
    Optional<EinvoiceIrnRecord> local = records.findByIrn(normalizedIrn);
    if (local.isPresent()) {
      logCall(
          EinvoiceApiTypes.STATUS, "irn=" + maskIrn(normalizedIrn), 200, "OK", latency(started));
      return toStatusResponse(local.get());
    }
    try {
      var remote = gsp.getStatus(normalizedIrn);
      logCall(
          EinvoiceApiTypes.STATUS, "irn=" + maskIrn(normalizedIrn), 200, "OK", latency(started));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("irn", remote.irn());
      data.put("status", remote.status());
      data.put("ack_number", remote.ackNumber());
      data.put("ack_date", remote.ackDate() == null ? null : remote.ackDate().toString());
      data.put(
          "cancelled_at", remote.cancelledAt() == null ? null : remote.cancelledAt().toString());
      return data;
    } catch (AppException e) {
      logCall(
          EinvoiceApiTypes.STATUS,
          "irn=" + maskIrn(normalizedIrn),
          e.httpStatus(),
          "IRN_NOT_FOUND".equals(e.code()) || e.httpStatus() == 404 ? "NOT_FOUND" : "ERROR",
          latency(started));
      throw e;
    }
  }

  /** Cron: refresh GSP JWT ~1h before 24h expiry; failures → CRITICAL + outbox alert. */
  public void refreshTokenIfNeeded() {
    Instant started = clock.instant();
    Optional<TokenState> current = gsp.currentToken();
    boolean needsRefresh =
        current.isEmpty()
            || !current.get().expiresAt().isAfter(clock.instant().plus(TOKEN_REFRESH_LEAD));
    if (!needsRefresh) {
      return;
    }
    try {
      TokenState next = gsp.refreshToken();
      logCall(
          EinvoiceApiTypes.TOKEN_REFRESH,
          "expires_at=" + next.expiresAt(),
          200,
          "OK",
          latency(started));
    } catch (RuntimeException e) {
      log.error("CRITICAL gsp_token_refresh_failed: {}", e.getMessage());
      logCall(EinvoiceApiTypes.TOKEN_REFRESH, "refresh", 503, "ERROR", latency(started));
      events.publish(
          "integration.gsp_token_refresh_failed",
          "gsp_token",
          Ids.newId(),
          Map.of("severity", "CRITICAL", "message", String.valueOf(e.getMessage())));
      if (e instanceof AppException app) {
        throw app;
      }
      throw new AppException("NIC_PORTAL_UNAVAILABLE", "GSP token refresh failed", 503);
    }
  }

  private Map<String, Object> toGenerateResponse(EinvoiceIrnRecord row, boolean alreadyExisted) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("irn", row.irn());
    data.put("ack_number", row.ackNumber());
    data.put("ack_date", row.ackDate().toString());
    data.put("qr_code_url", row.qrCodeUrl());
    data.put("signed_invoice_json", row.signedInvoiceJson());
    data.put("already_existed", alreadyExisted);
    data.put("generated_at", row.generatedAt().toString());
    return data;
  }

  private Map<String, Object> toStatusResponse(EinvoiceIrnRecord row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("irn", row.irn());
    data.put("status", row.status());
    data.put("ack_number", row.ackNumber());
    data.put("ack_date", row.ackDate().toString());
    data.put("seller_gstin", row.sellerGstin());
    data.put("buyer_gstin", row.buyerGstin());
    data.put("invoice_number", row.invoiceNumber());
    data.put("invoice_date", row.invoiceDate().toString());
    data.put("total_invoice_value", row.totalInvoiceValue());
    data.put("generated_at", row.generatedAt().toString());
    data.put("cancelled_at", row.cancelledAt() == null ? null : row.cancelledAt().toString());
    return data;
  }

  private Map<String, Object> normalizeInvoice(
      Map<String, Object> invoiceData, String seller, String buyer) {
    Map<String, Object> copy = new LinkedHashMap<>(invoiceData);
    copy.put("seller_gstin", seller);
    copy.put("buyer_gstin", buyer);
    return copy;
  }

  private BigDecimal totalValue(Map<String, Object> invoiceData) {
    @SuppressWarnings("unchecked")
    Map<String, Object> tax = (Map<String, Object>) invoiceData.get("tax_amounts");
    return new BigDecimal(tax.get("total_invoice_value").toString());
  }

  private void logCall(
      String apiType, String summary, Integer httpStatus, String responseStatus, int latencyMs) {
    callLog.insert(
        new EinvoiceApiCallLog(
            Ids.newId(),
            apiType,
            truncate(summary),
            httpStatus,
            responseStatus,
            latencyMs,
            clock.instant()));
  }

  private int latency(Instant started) {
    return (int) Math.max(0, Duration.between(started, clock.instant()).toMillis());
  }

  private static String summary(String seller, String invoiceNumber) {
    return "seller=" + seller + ",inv=" + invoiceNumber;
  }

  private static String maskIrn(String irn) {
    if (irn.length() < 8) {
      return "****";
    }
    return irn.substring(0, 8) + "…";
  }

  private static String truncate(String s) {
    return s.length() <= 200 ? s : s.substring(0, 200);
  }

  private static String str(Object v) {
    return v == null ? "" : v.toString().trim();
  }
}
