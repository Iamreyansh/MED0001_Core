package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.Distributor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistributorStore {

  Optional<Distributor> findById(UUID pharmacyId, UUID distributorId);

  /** Includes soft-deleted rows (for FK / inactive checks). */
  Optional<Distributor> findByIdIncludingDeleted(UUID pharmacyId, UUID distributorId);

  Optional<Distributor> findActiveByPhone(UUID pharmacyId, String phone, UUID excludeId);

  Optional<Distributor> findActiveSystem(UUID pharmacyId);

  boolean isSystem(UUID pharmacyId, UUID distributorId);

  Distributor insert(Distributor distributor);

  Distributor insertSystem(Distributor distributor);

  Distributor update(Distributor distributor);

  void deactivate(UUID pharmacyId, UUID distributorId, Instant now);

  ListResult list(UUID pharmacyId, Boolean active, String q, int page, int limit);

  KpiRow kpi(UUID pharmacyId);

  /** Sum of STOCKED GRN line totals for one distributor (repayments deferred — always 0). */
  long outstandingPayablePaise(UUID pharmacyId, UUID distributorId);

  LocalDate lastPurchaseDate(UUID pharmacyId, UUID distributorId);

  record ListResult(List<Distributor> items, long total) {
    public ListResult {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record KpiRow(
      long distributorCount,
      long productsSourced,
      long outstandingPayablePaise,
      long onCreditCount) {}
}
