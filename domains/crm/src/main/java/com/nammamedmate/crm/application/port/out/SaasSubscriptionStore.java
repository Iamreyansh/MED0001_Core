package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.SaasSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasSubscriptionStore {

  Optional<SaasSubscription> findByAccountId(UUID accountId);

  Optional<SaasSubscription> findById(UUID id);

  void insert(SaasSubscription sub);

  void update(SaasSubscription sub);

  List<SaasSubscription> findDueForAutoRenew(Instant now, Instant windowEnd);

  List<SaasSubscription> findPastDueExpired(Instant graceCutoff);

  List<SaasSubscription> findTrialsEnding(Instant now);

  List<SaasSubscription> findCancelsDue(Instant now);

  List<SaasSubscription> findOverridesExpired(Instant now);

  void updateAccountDenorm(UUID accountId, String planName, String status, Instant updatedAt);

  Optional<UUID> findPharmacyId(UUID accountId);
}
