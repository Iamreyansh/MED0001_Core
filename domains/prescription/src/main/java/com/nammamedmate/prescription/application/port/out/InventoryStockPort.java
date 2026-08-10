package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface InventoryStockPort {

  record StockInfo(boolean inStock, int stockQty, long unitPricePaise) {}

  Optional<StockInfo> findByName(UUID pharmacyId, String medicineName);
}
