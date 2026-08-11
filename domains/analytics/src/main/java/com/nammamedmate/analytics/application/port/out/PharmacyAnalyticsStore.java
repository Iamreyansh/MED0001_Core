package com.nammamedmate.analytics.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence for EPIC-016 STORY-004 pharmacy analytics. */
public interface PharmacyAnalyticsStore {

  record FinancialTotals(
      long netRevenuePaise,
      long cogsPaise,
      long grossProfitPaise,
      long unitsSold,
      long netGstPaise,
      boolean cogsIncomplete) {}

  record TopItem(UUID productId, String name, long unitsSold, long revenuePaise) {}

  record ChannelTotals(long onlineRevenuePaise, long counterRevenuePaise) {}

  record PaymentMixRow(String method, long revenuePaise) {}

  record SaleRow(
      UUID saleId,
      String invoiceNumber,
      Instant saleDate,
      String channel,
      String customerName,
      int itemsCount,
      long subtotalPaise,
      long gstPaise,
      long totalPaise,
      String paymentMethod,
      String status) {}

  record SaleTotals(long totalSales, long totalRevenuePaise, long totalGstPaise) {}

  record ProductRow(
      UUID productId,
      String name,
      String category,
      long unitsSold,
      long revenuePaise,
      long cogsPaise,
      long profitPaise,
      BigDecimal marginPct,
      int stockRemaining,
      boolean deadStockFlag,
      boolean cogsMissing) {}

  record GstSlab(
      int slabPct,
      long taxableValuePaise,
      long outputGstPaise,
      long inputItcPaise,
      long netPaise) {}

  record DayBookRow(
      LocalDate date,
      String type,
      String reference,
      String description,
      long debitPaise,
      long creditPaise) {}

  record AccountsData(
      long revenuePaise,
      long cogsPaise,
      long grossProfitPaise,
      long operatingExpensesPaise,
      long outputGstPaise,
      long inputItcPaise,
      long cashCollectedPaise,
      long digitalCollectedPaise,
      long totalPurchasesPaise,
      long gstOnPurchasesPaise,
      boolean cogsIncomplete,
      List<GstSlab> slabs,
      List<DayBookRow> dayBook) {
    public AccountsData {
      slabs = List.copyOf(slabs);
      dayBook = List.copyOf(dayBook);
    }
  }

  FinancialTotals financials(UUID pharmacyId, Instant fromInclusive, Instant toExclusive);

  List<TopItem> topItems(UUID pharmacyId, Instant fromInclusive, Instant toExclusive, int limit);

  ChannelTotals channelTotals(UUID pharmacyId, Instant fromInclusive, Instant toExclusive);

  List<PaymentMixRow> paymentMix(UUID pharmacyId, Instant fromInclusive, Instant toExclusive);

  SaleTotals saleTotals(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String channel,
      String paymentMethod);

  List<SaleRow> sales(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String channel,
      String paymentMethod,
      int offset,
      int limit);

  long countProducts(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive, boolean deadStockOnly);

  List<ProductRow> products(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String sort,
      String order,
      boolean deadStockOnly,
      int offset,
      int limit);

  AccountsData accounts(UUID pharmacyId, Instant fromInclusive, Instant toExclusive);

  Set<String> favoriteReportIds(UUID pharmacyId);

  void setFavorite(UUID pharmacyId, String reportId, boolean favorite);

  /** Build tabular rows for a catalogue report (inline or export). */
  List<List<Object>> reportRows(
      UUID pharmacyId, String reportId, Instant fromInclusive, Instant toExclusive);

  void refreshDailySnapshots(LocalDate fromInclusive, LocalDate toInclusive);

  /** BR7: mark dead_stock when no units sold in 90d and stock &gt; 0. */
  void refreshDeadStockFlags(LocalDate asOfDate);
}
