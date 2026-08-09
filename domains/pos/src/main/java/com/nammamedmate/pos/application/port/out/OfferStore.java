package com.nammamedmate.pos.application.port.out;

import com.nammamedmate.pos.domain.OfferRedemption;
import com.nammamedmate.pos.domain.PharmacyOffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OfferStore {

  record ListPage(List<PharmacyOffer> items, long total) {
    public ListPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record Kpi(int activeCount, long totalRedemptions) {}

  PharmacyOffer insert(PharmacyOffer offer);

  Optional<PharmacyOffer> findById(UUID pharmacyId, UUID offerId);

  Optional<PharmacyOffer> findByCoupon(UUID pharmacyId, String couponCode);

  boolean couponExists(UUID pharmacyId, String couponCode, UUID excludeOfferId);

  PharmacyOffer update(PharmacyOffer offer);

  void hardDelete(UUID pharmacyId, UUID offerId);

  ListPage list(UUID pharmacyId, String statusFilter, LocalDate today, int page, int limit);

  Kpi kpi(UUID pharmacyId, LocalDate today);

  List<PharmacyOffer> listActiveCounterOffers(UUID pharmacyId, LocalDate today);

  Map<UUID, UUID> productCategoryIds(UUID pharmacyId, List<UUID> productIds);

  Map<UUID, String> categoryNames(List<UUID> categoryIds);

  void insertRedemption(OfferRedemption redemption);

  void incrementRedemptions(UUID offerId, Instant updatedAt);
}
