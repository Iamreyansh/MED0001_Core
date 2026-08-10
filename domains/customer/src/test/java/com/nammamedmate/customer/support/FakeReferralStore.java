package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;

public class FakeReferralStore implements ReferralStore {

  private final Map<UUID, ReferralRecord> byCustomer = new ConcurrentHashMap<>();
  private final Map<String, UUID> codeToCustomer = new ConcurrentHashMap<>();
  private final List<ReferralEventRecord> events = new ArrayList<>();
  private final List<ShareEvent> shares = new ArrayList<>();
  private ProgramSettingsRecord settings;
  public boolean clearLocks;
  public boolean failNextInsertEvent;
  public boolean failNextInsertReferral;
  public boolean failSettings;
  public ReferralRecord revealAfterFailedInsert;

  public FakeReferralStore() {
    Instant now = Instant.parse("2026-07-26T02:00:00Z");
    settings =
        new ProgramSettingsRecord(
            PROGRAM_SETTINGS_ID,
            10_000L,
            10_000L,
            true,
            365,
            "Reward credited after referee's first DELIVERED order. One code per customer.",
            null,
            now);
  }

  @Override
  public Optional<ReferralRecord> findByCustomerId(UUID customerId) {
    return Optional.ofNullable(byCustomer.get(customerId));
  }

  @Override
  public Optional<ReferralRecord> findByCode(String referralCode) {
    UUID customerId = codeToCustomer.get(referralCode);
    return customerId == null ? Optional.empty() : findByCustomerId(customerId);
  }

  @Override
  public Optional<ReferralRecord> lockByCustomerId(UUID customerId) {
    if (clearLocks) {
      return Optional.empty();
    }
    return findByCustomerId(customerId);
  }

  @Override
  public ReferralRecord insert(ReferralRecord record) {
    if (failNextInsertReferral) {
      failNextInsertReferral = false;
      if (revealAfterFailedInsert != null) {
        byCustomer.put(revealAfterFailedInsert.customerId(), revealAfterFailedInsert);
        codeToCustomer.put(
            revealAfterFailedInsert.referralCode(), revealAfterFailedInsert.customerId());
        revealAfterFailedInsert = null;
      }
      throw new DuplicateKeyException("duplicate referral");
    }
    if (byCustomer.containsKey(record.customerId())
        || codeToCustomer.containsKey(record.referralCode())) {
      throw new DuplicateKeyException("duplicate referral");
    }
    byCustomer.put(record.customerId(), record);
    codeToCustomer.put(record.referralCode(), record.customerId());
    return record;
  }

  @Override
  public ReferralRecord update(ReferralRecord record) {
    byCustomer.put(record.customerId(), record);
    return record;
  }

  @Override
  public boolean codeExists(String referralCode) {
    return codeToCustomer.containsKey(referralCode);
  }

  @Override
  public ReferralEventRecord insertEvent(ReferralEventRecord event) {
    if (failNextInsertEvent) {
      failNextInsertEvent = false;
      throw new DuplicateKeyException("duplicate referee event");
    }
    if (events.stream().anyMatch(e -> e.refereeCustomerId().equals(event.refereeCustomerId()))) {
      throw new DuplicateKeyException("duplicate referee event");
    }
    events.add(event);
    return event;
  }

  @Override
  public Optional<ReferralEventRecord> findEventByReferee(UUID refereeCustomerId) {
    return events.stream().filter(e -> e.refereeCustomerId().equals(refereeCustomerId)).findFirst();
  }

  @Override
  public Optional<ReferralEventRecord> lockEventById(UUID eventId) {
    return events.stream().filter(e -> e.id().equals(eventId)).findFirst();
  }

