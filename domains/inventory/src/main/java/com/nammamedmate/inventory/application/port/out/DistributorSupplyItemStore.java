package com.nammamedmate.inventory.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistributorSupplyItemStore {

  void upsertFromGrn(
      UUID pharmacyId,
      UUID distributorId,
      UUID productId,
      long purchasePricePaise,
      String schemeDescription,
      Instant purchasedAt);

  ListResult listByDistributor(UUID pharmacyId, UUID distributorId, String q, int page, int limit);

  PriceCompareResult priceCompare(
      UUID pharmacyId, boolean onlyMultiSource, String q, int page, int limit);

  /**
   * @return empty if supply row missing; previousPreferredId may be null
   */
  Optional<SetPreferredResult> setPreferred(
      UUID pharmacyId, UUID distributorId, UUID productId, Instant now);

  record SetPreferredResult(UUID previousPreferredId) {}

  record SupplyRow(
      UUID productId,
      String productName,
      String manufacturer,
      long purchasePricePaise,
      String schemeDescription,
      long mrpPaise,
      boolean preferredSource,
      int priceRank) {}

  record ListResult(List<SupplyRow> items, long total) {
    public ListResult {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record PriceOffer(
      UUID distributorId,
      String distributorName,
      long purchasePricePaise,
      String schemeDescription,
      long mrpPaise,
      boolean preferredSource,
      int priceRank) {}

  record PriceProduct(
      UUID productId, String productName, String manufacturer, List<PriceOffer> offers) {
    public PriceProduct {
      offers = offers == null ? List.of() : List.copyOf(offers);
    }
  }

  record PriceCompareResult(List<PriceProduct> products, long total) {
    public PriceCompareResult {
      products = products == null ? List.of() : List.copyOf(products);
    }
  }
}
