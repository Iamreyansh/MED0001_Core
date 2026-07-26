package com.nammamedmate.customer.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerProfileStore {

  Optional<CustomerProfileRecord> findById(UUID id);

  /** Row-lock for rate-limit / notify mutations within an open transaction. */
  default void lockCustomer(UUID id) {}

  CustomerProfileRecord saveProfile(CustomerProfileRecord customer);

  void requestDeletion(UUID id, Instant requestedAt, String reason);

  void cancelDeletion(UUID id);

  void flag(UUID id, String reason, String note, UUID flaggedBy, Instant flaggedAt);

  void unflag(UUID id);

  PageResult list(ListFilter filter);

  void updateSegment(UUID id, String segment);

  void insertSegmentChange(
      UUID id,
      UUID customerId,
      String from,
      String to,
      int totalOrders,
      long totalLtvPaise,
      Instant changedAt);

  List<CustomerProfileRecord> findAllActiveForSegmentRecompute();

  List<CustomerProfileRecord> findDueForAnonymisation(Instant cutoff);

  void anonymise(UUID id, String hashedPhone, Instant deletedAt);

  int countNotificationsSince(UUID customerId, Instant since);

  UUID insertNotification(
      UUID id,
      UUID customerId,
      String channel,
      String title,
      String body,
      String deepLink,
      UUID createdBy,
      Instant createdAt);

  record ListFilter(
      int page,
      int limit,
      String sort,
      String order,
      String search,
      String segment,
      Boolean isFlagged,
      String city) {
    public int offset() {
      return (page - 1) * limit;
    }
  }

  record PageResult(List<CustomerProfileRecord> items, long total) {
    public PageResult {
      items = List.copyOf(items);
    }
  }

  record CustomerProfileRecord(
      UUID id,
      String phone,
      String name,
      String avatarUrl,
      LocalDate dateOfBirth,
      String gender,
      String preferredLanguage,
      String segment,
      String city,
      boolean isFlagged,
      String flagReason,
      String flagNote,
      UUID flaggedBy,
      Instant flaggedAt,
      long walletBalancePaise,
      int loyaltyPoints,
      int totalOrders,
      long totalLtvPaise,
      BigDecimal cancelRate,
      int disputeCount,
      Instant lastOrderAt,
      Instant deletionRequestedAt,
      String deletionReason,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}
}