  @Override
  public ReferralEventRecord updateEvent(ReferralEventRecord event) {
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).id().equals(event.id())) {
        events.set(i, event);
        return event;
      }
    }
    events.add(event);
    return event;
  }

  @Override
  public long countEventsByReferrerAndStatus(UUID referrerCustomerId, ReferralEventStatus status) {
    return events.stream()
        .filter(e -> e.referrerCustomerId().equals(referrerCustomerId))
        .filter(e -> e.status() == status)
        .count();
  }

  @Override
  public ProgramSettingsRecord getProgramSettings() {
    if (failSettings) {
      throw new IllegalStateException("settings missing");
    }
    return settings;
  }

  @Override
  public ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord next) {
    this.settings = next;
    return next;
  }

  @Override
  public void insertShareEvent(UUID id, UUID customerId, String channel, Instant createdAt) {
    shares.add(new ShareEvent(id, customerId, channel, createdAt));
  }

  @Override
  public long countShareEvents(UUID customerId) {
    return shares.stream().filter(s -> s.customerId.equals(customerId)).count();
  }

  @Override
  public AdminOverviewChips chips() {
    long total = events.size();
    long converted =
        events.stream().filter(e -> e.status() == ReferralEventStatus.REWARDED).count();
    long pending =
        events.stream()
            .filter(e -> e.status() == ReferralEventStatus.PENDING)
            .mapToLong(ReferralEventRecord::rewardAmountPaise)
            .sum();
    long paid =
        events.stream()
            .filter(e -> e.status() == ReferralEventStatus.REWARDED)
            .mapToLong(e -> e.rewardAmountPaise() + e.refereeRewardAmountPaise())
            .sum();
    return new AdminOverviewChips(total, converted, pending, paid);
  }

  public String adminReferrerName = "Referrer";
  public String adminRefereeName = "Referee";
  public String adminRefereePhone = "+919876543210";

  @Override
  public List<TopReferrerRow> topReferrers(int limit) {
    return byCustomer.values().stream()
        .filter(r -> r.convertedReferrals() > 0)
        .sorted(
            Comparator.comparingInt(ReferralRecord::convertedReferrals)
                .reversed()
                .thenComparingLong(ReferralRecord::totalEarnedPaise)
                .reversed())
        .limit(limit)
        .map(
            r ->
                new TopReferrerRow(
                    r.customerId(),
                    topReferrerName,
                    r.totalReferrals(),
                    r.convertedReferrals(),
                    r.totalEarnedPaise()))
        .toList();
  }

  public String topReferrerName = "Referrer";

  @Override
  public List<AdminReferralRow> listAdminReferrals(
      ReferralEventStatus statusFilter, int limit, int offset) {
    return events.stream()
        .filter(e -> statusFilter == null || e.status() == statusFilter)
        .sorted(Comparator.comparing(ReferralEventRecord::createdAt).reversed())
        .skip(offset)
        .limit(limit)
        .map(
            e ->
                new AdminReferralRow(
                    e.id(),
                    adminReferrerName,
                    adminRefereeName,
                    adminRefereePhone,
                    e.status(),
                    e.referrerRewardedAt(),
                    e.createdAt()))
        .toList();
  }

  @Override
  public long countAdminReferrals(ReferralEventStatus statusFilter) {
    return events.stream().filter(e -> statusFilter == null || e.status() == statusFilter).count();
  }

  public void setActive(boolean active) {
    settings =
        new ProgramSettingsRecord(
            settings.id(),
            settings.rewardForReferrerPaise(),
            settings.rewardForRefereePaise(),
            active,
            settings.rewardExpiryDays(),
            settings.conditions(),
            settings.updatedBy(),
            settings.updatedAt());
  }

  public void setRewards(long referrerPaise, long refereePaise) {
    settings =
        new ProgramSettingsRecord(
            settings.id(),
            referrerPaise,
            refereePaise,
            settings.active(),
            settings.rewardExpiryDays(),
            settings.conditions(),
            settings.updatedBy(),
            settings.updatedAt());
  }

  public List<ReferralEventRecord> allEvents() {
    return List.copyOf(events);
  }

  private record ShareEvent(UUID id, UUID customerId, String channel, Instant createdAt) {}
}
