package com.nammamedmate.api.config;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.order.application.port.out.DeliveryInvoicePort;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** D12: ONLINE invoice + sales-ledger row on order DELIVERED, reusing POS invoice store. */
@Configuration
public class OrderOnlineInvoiceBridgeConfig {

  @Bean
  @Primary
  DeliveryInvoicePort onlineDeliveryInvoicePort(
      InvoiceStore invoices, JdbcTemplate jdbc, Clock clock) {
    return new DeliveryInvoicePort() {
      @Override
      public void onDelivered(Order order) {
        createOnlineInvoice(invoices, jdbc, clock, order);
      }
    };
  }

  static void createOnlineInvoice(
      InvoiceStore invoices, JdbcTemplate jdbc, Clock clock, Order order) {
    if (order == null || order.pharmacyId() == null) {
      return;
    }
    String ref = order.id().toString();
    List<UUID> existing =
        jdbc.query(
            """
            SELECT id FROM invoice
             WHERE pharmacy_id = ? AND payment_reference = ? AND channel = 'ONLINE'
            """,
            (rs, i) -> (UUID) rs.getObject("id"),
            order.pharmacyId(),
            ref);
    if (!existing.isEmpty()) {
      return;
    }

    Instant now = clock.instant();
    String name = "Customer";
    String phone = null;
    if (order.customerId() != null) {
      List<String[]> rows =
          jdbc.query(
              """
              SELECT COALESCE(name, 'Customer') AS name, phone
                FROM customers WHERE id = ? AND deleted_at IS NULL
              """,
              (rs, i) -> new String[] {rs.getString("name"), rs.getString("phone")},
              order.customerId());
      if (!rows.isEmpty()) {
        name = rows.getFirst()[0];
        phone = rows.getFirst()[1];
      }
    }

    var settings = invoices.getOrCreateSettings(order.pharmacyId());
    ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
    int year = zdt.getYear();
    int month = zdt.getMonthValue();
    int seq = invoices.nextSequence(order.pharmacyId(), year, month);
    String invoiceNumber =
        String.format(Locale.ROOT, "%s-%04d-%02d-%06d", settings.invoicePrefix(), year, month, seq);

    long subtotal = order.itemTotalPaise();
    long discount = order.couponDiscountPaise();
    long grand = Math.max(0L, subtotal - discount);
    UUID invoiceId = Ids.newId();
    List<InvoiceItem> items = new ArrayList<>();
    long gstTotal = 0L;
    for (OrderItemSnapshot line : order.items()) {
      long gst = MoneyMath.gstFromInclusive(line.lineTotalPaise(), 12);
      gstTotal += gst;
      items.add(
          new InvoiceItem(
              Ids.newId(),
              invoiceId,
              line.productId(),
              line.name(),
              null,
              null,
              null,
              null,
              null,
              line.quantity(),
              false,
              line.unitPricePaise(),
              12,
              line.lineTotalPaise(),
              gst,
              line.lineTotalPaise(),
              line.rxRequired(),
              now));
    }

    PaymentMethod method = mapMethod(order.paymentMethod());
    PaymentStatus payStatus =
        order.paymentMethod() == com.nammamedmate.order.domain.PaymentMethod.COD
            ? PaymentStatus.PENDING
            : PaymentStatus.PAID;
    String pdfUrl =
        "https://cdn.medmate.in/pharmacy/" + order.pharmacyId() + "/" + invoiceNumber + ".pdf";
    invoices.insert(
        new Invoice(
            invoiceId,
            order.pharmacyId(),
            invoiceNumber,
            null,
            InvoiceChannel.ONLINE,
            order.customerId(),
            name,
            phone,
            null,
            subtotal,
            discount,
            gstTotal,
            grand,
            method,
            payStatus,
            ref,
            grand,
            0L,
            Math.max(0L, subtotal - grand),
            InvoiceStatus.ACTIVE,
            pdfUrl,
            now));
    if (!items.isEmpty()) {
      invoices.insertItems(items);
    }
  }

  private static PaymentMethod mapMethod(com.nammamedmate.order.domain.PaymentMethod method) {
    if (method == null) {
      return PaymentMethod.UPI;
    }
    return switch (method) {
      case CARD -> PaymentMethod.CARD;
      case COD -> PaymentMethod.COD;
      case UPI, WALLET -> PaymentMethod.UPI;
    };
  }
}
