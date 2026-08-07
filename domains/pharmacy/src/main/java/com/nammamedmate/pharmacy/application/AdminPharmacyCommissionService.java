package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore.CommissionHistoryRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.SettlementRow;
import com.nammamedmate.pharmacy.domain.SettlementCalculator;
import com.nammamedmate.pharmacy.domain.SettlementPeriod;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacyCommissionService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal MIN_PCT = new BigDecimal("3.00");
  private static final BigDecimal MAX_PCT = new BigDecimal("20.00");
  private static final BigDecimal TCS_RATE = new BigDecimal("1.00");
  private static final int READ_LIMIT = 60;
  private static final int MUTATE_LIMIT = 10;
  private static final int WINDOW = 60;

  private final AdminPharmacyStore pharmacies;
  private final CommissionHistoryStore commissionHistory;
  private final SettlementStore settlements;
  private final PharmacyOrderMetricsPort orderMetrics;
  private final PharmacyProfileStore profiles;
  private final AuditLogStore auditLog;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public AdminPharmacyCommissionService(
      AdminPharmacyStore pharmacies,
      CommissionHistoryStore commissionHistory,
      SettlementStore settlements,
      PharmacyOrderMetricsPort orderMetrics,
      PharmacyProfileStore profiles,
      AuditLogStore auditLog,
      RateLimiter rateLimiter,
      Clock clock) {
    this.pharmacies = pharmacies;
    this.commissionHistory = commissionHistory;
    this.settlements = settlements;
    this.orderMetrics = orderMetrics;
    this.profiles = profiles;
    this.auditLog = auditLog;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getCommission(MedmatePrincipal principal, UUID pharmacyId) {
    requireReadRole(principal);
    rateLimit("admin:pharmacies:commission:get:" + principal.subject(), READ_LIMIT);

    AdminDetailRow pharmacy = requirePharmacy(pharmacyId);
    LocalDate today = LocalDate.now(clock.withZone(IST));

    long annualGmvYtd = orderMetrics.annualGmvYtdPaise(pharmacyId);
    boolean tcsApplicable = annualGmvYtd > SettlementCalculator.TCS_THRESHOLD_PAISE;

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("business_name", pharmacy.businessName());
    data.put("current_commission_pct", scalePct(pharmacy.commissionPct()));
    data.put("pending_commission_change", pendingChange(pharmacyId));
    data.put("tcs_applicable", tcsApplicable);
    data.put("tcs_rate_pct", tcsApplicable ? TCS_RATE : BigDecimal.ZERO.setScale(2));
    data.put("annual_gmv_ytd", paiseToRupees(annualGmvYtd));
    data.put("tcs_threshold_crossed", tcsApplicable);
    data.put("current_period", currentPeriod(pharmacy, today, annualGmvYtd));
    data.putAll(bankSummary(pharmacyId));
    data.put("last_settlement_date", lastSettlementDate(pharmacyId));
    data.put("next_settlement_date", nextSettlementDate(today));
    return data;
  }

  @Transactional
  public Map<String, Object> changeCommission(
      MedmatePrincipal principal,
      UUID pharmacyId,
      BigDecimal commissionPct,
      LocalDate effectiveFrom,
      String reason,
      String notes,
      String clientIp) {
    requireWriteRole(principal);
    rateLimit("admin:pharmacies:commission:patch:" + principal.subject(), MUTATE_LIMIT);

    AdminDetailRow pharmacy = requirePharmacy(pharmacyId);
    validateCommissionPct(commissionPct);
    validateEffectiveFrom(effectiveFrom);
    validateReason(reason);

    if (commissionHistory.findPendingChange(pharmacyId).isPresent()) {
      throw new AppException(
          "PENDING_CHANGE_EXISTS",
          "A commission change is already scheduled; cancel it before creating a new one",
          409);
    }

    Instant now = clock.instant();
    UUID historyId = Ids.newId();
    BigDecimal previous = pharmacy.commissionPct();
    BigDecimal scaled = scalePct(commissionPct);

    commissionHistory.insert(
        new CommissionHistoryRow(
            historyId,
            pharmacyId,
            previous,
            scaled,
            effectiveFrom,
            reason.trim(),
            blankToNull(notes),
            principal.subject(),
            now,
            null));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("before_value", previous.toPlainString());
    payload.put("after_value", scaled.toPlainString());
    payload.put("effective_from", effectiveFrom.toString());
    payload.put("reason", reason.trim());
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "PHARMACY",
            pharmacyId,
            "COMMISSION_CHANGED",
            principal.subject(),
            principal.role().name(),
            payload,
            clientIp,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("previous_commission_pct", scalePct(previous));
    data.put("new_commission_pct", scaled);
    data.put("effective_from", effectiveFrom.toString());
    data.put("reason", reason.trim());
    data.put("changed_by", principal.subject().toString());
    data.put("changed_at", now.toString());
    data.put("commission_history_id", historyId.toString());
    return data;
  }

  private Map<String, Object> pendingChange(UUID pharmacyId) {
    return commissionHistory
        .findPendingChange(pharmacyId)
        .map(
            row -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("new_commission_pct", scalePct(row.newCommissionPct()));
              m.put("effective_from", row.effectiveFrom().toString());
              m.put("changed_by", row.changedBy().toString());
              m.put("reason", row.reason());
              return m;
            })
        .orElse(null);
  }

  private Map<String, Object> currentPeriod(
      AdminDetailRow pharmacy, LocalDate today, long annualGmvYtd) {
    LocalDate periodStart = SettlementPeriod.weekMonday(today);
    LocalDate periodEnd = SettlementPeriod.weekSunday(today);

    SettlementRow settlement =
        settlements.findForPeriod(pharmacy.pharmacyId(), periodStart, periodEnd).orElse(null);

    if (settlement != null) {
      return settlementPeriodMap(settlement);
    }

    long gmvPaise = orderMetrics.gmvForPeriodPaise(pharmacy.pharmacyId(), periodStart, periodEnd);
    if (gmvPaise == 0L) {
      gmvPaise = orderMetrics.commissionLedger(pharmacy.pharmacyId()).gmvCurrentPeriodPaise();
    }
    var amounts = SettlementCalculator.compute(gmvPaise, pharmacy.commissionPct(), annualGmvYtd);

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period_label", SettlementPeriod.label(periodStart, periodEnd));
    m.put("gmv", paiseToRupees(gmvPaise));
    m.put("commission_earned", paiseToRupees(amounts.commissionEarnedPaise()));
    m.put("tcs_deducted", paiseToRupees(amounts.tcsDeductedPaise()));
    m.put("net_payable_to_pharmacy", paiseToRupees(amounts.netPaidPaise()));
    m.put("settlement_status", "PENDING_RELEASE");
    return m;
  }

  static Map<String, Object> settlementPeriodMap(SettlementRow settlement) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period_label", SettlementPeriod.label(settlement.periodStart(), settlement.periodEnd()));
    m.put("gmv", paiseToRupees(settlement.gmvPaise()));
    m.put("commission_earned", paiseToRupees(settlement.commissionEarnedPaise()));
    m.put("tcs_deducted", paiseToRupees(settlement.tcsDeductedPaise()));
    m.put("net_payable_to_pharmacy", paiseToRupees(settlement.netPaidPaise()));
    m.put("settlement_status", settlement.status());
    return m;
  }

  private Map<String, Object> bankSummary(UUID pharmacyId) {
    BankAccountRecord bank = profiles.findActiveBankAccount(pharmacyId).orElse(null);
    Map<String, Object> m = new LinkedHashMap<>();
    if (bank == null) {
      m.put("bank_account_masked", null);
      m.put("bank_account_verified", false);
      return m;
    }
    m.put("bank_account_masked", maskAccount(bank.accountNumberLast4()));
    m.put("bank_account_verified", "VERIFIED".equals(bank.verificationStatus()));
    return m;
  }

  private String lastSettlementDate(UUID pharmacyId) {
    return settlements
        .findLatestPaid(pharmacyId)
        .map(s -> s.paidAt() == null ? null : s.paidAt().atZone(IST).toLocalDate().toString())
        .orElse(null);
  }

  private static String nextSettlementDate(LocalDate today) {
    LocalDate nextMonday = SettlementPeriod.weekMonday(today).plusWeeks(1);
    return nextMonday.toString();
  }

  private AdminDetailRow requirePharmacy(UUID pharmacyId) {
    return pharmacies
        .findDetail(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private static void validateCommissionPct(BigDecimal commissionPct) {
    if (commissionPct == null) {
      throw new AppException("INVALID_COMMISSION_PCT", "commission_pct is required", 400);
    }
    BigDecimal scaled = scalePct(commissionPct);
    if (scaled.compareTo(MIN_PCT) < 0 || scaled.compareTo(MAX_PCT) > 0) {
      throw new AppException(
          "INVALID_COMMISSION_PCT", "Commission must be between 3.00% and 20.00%", 400);
    }
  }

  private void validateEffectiveFrom(LocalDate effectiveFrom) {
    if (effectiveFrom == null) {
      throw new AppException("EFFECTIVE_FROM_MUST_BE_FUTURE", "effective_from is required", 400);
    }
    LocalDate today = LocalDate.now(clock.withZone(IST));
    if (!effectiveFrom.isAfter(today)) {
      throw new AppException(
          "EFFECTIVE_FROM_MUST_BE_FUTURE", "effective_from must be tomorrow or later", 400);
    }
  }

  private static void validateReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    if (reason.length() > 500) {
      throw new AppException("REASON_REQUIRED", "reason must be at most 500 characters", 400);
    }
  }

  static BigDecimal scalePct(BigDecimal pct) {
    return pct.setScale(2, RoundingMode.HALF_UP);
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  private static String maskAccount(String last4) {
    return "XXXXXXXXXXXX" + last4;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  static void requireReadRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  static void requireWriteRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException(
          "FORBIDDEN", "Only admin_finance or admin_super may change commission", 403);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }
}
