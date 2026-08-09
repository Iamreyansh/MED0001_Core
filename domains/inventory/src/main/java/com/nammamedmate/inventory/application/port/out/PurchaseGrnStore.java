package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseGrnStore {

  record ListFilter(
      UUID pharmacyId,
      GrnStatus status,
      UUID distributorId,
      LocalDate fromDate,
      LocalDate toDate,
      String q,
      int page,
      int limit) {}

  record GrnListRow(
      UUID grnId,
      String distributorName,
      String invoiceNumber,
      LocalDate invoiceDate,
      int lineCount,
      long taxableAmountPaise,
      long gstAmountPaise,
      long totalPaise,
      GrnStatus status,
      Instant createdAt) {}

  record ListResult(List<GrnListRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record KpiRow(long purchasesThisMonth, long inputGstCreditThisMonthPaise, long totalGrns) {}

  record ItemWithProduct(PurchaseGrnItem item, String productName) {}

  PurchaseGrn insert(PurchaseGrn grn);

  Optional<PurchaseGrn> findById(UUID pharmacyId, UUID grnId);

  boolean invoiceExists(UUID pharmacyId, UUID distributorId, String invoiceNumber);

  ListResult list(ListFilter filter);

  KpiRow kpi(UUID pharmacyId, LocalDate monthStart, LocalDate monthEndExclusive);

  PurchaseGrn updateStatus(
      UUID grnId, GrnStatus status, Instant stockedAt, UUID stockedBy, Instant updatedAt);

  PurchaseGrn updateImportUnmatched(UUID grnId, String importUnmatchedJson, Instant updatedAt);

  PurchaseGrnItem insertItem(PurchaseGrnItem item);

  Optional<PurchaseGrnItem> findItem(UUID pharmacyId, UUID grnId, UUID itemId);

  PurchaseGrnItem updateItem(PurchaseGrnItem item);

  boolean deleteItem(UUID pharmacyId, UUID grnId, UUID itemId);

  List<ItemWithProduct> listItems(UUID pharmacyId, UUID grnId);

  int countItems(UUID pharmacyId, UUID grnId);

  String distributorFirmName(UUID pharmacyId, UUID distributorId);
}
