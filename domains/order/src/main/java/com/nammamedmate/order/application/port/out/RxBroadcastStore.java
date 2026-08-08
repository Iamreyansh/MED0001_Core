package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.RxBroadcast;
import com.nammamedmate.order.domain.RxBroadcastPharmacy;
import com.nammamedmate.order.domain.RxBroadcastStatus;
import com.nammamedmate.order.domain.RxPharmacySlotStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RxBroadcastStore {

  void insert(RxBroadcast broadcast, List<RxBroadcastPharmacy> pharmacies);

  Optional<RxBroadcast> findById(UUID broadcastId);

  Optional<RxBroadcast> findByIdForCustomer(UUID broadcastId, UUID customerId);

  List<RxBroadcastPharmacy> listPharmacies(UUID broadcastId);

  Optional<RxBroadcastPharmacy> findPharmacySlot(UUID broadcastId, UUID pharmacyId);

  void updatePharmacySlot(RxBroadcastPharmacy slot);

  void markSelected(UUID broadcastId, UUID pharmacyId, UUID cartId);

  /** NOTIFIED/REVIEWING past deadline → EXPIRED. Returns rows updated. */
  int expirePharmacySlots(Instant now);

  /** ACTIVE broadcasts past expires_at → EXPIRED. Returns expired rows (for notify). */
  List<RxBroadcast> expireBroadcasts(Instant now);

  List<RxBroadcastPharmacy> listPendingForPharmacy(UUID pharmacyId);

  int countQuoted(UUID broadcastId);

  void updateBroadcastStatus(UUID broadcastId, RxBroadcastStatus status);

  void updatePharmacyStatus(UUID slotId, RxPharmacySlotStatus status);
}
