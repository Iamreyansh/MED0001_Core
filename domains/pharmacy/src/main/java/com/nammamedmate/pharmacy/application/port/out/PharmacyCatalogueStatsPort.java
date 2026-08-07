package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** Catalogue SKU counts for admin pharmacy detail (EPIC-005 / pharmacy catalogue mapping). */
public interface PharmacyCatalogueStatsPort {

  record CatalogueStats(int mappedSkus, int inStockSkus, int outOfStockSkus) {}

  CatalogueStats catalogueStats(UUID pharmacyId);
}
