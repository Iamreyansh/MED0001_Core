package com.nammamedmate.customer.application.port.out;

import com.nammamedmate.customer.domain.ReferralEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralStore {

  /** Fixed singleton id for referral_program_settings (V094). */
  UUID PROGRAM_SETTINGS_ID = UUID.fromString("00000000-0000-4000-8000-000000000013");

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

  ProgramSettingsRecord getProgramSettings();

  ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord settings);

  void insertShareEvent(UUID id, UUID customerId, String channel, Instant createdAt);

  long countShareEvents(UUID customerId);

  AdminOverviewChips chips();

  List<TopReferrerRow> topReferrers(int limit);

  List<AdminReferralRow> listAdminReferrals(
      ReferralEventStatus statusFilter, int limit, int offset);

  long countAdminReferrals(ReferralEventStatus statusFilter);

  record ReferralRecord(
      UUID id,
      UUID customerId,
      String referralCode,
      int totalReferrals,
      int convertedReferrals,
      long totalEarnedPaise,
      Instant createdAt) {}

  /**
   * {@code rewardAmountPaise} = referrer credit snapshot; {@code refereeRewardAmountPaise} =
   * referee credit snapshot (both set at apply from program settings).
   */
  record ReferralEventRecord(
      UUID id,
      UUID refereeCustomerId,
      UUID referrerCustomerId,
      String referralCode,
      ReferralEventStatus status,
      UUID firstOrderId,
      long rewardAmountPaise,
      long refereeRewardAmountPaise,
      Instant refereeRewardedAt,
      Instant referrerRewardedAt,
      Instant createdAt,
      Instant updatedAt) {}

  record ProgramSettingsRecord(
      UUID id,
      long rewardForReferrerPaise,
      long rewardForRefereePaise,
      boolean active,
      int rewardExpiryDays,
      String conditions,
      UUID updatedBy,
      Instant updatedAt) {}

  record AdminOverviewChips(
      long totalReferrals,
      long convertedReferrals,
      long pendingRewardsPaise,
      long totalRewardsPaidPaise) {}

  record TopReferrerRow(
      UUID customerId, String name, int totalReferrals, int converted, long totalEarnedPaise) {}

  record AdminReferralRow(
      UUID id,
      String referrerName,
      String refereeName,
      String refereePhone,
      ReferralEventStatus status,
      Instant rewardCreditedAt,
      Instant createdAt) {}
}
