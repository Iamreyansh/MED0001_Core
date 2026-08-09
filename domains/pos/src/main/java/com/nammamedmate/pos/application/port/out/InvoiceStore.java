package com.nammamedmate.pos.application.port.out;

import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.PaymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceStore {

  record InvoiceSettingsRow(String invoicePrefix) {}

  record InvoiceListRow(Invoice invoice, int itemsCount) {}

  record PeriodSummary(
      long billCount,
      long unitsSold,
      long grossRevenuePaise,
      long gstCollectedPaise,
      long creditOutstandingPaise) {}

  record PaymentModeAgg(String paymentMethod, long count, long amountPaise) {}

  record ChannelAgg(String channel, long revenuePaise) {}

  record ProductAgg(String productName, long revenuePaise, long units) {}

  InvoiceSettingsRow getOrCreateSettings(UUID pharmacyId);

  /** Atomically allocate next seq for pharmacy-year-month; returns 1-based sequence. */
  int nextSequence(UUID pharmacyId, int year, int month);

  Invoice insert(Invoice invoice);

  void insertItems(List<InvoiceItem> items);

  Optional<Invoice> findById(UUID pharmacyId, UUID invoiceId);

  /** Cross-pharmacy lookup for admin_finance / admin_support read paths. */
  Optional<Invoice> findByIdAny(UUID invoiceId);

  List<InvoiceItem> listItems(UUID invoiceId);

  List<InvoiceListRow> list(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String channel,
      String q,
      int limit,
      int offset);

  long count(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String channel,
      String q);

  List<InvoiceListRow> listSales(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q,
      String sort,
      String order,
      int limit,
      int offset);

  long countSales(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q);

  PeriodSummary periodSummary(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q);

  List<PaymentModeAgg> paymentModeMix(UUID pharmacyId, LocalDate fromDate, LocalDate toDate);

  List<ChannelAgg> channelRevenue(UUID pharmacyId, LocalDate fromDate, LocalDate toDate);

  List<ProductAgg> topProducts(UUID pharmacyId, LocalDate fromDate, LocalDate toDate, int limit);

  void markPaid(
      UUID pharmacyId,
      UUID invoiceId,
      PaymentStatus paymentStatus,
      String paymentReference,
      long amountPaidPaise,
      Instant updatedAt);
}
