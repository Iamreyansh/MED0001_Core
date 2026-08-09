package com.nammamedmate.payment.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EPIC-012 STORY-002 façade over customer wallet + financial ledger. */
@Service
public class WalletFacadeService {

  private final CustomerWalletPort wallets;
  private final FinancialLedgerWriterPort ledger;

  public WalletFacadeService(CustomerWalletPort wallets, FinancialLedgerWriterPort ledger) {
    this.wallets = wallets;
    this.ledger = ledger;
  }

  @Transactional
  public Map<String, Object> debit(
      UUID customerId, Object amount, UUID orderId, String idempotencyKey) {
    if (customerId == null) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    long amountPaise = MoneyFormats.parsePositiveRupeesToPaise(amount);
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "idempotency_key is required", 400);
    }
    String note =
        orderId == null
            ? "Auto-applied at checkout"
            : "Auto-applied at checkout for order " + orderId;
    Map<String, Object> result =
        wallets.debit(customerId, orderId, amountPaise, idempotencyKey.trim(), note);
    boolean already = Boolean.TRUE.equals(result.get("already_processed"));
    if (!already) {
      UUID txId = asUuid(result.get("transaction_id"));
      ledger.append(
          "WALLET_DEBIT",
          txId,
          "WALLET",
          0L,
          amountPaise,
          note,
          Map.of(
              "customer_id", customerId.toString(),
              "order_id", orderId == null ? "" : orderId.toString(),
              "idempotency_key", idempotencyKey.trim()));
    }
    return result;
  }

  @Transactional
  public Map<String, Object> credit(
      MedmatePrincipal principal,
      UUID customerId,
      Object amount,
      String reason,
      String referenceId,
      String note) {
    if (customerId == null) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    long amountPaise = MoneyFormats.parsePositiveRupeesToPaise(amount);
    if (reason == null || reason.isBlank()) {
      throw new AppException("INVALID_REASON", "reason is required", 422);
    }
    String noteText = note == null || note.isBlank() ? "Wallet credit" : note.trim();
    String idempotencyKey = creditIdempotencyKey(customerId, reason, referenceId);
    String requestReason = reason.trim().toUpperCase();

    Map<String, Object> result;
    if (isAdmin(principal)) {
      result =
          wallets.adminCredit(
              principal.subject(),
              customerId,
              amountPaise,
              mapAdminReason(requestReason),
              noteText,
              referenceId,
              idempotencyKey);
    } else {
      result =
          wallets.systemCredit(
              customerId, amountPaise, requestReason, referenceId, noteText, idempotencyKey);
    }

    UUID txId = asUuid(result.get("transaction_id"));
    boolean already = Boolean.TRUE.equals(result.get("already_processed"));
    if (!already) {
      ledger.append(
          "WALLET_CREDIT",
          txId,
          "WALLET",
          amountPaise,
          0L,
          noteText,
          Map.of(
              "customer_id",
              customerId.toString(),
              "reason",
              requestReason,
              "reference_id",
              referenceId == null ? "" : referenceId));
    }

    Map<String, Object> shaped = new LinkedHashMap<>(result);
    shaped.put("reason", requestReason);
    shaped.putIfAbsent("amount", MoneyFormats.paiseToRupees(amountPaise));
    if (shaped.get("note") == null) {
      shaped.put("note", noteText);
    }
    if (shaped.get("reference_id") == null && referenceId != null) {
      shaped.put("reference_id", referenceId);
    }
    return shaped;
  }

  public Map<String, Object> balance(UUID customerId) {
    return wallets.balance(customerId);
  }

  public CustomerWalletPort.TransactionsPage transactions(
      UUID customerId, Integer page, Integer limit, String type) {
    return wallets.transactions(customerId, page, limit, type);
  }

  private static boolean isAdmin(MedmatePrincipal principal) {
    return canAdminCredit(principal);
  }

  /** Story roles: admin_finance, admin_support, admin_super — not all admin_*. */
  public static boolean canAdminCredit(MedmatePrincipal principal) {
    if (principal == null) {
      return false;
    }
    return principal.role() == AuthRole.ADMIN_FINANCE
        || principal.role() == AuthRole.ADMIN_SUPPORT
        || principal.role() == AuthRole.ADMIN_SUPER;
  }

  /** Map story admin aliases while keeping legacy GOODWILL/PROMOTIONAL/REFUND. */
  private static String mapAdminReason(String reason) {
    return switch (reason) {
      case "ADMIN_CREDIT" -> "GOODWILL";
      case "CASHBACK" -> "PROMOTIONAL";
      default -> reason;
    };
  }

  private static String creditIdempotencyKey(UUID customerId, String reason, String referenceId) {
    if (referenceId != null && !referenceId.isBlank()) {
      return "wallet-credit:"
          + customerId
          + ":"
          + reason.trim().toUpperCase()
          + ":"
          + referenceId.trim();
    }
    return "wallet-credit:" + customerId + ":" + reason.trim().toUpperCase() + ":" + Ids.newId();
  }

  private static UUID asUuid(Object value) {
    if (value instanceof UUID u) {
      return u;
    }
    if (value != null) {
      return UUID.fromString(value.toString());
    }
    return Ids.newId();
  }
}
