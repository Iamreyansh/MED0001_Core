package com.nammamedmate.api.config;

import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.AdminCreditCommand;
import com.nammamedmate.customer.application.WalletService.TxPage;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.order.application.OrderPlacementService;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.payment.application.PaymentService;
import com.nammamedmate.payment.application.port.out.CustomerWalletPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import com.nammamedmate.payment.application.port.out.WalletPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridges for EPIC-012 payment domain: wallet, order lookup, order status advance.
 */
@Configuration
public class PaymentOrderBridgeConfig {

  @Bean
  @Primary
  RazorpayPaymentPort orderRazorpayFromPaymentDomain(
      RazorpayGatewayPort gateway, @Lazy PaymentService payments) {
    return new RazorpayPaymentPort() {
      @Override
      public CreateOrderResult createOrder(UUID orderId, long amountPaise) {
        var created = gateway.createOrder(orderId, amountPaise);
        return new CreateOrderResult(created.razorpayOrderId(), created.amountPaise());
      }

      @Override
      public boolean verifyPaymentSignature(
          String razorpayOrderId, String paymentId, String signature) {
        return gateway.verifyPaymentSignature(razorpayOrderId, paymentId, signature);
      }

      @Override
      public String signPayment(String razorpayOrderId, String paymentId) {
        return gateway.signPayment(razorpayOrderId, paymentId);
      }

      @Override
      public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
        return gateway.verifyWebhookSignature(signatureHeader, rawBody);
      }

      @Override
      public RefundResult refund(String razorpayPaymentId, long amountPaise) {
        var refunded = gateway.refund(razorpayPaymentId, amountPaise);
        return new RefundResult(refunded.razorpayRefundId(), refunded.amountPaise());
      }

      @Override
      public Map<String, Object> handleWebhook(String signatureHeader, byte[] rawBody) {
        return payments.handleWebhook(signatureHeader, rawBody);
      }
    };
  }

  @Bean
  @Primary
  WalletPort paymentWalletPort(WalletService walletService, FinancialLedgerWriterPort ledger) {
    return (customerId, orderId, amountPaise, description) -> {
      Map<String, Object> result =
          walletService.debitForOrder(customerId, orderId, amountPaise, description);
      long debitPaise = 0L;
      Object debited = result.get("amount_debited");
      if (debited instanceof BigDecimal bd) {
        debitPaise = bd.movePointRight(2).longValueExact();
      } else if (debited instanceof Number n) {
        debitPaise = BigDecimal.valueOf(n.doubleValue()).movePointRight(2).longValue();
      }
      if (debitPaise > 0
          && ledger != null
          && !Boolean.TRUE.equals(result.get("already_processed"))) {
        Object tx = result.get("transaction_id");
        UUID txId =
            tx instanceof UUID u
                ? u
                : tx != null ? UUID.fromString(tx.toString()) : UUID.randomUUID();
        ledger.append(
            "WALLET_DEBIT",
            txId,
            "WALLET",
            0L,
            debitPaise,
            description == null || description.isBlank() ? "Payment wallet debit" : description,
            Map.of(
                "customer_id",
                customerId == null ? "" : customerId.toString(),
                "order_id",
                orderId == null ? "" : orderId.toString()));
      }
      return debitPaise;
    };
  }

  @Bean
  @Primary
  CustomerWalletPort customerWalletPort(
      WalletService walletService, FinancialLedgerWriterPort ledger) {
    return new CustomerWalletPort() {
      @Override
      public Map<String, Object> debit(
          UUID customerId, UUID orderId, long amountPaise, String idempotencyKey, String note) {
        return walletService.debitStrict(customerId, orderId, amountPaise, idempotencyKey, note);
      }

      @Override
      public Map<String, Object> systemCredit(
          UUID customerId,
          long amountPaise,
          String reason,
          String referenceId,
          String note,
          String idempotencyKey) {
        Map<String, Object> result =
            walletService.systemCredit(
                customerId, amountPaise, note, referenceId, idempotencyKey, reason);
        appendWalletCreditLedger(ledger, customerId, amountPaise, reason, referenceId, result);
        return result;
      }

      @Override
      public Map<String, Object> adminCredit(
          UUID adminId,
          UUID customerId,
          long amountPaise,
          String reason,
          String note,
          String referenceId,
          String idempotencyKey) {
        MedmatePrincipal admin =
            new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "jti");
        BigDecimal rupees =
            BigDecimal.valueOf(amountPaise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        return walletService.adminCredit(
            admin,
            customerId,
            new AdminCreditCommand(rupees, reason, note, referenceId, idempotencyKey));
      }

      @Override
      public Map<String, Object> balance(UUID customerId) {
        return walletService.getBalanceForCustomer(customerId);
      }

      @Override
      public TransactionsPage transactions(
          UUID customerId, Integer page, Integer limit, String type) {
        TxPage txPage = walletService.listTransactionsForCustomer(customerId, page, limit, type);
        PaginationMeta meta = txPage.meta();
        return new TransactionsPage(txPage.data(), meta.total(), meta.page(), meta.limit());
      }
    };
  }

  private static void appendWalletCreditLedger(
      FinancialLedgerWriterPort ledger,
      UUID customerId,
      long amountPaise,
      String reason,
      String referenceId,
      Map<String, Object> result) {
    if (ledger == null
        || amountPaise <= 0
        || Boolean.TRUE.equals(result.get("already_processed"))) {
      return;
    }
    Object tx = result.get("transaction_id");
    UUID txId =
        tx instanceof UUID u ? u : tx != null ? UUID.fromString(tx.toString()) : UUID.randomUUID();
    ledger.append(
        "WALLET_CREDIT",
        txId,
        "WALLET",
        amountPaise,
        0L,
        reason == null || reason.isBlank() ? "Wallet credit" : reason,
        Map.of(
            "customer_id",
            customerId == null ? "" : customerId.toString(),
            "reference_id",
            referenceId == null ? "" : referenceId));
  }

  @Bean
  @Primary
  OrderLookupPort jdbcOrderLookupPort(JdbcTemplate jdbc) {
    return orderId -> {
      List<OrderLookupPort.OrderSnapshot> rows =
          jdbc.query(
              """
              SELECT id, customer_id, payment_method, total_payable_paise, wallet_applied_paise,
                     status
              FROM orders
              WHERE id = ? AND deleted_at IS NULL
              LIMIT 1
              """,
              (rs, i) ->
                  new OrderLookupPort.OrderSnapshot(
                      (UUID) rs.getObject("id"),
                      (UUID) rs.getObject("customer_id"),
                      rs.getString("payment_method"),
                      rs.getLong("total_payable_paise"),
                      rs.getLong("wallet_applied_paise"),
                      rs.getString("status")),
              orderId);
      return rows.stream().findFirst();
    };
  }

  @Bean
  @Primary
  OrderPaymentStatusPort orderPaymentStatusPort(@Lazy OrderPlacementService orderPlacement) {
    return new OrderPaymentStatusPort() {
      @Override
      public void onCaptured(UUID orderId, String razorpayPaymentId) {
        orderPlacement.applyExternalPaymentCapture(orderId, razorpayPaymentId);
      }

      @Override
      public void onFailed(UUID orderId, String reason) {
        orderPlacement.applyExternalPaymentFailure(orderId, reason);
      }
    };
  }
}
