package com.nammamedmate.inventory.application.port.out;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface InventoryExcelExporter {

  byte[] export(List<Map<String, Object>> products);
}
