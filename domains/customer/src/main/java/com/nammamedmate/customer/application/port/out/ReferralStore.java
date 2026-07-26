package com.nammamedmate.customer.application.port.out;

import com.nammamedmate.customer.domain.ReferralEventStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReferralStore {

  Optional<ReferralRecord> findByCustomerId(UUID customerId);

  Optional<ReferralRecord> findByCode(String referralCode);

  Optional<ReferralRecord> lockByCustomerId(UUID customerId);

  ReferralRecord insert(ReferralRecord record);

  ReferralRecord update(ReferralRecord record);

  boolean codeExists(String referralCode);

  ReferralEventRecord insertEvent(ReferralEventRecord event);

  Optional<ReferralEventRecord> findEventByReferee(UUID refereeCustomerId);

  Optional<ReferralEventRecord> lockEventById(UUID eventId);

  ReferralEventRecord updateEvent(ReferralEventRecord event);

  long countEventsByReferrerAndStatus(UUID referrerCustomerId, ReferralEventStatus status);

  record ReferralRecord(
      UUID id,
      UUID customerId,
      String referralCode,
      int totalReferrals,
      int convertedReferrals,
      long totalEarnedPaise,
      Instant createdAt) {}

  record ReferralEventRecord(
      UUID id,
      UUID refereeCustomerId,
      UUID referrerCustomerId,
      String referralCode,
      ReferralEventStatus status,
      UUID firstOrderId,
      long rewardAmountPaise,
      Instant refereeRewardedAt,
      Instant referrerRewardedAt,
      Instant createdAt,
      Instant updatedAt) {}
}
