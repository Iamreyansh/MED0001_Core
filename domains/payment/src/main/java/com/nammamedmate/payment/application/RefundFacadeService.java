package com.nammamedmate.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort.RefundResult;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.RefundFinancePort;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.ListFilter;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.ListResult;
import com.nammamedmate.payment.application.port.out.RefundFinancePort.RefundRecord;
import com.nammamedmate.payment.application.port.out.RefundNotificationPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.RefundStatuses;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

/** EPIC-012 STORY-005 finance façade over shared refund rows + Cashfree + wallet + ledger. */
@Service
public class RefundFacadeService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final RefundFinancePort refunds;
  private final CashfreeGatewayPort cashfree;
  private final CustomerWalletPort wallets;
  private final FinancialLedgerWriterPort ledger;
  private final RefundNotificationPort notifications;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ProviderOperationStore providerOps;

  public RefundFacadeService(
      RefundFinancePort refunds,
      CashfreeGatewayPort cashfree,
      CustomerWalletPort wallets,
      FinancialLedgerWriterPort ledger,
      RefundNotificationPort notifications,
      Clock clock) {
    this(refunds, cashfree, wallets, ledger, notifications, clock, null, null);
  }

  public RefundFacadeService(
      RefundFinancePort refunds,
      CashfreeGatewayPort cashfree,
      CustomerWalletPort wallets,
      FinancialLedgerWriterPort ledger,
      RefundNotificationPort notifications,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager) {
    this(refunds, cashfree, wallets, ledger, notifications, clock, transactionManager, null);
  }

  @Autowired
  public RefundFacadeService(
      RefundFinancePort refunds,
      CashfreeGatewayPort cashfree,
      CustomerWalletPort wallets,
      FinancialLedgerWriterPort ledger,
      RefundNotificationPort notifications,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager,
      @Nullable ProviderOperationStore providerOps) {
    this.refunds = refunds;
    this.cashfree = cashfree;
    this.wallets = wallets;
    this.ledger = ledger;
    this.notifications = notifications;
    this.clock = clock;
    this.providerOps = providerOps;
    this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
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

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PagedResult listAdmin(
      MedmatePrincipal principal,
      String status,
      String refundTo,
      LocalDate from,
      Integer page,
      Integer limit) {
    requireFinanceRead(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    String storageStatus = parseStatusFilter(status);
    String storageRefundTo = parseRefundToFilter(refundTo);
    Instant createdFrom = from == null ? null : from.atStartOfDay(IST).toInstant();
    ListFilter filter =
        new ListFilter(
            storageStatus, storageRefundTo, createdFrom, pageLimit, (pageNum - 1) * pageLimit);
    ListResult result = refunds.list(filter);

    Instant dayStart = LocalDate.now(clock.withZone(IST)).atStartOfDay(IST).toInstant();
    Instant dayEnd = dayStart.plusSeconds(86400);
    Instant overdueBefore = clock.instant().minusSeconds(RefundStatuses.OVERDUE_HOURS * 3600L);
    KpiSnapshot kpi = refunds.kpis(dayStart, dayEnd, overdueBefore);

    List<Map<String, Object>> items = new ArrayList<>();
    for (RefundRecord row : result.refunds()) {
      items.add(toListItem(row, overdueBefore));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "kpi_chips",
        Map.of(
            "pending_count", kpi.pendingCount(),
            "pending_value", MoneyFormats.paiseToRupees(kpi.pendingValuePaise()),
            "processed_today", kpi.processedToday(),
            "failed_today", kpi.failedToday(),
            "overdue_count", kpi.overdueCount()));
    data.put("refunds", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getAdminDetail(MedmatePrincipal principal, UUID refundId) {
    requireFinanceRead(principal);
    RefundRecord row = requireRefund(refundId);
    return toDetail(row);
  }

  /**
   * Process PENDING refund: claim → Cashfree/wallet (outside TX) → finalize. Matches settlement TX
   * pattern so a provider accept is not rolled back with an uncommitted claim.
   */
  public Map<String, Object> process(MedmatePrincipal principal, UUID refundId, String notes) {
    requireFinanceWrite(principal);
    RefundRecord refund = requireRefund(refundId);
    if (!RefundStatuses.STORAGE_PENDING.equals(refund.status())) {
      throw new AppException("REFUND_ALREADY_PROCESSED", "Refund not in PENDING state", 409);
    }

    Instant now = clock.instant();
    Boolean claimed =
        inTx(() -> refunds.claimForProcess(refundId, principal.subject(), notes, now));
    if (!Boolean.TRUE.equals(claimed)) {
      RefundRecord current = requireRefund(refundId);
      if (!RefundStatuses.STORAGE_PENDING.equals(current.status())) {
        throw new AppException("REFUND_ALREADY_PROCESSED", "Refund not in PENDING state", 409);
      }
      throw new AppException("REFUND_ALREADY_PROCESSED", "Refund not in PENDING state", 409);
    }

    if (RefundStatuses.STORAGE_WALLET.equals(refund.refundTo())) {
      try {
        Map<String, Object> credited =
            wallets.systemCredit(
                refund.customerId(),
                refund.amountPaise(),
                "REFUND",
                refund.orderId().toString(),
                notes == null || notes.isBlank() ? "Refund" : notes.trim(),
                "finance-refund-" + refundId);
        UUID walletTxId = extractUuid(credited.get("transaction_id"));
        inTx(
            () -> {
              if (!refunds.markWalletCompleted(
                  refundId, walletTxId, principal.subject(), notes, now)) {
                throw new AppException(
                    "REFUND_ALREADY_PROCESSED", "Refund not in PENDING state", 409);
              }
              writeLedger(refund);
              notifications.refundCompleted(
                  refund.customerId(), refundId, refund.orderId(), refund.amountPaise());
            });
      } catch (AppException ex) {
        inTx(() -> refunds.markProcessFailed(refundId, ex.getMessage(), now));
        throw ex;
      } catch (RuntimeException ex) {
        inTx(() -> refunds.markProcessFailed(refundId, "wallet credit failed", now));
        throw new AppException("VALIDATION_ERROR", "Wallet credit failed", 422);
      }
      return processResponse(requireRefund(refundId), principal.subject());
    }

    String paymentId = refund.gatewayPaymentId();
    if (paymentId == null || paymentId.isBlank()) {
      inTx(() -> refunds.markProcessFailed(refundId, "missing cashfree payment id", now));
      throw new AppException(
          "CASHFREE_REFUND_FAILED", "Order has no Cashfree payment for SOURCE refund", 502);
    }

    try {
      String opKey = "refund:" + refundId;
      RefundResult rz = replayRefund(opKey, refund.amountPaise());
      if (rz == null) {
        if (providerOps != null) {
          providerOps.ensurePending("REFUND", opKey, "cashfree");
        }
        rz = cashfree.refund(paymentId, refund.amountPaise());
        if (providerOps != null) {
          providerOps.markSent("REFUND", opKey, rz.gatewayRefundId());
        }
      }
      final RefundResult gateway = rz;
      LocalDate expectedBy =
          addBusinessDays(
              LocalDate.now(clock.withZone(IST)), RefundStatuses.EXPECTED_BUSINESS_DAYS);
      try {
        inTx(
            () -> {
              if (!refunds.finalizeGatewayProcess(
                  refundId, gateway.gatewayRefundId(), expectedBy, now)) {
                throw new AppException(
                    "REFUND_ALREADY_PROCESSED", "Could not finalize refund process", 409);
              }
            });
      } catch (RuntimeException ex) {
        // Provider accepted — keep INITIATED + cashfree id for webhook reconcile (no FAILED)
        inTx(() -> refunds.attachGatewayRefundId(refundId, gateway.gatewayRefundId(), now));
        throw ex;
      }
    } catch (AppException ex) {
      if (ex.code().equals("REFUND_ALREADY_PROCESSED")) {
        throw ex;
      }
      inTx(() -> refunds.markProcessFailed(refundId, ex.getMessage(), now));
      if ("CASHFREE_REFUND_FAILED".equals(ex.code()) || "CASHFREE_ERROR".equals(ex.code())) {
        throw new AppException("CASHFREE_REFUND_FAILED", ex.getMessage(), 502);
      }
      throw ex;
    } catch (RuntimeException ex) {
      inTx(() -> refunds.markProcessFailed(refundId, "cashfree refund failed", now));
      throw new AppException("CASHFREE_REFUND_FAILED", "Failed to initiate Cashfree refund", 502);
    }

    return processResponse(requireRefund(refundId), principal.subject());
  }

  @Transactional(readOnly = true)
  public PagedResult listCustomer(MedmatePrincipal principal, Integer page, Integer limit) {
    requireCustomer(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    ListResult result =
        refunds.listForCustomer(principal.subject(), pageLimit, (pageNum - 1) * pageLimit);
    List<Map<String, Object>> items = new ArrayList<>();
    for (RefundRecord row : result.refunds()) {
      items.add(toCustomerItem(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refunds", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  /** Called from payment webhook {@code refund.processed}. */
  public Map<String, Object> completeFromWebhook(JsonNode root) {
    JsonNode entity = root.path("payload").path("refund").path("entity");
    String gatewayRefundId = text(entity, "id");
    if (gatewayRefundId == null) {
      Map<String, Object> ack = new LinkedHashMap<>();
      ack.put("event", "refund.processed");
      ack.put("gateway_payment_id", text(entity, "payment_id"));
      ack.put("processed", true);
      return ack;
    }
    Instant now = clock.instant();
    RefundRecord refund = refunds.findByGatewayRefundId(gatewayRefundId).orElse(null);
    if (refund == null) {
      Map<String, Object> ack = new LinkedHashMap<>();
      ack.put("event", "refund.processed");
      ack.put("gateway_payment_id", text(entity, "payment_id"));
      ack.put("processed", true);
      return ack;
    }
    if (RefundStatuses.STORAGE_PROCESSED.equals(refund.status())) {
      Map<String, Object> done = new LinkedHashMap<>();
      done.put("event", "refund.processed");
      done.put("refund_id", refund.id().toString());
      done.put("status", RefundStatuses.API_COMPLETED);
      done.put("processed", false);
      return done;
    }
    inTx(
        () -> {
          if (refunds.markCompleted(refund.id(), now)) {
            writeLedger(refund);
            notifications.refundCompleted(
                refund.customerId(), refund.id(), refund.orderId(), refund.amountPaise());
          }
        });
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("event", "refund.processed");
    data.put("refund_id", refund.id().toString());
    data.put("status", RefundStatuses.API_COMPLETED);
    data.put("processed", true);
    return data;
  }

  private void writeLedger(RefundRecord refund) {
    ledger.append(
        "REFUND",
        refund.id(),
        "REFUND",
        0L,
        refund.amountPaise(),
        "Customer refund",
        Map.of(
            "order_id",
            refund.orderId().toString(),
            "customer_id",
            refund.customerId() == null ? "" : refund.customerId().toString(),
            "refund_to",
            RefundStatuses.toApiRefundTo(refund.refundTo())));
  }

  private Map<String, Object> processResponse(RefundRecord row, UUID processedBy) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refund_id", row.id().toString());
    data.put("order_id", row.orderId().toString());
    data.put("refund_amount", MoneyFormats.paiseToRupees(row.amountPaise()));
    data.put("refund_to", RefundStatuses.toApiRefundTo(row.refundTo()));
    data.put("status", RefundStatuses.toApiStatus(row.status()));
    data.put("gateway_refund_id", row.gatewayRefundId());
    data.put("expected_by", row.expectedBy() == null ? null : row.expectedBy().toString());
    data.put(
        "processed_by",
        row.processedBy() == null ? processedBy.toString() : row.processedBy().toString());
    data.put("processed_at", row.processedAt() == null ? null : row.processedAt().toString());
    return data;
  }

  private Map<String, Object> toListItem(RefundRecord row, Instant overdueBefore) {
    String apiStatus = RefundStatuses.toApiStatus(row.status());
    boolean overdue =
        RefundStatuses.API_PENDING.equals(apiStatus)
            && row.createdAt() != null
            && row.createdAt().isBefore(overdueBefore);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("refund_id", row.id().toString());
    m.put("order_id", row.orderId().toString());
    m.put("order_number", row.orderNumber());
    m.put("customer_name", row.customerName() == null ? "" : row.customerName());
    m.put("customer_phone", row.customerPhone() == null ? "" : row.customerPhone());
    m.put("refund_amount", MoneyFormats.paiseToRupees(row.amountPaise()));
    m.put("payment_method", apiPaymentMethod(row.paymentMethod()));
    m.put("refund_to", RefundStatuses.toApiRefundTo(row.refundTo()));
    m.put("status", apiStatus);
    m.put("cancellation_reason", row.reason());
    m.put("is_overdue", overdue);
    m.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return m;
  }

  private Map<String, Object> toDetail(RefundRecord row) {
    long orderTotal = row.orderTotalPaise();
    long walletOriginal = row.walletAppliedPaise();
    long gatewayOriginal = Math.max(0L, orderTotal);
    long walletRefund =
        RefundStatuses.STORAGE_WALLET.equals(row.refundTo()) ? row.amountPaise() : 0L;
    long gatewayRefund =
        RefundStatuses.STORAGE_SOURCE.equals(row.refundTo()) ? row.amountPaise() : 0L;
    // Hybrid: if wallet applied on order and this is SOURCE line, wallet portion was separate row
    boolean isPartial = orderTotal > 0 && row.amountPaise() < orderTotal;
    Instant overdueBefore = clock.instant().minusSeconds(RefundStatuses.OVERDUE_HOURS * 3600L);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refund_id", row.id().toString());
    data.put("order_id", row.orderId().toString());
    data.put("order_number", row.orderNumber());
    data.put("order_total", MoneyFormats.paiseToRupees(orderTotal));
    data.put("refund_amount", MoneyFormats.paiseToRupees(row.amountPaise()));
    data.put("is_partial", isPartial);
    data.put("payment_method", apiPaymentMethod(row.paymentMethod()));
    data.put("wallet_portion_original", MoneyFormats.paiseToRupees(walletOriginal));
    data.put("gateway_portion_original", MoneyFormats.paiseToRupees(gatewayOriginal));
    data.put("wallet_refund_amount", MoneyFormats.paiseToRupees(walletRefund));
    data.put("gateway_refund_amount", MoneyFormats.paiseToRupees(gatewayRefund));
    data.put("refund_to", RefundStatuses.toApiRefundTo(row.refundTo()));
    data.put("status", RefundStatuses.toApiStatus(row.status()));
    data.put("cancellation_reason", row.reason());
    data.put("gateway_refund_id", row.gatewayRefundId());
    data.put("gateway_payment_id", row.gatewayPaymentId());
    data.put("expected_by", row.expectedBy() == null ? null : row.expectedBy().toString());
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("name", row.customerName() == null ? "" : row.customerName());
    customer.put("phone", row.customerPhone() == null ? "" : row.customerPhone());
    customer.put("email", row.customerEmail() == null ? "" : row.customerEmail());
    data.put("customer", customer);
    data.put("notes", row.notes());
    data.put("auto_processed", row.autoProcessed());
    data.put(
        "is_overdue",
        RefundStatuses.STORAGE_PENDING.equals(row.status())
            && row.createdAt() != null
            && row.createdAt().isBefore(overdueBefore));
    data.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    data.put("processed_at", row.processedAt() == null ? null : row.processedAt().toString());
    return data;
  }

  private Map<String, Object> toCustomerItem(RefundRecord row) {
    String apiStatus = RefundStatuses.toApiStatus(row.status());
    String apiTo = RefundStatuses.toApiRefundTo(row.refundTo());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("refund_id", row.id().toString());
    m.put("order_id", row.orderId().toString());
    m.put("order_number", row.orderNumber());
    m.put("amount", MoneyFormats.paiseToRupees(row.amountPaise()));
    m.put("refund_to", apiTo);
    m.put("status", apiStatus);
    m.put("expected_by", row.expectedBy() == null ? null : row.expectedBy().toString());
    m.put("message", RefundStatuses.customerMessage(apiStatus, apiTo));
    m.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return m;
  }

  private RefundRecord requireRefund(UUID refundId) {
    if (refundId == null) {
      throw new AppException("REFUND_NOT_FOUND", "refund_id does not exist", 404);
    }
    return refunds
        .findById(refundId)
        .orElseThrow(() -> new AppException("REFUND_NOT_FOUND", "refund_id does not exist", 404));
  }

  private static String parseStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return RefundStatuses.toStorageStatusFilter(status);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid refund status filter", 400);
    }
  }

  private static String parseRefundToFilter(String refundTo) {
    if (refundTo == null || refundTo.isBlank()) {
      return null;
    }
    try {
      return RefundStatuses.toStorageRefundToFilter(refundTo);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid refund_to filter", 400);
    }
  }

  static void requireFinanceRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_SUPPORT) {
      throw new AppException("FORBIDDEN", "admin finance/support required", 403);
    }
  }

  static void requireFinanceWrite(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "admin_finance or admin_super required", 403);
    }
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "customer role required", 403);
    }
  }

  private static String apiPaymentMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      return "UPI";
    }
    String n = raw.trim().toUpperCase(Locale.ROOT);
    if ("WALLET".equals(n)) {
      return "WALLET_ONLY";
    }
    return n;
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

  private RefundResult replayRefund(String opKey, long amountPaise) {
    if (providerOps == null) {
      return null;
    }
    return providerOps
        .find("REFUND", opKey)
        .filter(ProviderOperationStore.Operation::hasProviderRef)
        .map(op -> new RefundResult(op.providerRef(), amountPaise))
        .orElse(null);
  }

  private static UUID extractUuid(Object raw) {
    if (raw instanceof UUID u) {
      return u;
    }
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw.toString());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText();
    return v.isBlank() ? null : v;
  }

  /** Visible for tests — IST zone. */
  static ZoneOffset istOffset() {
    return IST.getRules().getOffset(Instant.now());
  }
}
