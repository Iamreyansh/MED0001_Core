package com.nammamedmate.customer.support;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link CustomerProfileStore} for unit tests. */
public final class FakeCustomerProfileStore implements CustomerProfileStore {

  private final Map<UUID, CustomerProfileRecord> profiles = new ConcurrentHashMap<>();
  private final List<NotificationRow> notifications = new CopyOnWriteArrayList<>();
  private final List<SegmentChangeRow> segmentChanges = new CopyOnWriteArrayList<>();

  @Override
  public Optional<CustomerProfileRecord> findById(UUID id) {
    return Optional.ofNullable(profiles.get(id));
  }

  @Override
  public CustomerProfileRecord saveProfile(CustomerProfileRecord customer) {
    profiles.put(customer.id(), customer);
    return customer;
  }

  @Override
  public void requestDeletion(UUID id, java.time.Instant requestedAt, String reason) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              c.id(),
              c.phone(),
              c.name(),
              c.avatarUrl(),
              c.dateOfBirth(),
              c.gender(),
              c.preferredLanguage(),
              c.segment(),
              c.city(),
              c.isFlagged(),
              c.flagReason(),
              c.flagNote(),
              c.flaggedBy(),
              c.flaggedAt(),
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              requestedAt,
              reason,
              c.createdAt(),
              requestedAt,
              c.deletedAt()));
    }
  }

  @Override
  public void cancelDeletion(UUID id) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              c.id(),
              c.phone(),
              c.name(),
              c.avatarUrl(),
              c.dateOfBirth(),
              c.gender(),
              c.preferredLanguage(),
              c.segment(),
              c.city(),
              c.isFlagged(),
              c.flagReason(),
              c.flagNote(),
              c.flaggedBy(),
              c.flaggedAt(),
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              null,
              null,
              c.createdAt(),
              java.time.Instant.now(),
              c.deletedAt()));
    }
  }

  @Override
  public void flag(
      UUID id, String reason, String note, UUID flaggedBy, java.time.Instant flaggedAt) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              c.id(),
              c.phone(),
              c.name(),
              c.avatarUrl(),
              c.dateOfBirth(),
              c.gender(),
              c.preferredLanguage(),
              c.segment(),
              c.city(),
              true,
              reason,
              note,
              flaggedBy,
              flaggedAt,
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              c.deletionRequestedAt(),
              c.deletionReason(),
              c.createdAt(),
              flaggedAt,
              c.deletedAt()));
    }
  }

  @Override
  public void unflag(UUID id) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              c.id(),
              c.phone(),
              c.name(),
              c.avatarUrl(),
              c.dateOfBirth(),
              c.gender(),
              c.preferredLanguage(),
              c.segment(),
              c.city(),
              false,
              null,
              null,
              null,
              null,
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              c.deletionRequestedAt(),
              c.deletionReason(),
              c.createdAt(),
              java.time.Instant.now(),
              c.deletedAt()));
    }
  }

  @Override
  public PageResult list(ListFilter filter) {
    List<CustomerProfileRecord> filtered = new ArrayList<>();
    for (CustomerProfileRecord c : profiles.values()) {
      if (c.deletedAt() != null) {
        continue;
      }
      if (filter.search() != null) {
        String q = filter.search().toLowerCase(Locale.ROOT);
        boolean match =
            (c.name() != null && c.name().toLowerCase(Locale.ROOT).contains(q))
                || (c.phone() != null && c.phone().contains(q));
        if (!match) {
          continue;
        }
      }
      if (filter.segment() != null
          && (c.segment() == null || !c.segment().equalsIgnoreCase(filter.segment()))) {
        continue;
      }
      if (filter.isFlagged() != null && c.isFlagged() != filter.isFlagged()) {
        continue;
      }
      if (filter.city() != null
          && (c.city() == null || !c.city().equalsIgnoreCase(filter.city()))) {
        continue;
      }
      filtered.add(c);
    }

    Comparator<CustomerProfileRecord> comparator =
        switch (filter.sort()) {
          case "name" ->
              Comparator.comparing(
                  CustomerProfileRecord::name, Comparator.nullsLast(String::compareTo));
          case "total_orders" -> Comparator.comparingInt(CustomerProfileRecord::totalOrders);
          case "total_ltv" -> Comparator.comparingLong(CustomerProfileRecord::totalLtvPaise);
          default ->
              Comparator.comparing(
                  CustomerProfileRecord::createdAt,
                  Comparator.nullsLast(java.time.Instant::compareTo));
        };
    if ("desc".equalsIgnoreCase(filter.order())) {
      comparator = comparator.reversed();
    }
    filtered.sort(comparator);

    int offset = filter.offset();
    int limit = filter.limit();
    List<CustomerProfileRecord> page =
        filtered.subList(
            Math.min(offset, filtered.size()), Math.min(offset + limit, filtered.size()));
    return new PageResult(page, filtered.size());
  }

  @Override
  public void updateSegment(UUID id, String segment) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              c.id(),
              c.phone(),
              c.name(),
              c.avatarUrl(),
              c.dateOfBirth(),
              c.gender(),
              c.preferredLanguage(),
              segment,
              c.city(),
              c.isFlagged(),
              c.flagReason(),
              c.flagNote(),
              c.flaggedBy(),
              c.flaggedAt(),
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              c.deletionRequestedAt(),
              c.deletionReason(),
              c.createdAt(),
              java.time.Instant.now(),
              c.deletedAt()));
    }
  }

  @Override
  public void insertSegmentChange(
      UUID id,
      UUID customerId,
      String from,
      String to,
      int totalOrders,
      long totalLtvPaise,
      java.time.Instant changedAt) {
    segmentChanges.add(
        new SegmentChangeRow(id, customerId, from, to, totalOrders, totalLtvPaise, changedAt));
  }

  @Override
  public List<CustomerProfileRecord> findAllActiveForSegmentRecompute() {
    return profiles.values().stream().filter(c -> c.deletedAt() == null).toList();
  }

  @Override
  public List<CustomerProfileRecord> findDueForAnonymisation(java.time.Instant cutoff) {
    return profiles.values().stream()
        .filter(
            c ->
                c.deletedAt() == null
                    && c.deletionRequestedAt() != null
                    && !c.deletionRequestedAt().isAfter(cutoff))
        .toList();
  }

  @Override
  public void anonymise(UUID id, String hashedPhone, java.time.Instant deletedAt) {
    CustomerProfileRecord c = profiles.get(id);
    if (c != null) {
      profiles.put(
          id,
          new CustomerProfileRecord(
              id,
              hashedPhone,
              "Deleted User",
              null,
              null,
              null,
              c.preferredLanguage(),
              c.segment(),
              null,
              false,
              null,
              null,
              null,
              null,
              c.walletBalancePaise(),
              c.loyaltyPoints(),
              c.totalOrders(),
              c.totalLtvPaise(),
              c.cancelRate(),
              c.disputeCount(),
              c.lastOrderAt(),
              null,
              null,
              c.createdAt(),
              deletedAt,
              deletedAt));
    }
  }

  @Override
  public int countNotificationsSince(UUID customerId, java.time.Instant since) {
    int count = 0;
    for (NotificationRow n : notifications) {
      if (n.customerId().equals(customerId) && !n.createdAt().isBefore(since)) {
        count++;
      }
    }
    return count;
  }

  @Override
  public UUID insertNotification(
      UUID id,
      UUID customerId,
      String channel,
      String title,
      String body,
      String deepLink,
      UUID createdBy,
      java.time.Instant createdAt) {
    notifications.add(
        new NotificationRow(id, customerId, channel, title, body, deepLink, createdBy, createdAt));
    return id;
  }

  public List<SegmentChangeRow> segmentChanges() {
    return List.copyOf(segmentChanges);
  }

  public record NotificationRow(
      UUID id,
      UUID customerId,
      String channel,
      String title,
      String body,
      String deepLink,
      UUID createdBy,
      java.time.Instant createdAt) {}

  public record SegmentChangeRow(
      UUID id,
      UUID customerId,
      String from,
      String to,
      int totalOrders,
      long totalLtvPaise,
      java.time.Instant changedAt) {}
}
