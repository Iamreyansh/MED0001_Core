package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.PoSentChannel;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderStore {

  record ListFilter(
      UUID pharmacyId, PurchaseOrderStatus status, UUID distributorId, int page, int limit) {}

  record PoListRow(
      UUID poId,
      String poNumber,
      String distributorName,
      int itemsCount,
      long estimatedTotalPaise,
      PurchaseOrderStatus status,
      Instant createdAt,
      Instant sentAt) {}

  record ListResult(List<PoListRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record ItemWithProduct(PurchaseOrderItem item, String productName, long mrpPaise, int gstPct) {}

  PurchaseOrder insert(PurchaseOrder po);

  PurchaseOrderItem insertItem(PurchaseOrderItem item);

  Optional<PurchaseOrder> findById(UUID pharmacyId, UUID poId);

  ListResult list(ListFilter filter);

  long countOpen(UUID pharmacyId);

  int nextSequence(UUID pharmacyId, YearMonth yearMonth);

  List<ItemWithProduct> listItems(UUID pharmacyId, UUID poId);

  int countItems(UUID pharmacyId, UUID poId);

  long estimatedTotalPaise(UUID pharmacyId, UUID poId);

  boolean deleteItem(UUID pharmacyId, UUID poId, UUID itemId);

  Optional<PurchaseOrderItem> findItem(UUID pharmacyId, UUID poId, UUID itemId);

  PurchaseOrderItem updateItemQuantity(UUID itemId, int quantity);

  PurchaseOrder update(
      UUID poId,
      PurchaseOrderStatus status,
      Instant sentAt,
      PoSentChannel channel,
      UUID grnId,
      Instant updatedAt);

  void softCancel(UUID pharmacyId, UUID poId, Instant now);
}
