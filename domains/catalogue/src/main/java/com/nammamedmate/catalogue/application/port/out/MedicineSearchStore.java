package com.nammamedmate.catalogue.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicineSearchStore {

  record SearchHit(
      UUID medicineId,
      String name,
      String saltComposition,
      String manufacturer,
      String categoryName,
      String categorySlug,
      String form,
      BigDecimal packSize,
      String packUnit,
      String schedule,
      boolean rxOnly,
      long mrpPaise,
      double relevanceScore) {}

  record SearchPage(List<SearchHit> rows, long total) {
    public SearchPage {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record AutocompleteHit(UUID medicineId, String name, String manufacturer) {}

  record StockOffer(
      UUID medicineId,
      UUID pharmacyId,
      String pharmacyName,
      long pharmacyPricePaise,
      int stockQuantity,
      boolean inStock) {}

  record SubstituteHit(
      UUID medicineId,
      String name,
      String saltComposition,
      String manufacturer,
      String form,
      BigDecimal packSize,
      String schedule,
      boolean rxOnly,
      long mrpPaise) {}

  record AvailabilityHit(
      UUID medicineId,
      String name,
      boolean inStock,
      int stockQuantity,
      Long pharmacyPricePaise,
      boolean rxOnly) {}

  record PharmacyMasterHit(
      UUID medicineId,
      String name,
      String saltComposition,
      String manufacturer,
      String form,
      BigDecimal packSize,
      String schedule,
      boolean rxOnly,
      long masterMrpPaise,
      Long pharmacyPricePaise,
      Integer stockQuantity,
      UUID mappingId,
      boolean mapped,
      boolean visible) {}

  record PharmacyMasterPage(List<PharmacyMasterHit> rows, long total) {
    public PharmacyMasterPage {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  SearchPage search(
      String query,
      UUID categoryId,
      String schedule,
      Boolean rxOnly,
      boolean excludeBanned,
      int page,
      int limit);

  List<AutocompleteHit> autocomplete(String query, int limit);

  Optional<String> didYouMean(String query);

  List<StockOffer> bestOffers(
      List<UUID> medicineIds, UUID zoneId, UUID pharmacyId, boolean showOos);

  List<StockOffer> stockingOffers(UUID medicineId, UUID zoneId, boolean showOos);

  List<SubstituteHit> findSubstitutes(List<UUID> substituteIds);

  List<AvailabilityHit> checkAvailability(UUID pharmacyId, List<UUID> medicineIds);

  PharmacyMasterPage searchMasterForPharmacy(
      UUID pharmacyId, String query, boolean inStockOnly, int page, int limit);
}
