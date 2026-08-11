package com.nammamedmate.medicine_schedule.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CareCircleMemberStore {

  List<MemberRecord> listByCustomer(UUID customerId);

  int countByCustomer(UUID customerId);

  Optional<MemberRecord> findById(UUID memberId);

  Optional<MemberRecord> findSelf(UUID customerId);

  MemberRecord insert(MemberRecord member);

  MemberRecord update(MemberRecord member);

  void softDelete(UUID memberId, Instant deletedAt);

  record MemberRecord(
      UUID id,
      UUID customerId,
      String name,
      int age,
      String relationship,
      String avatarEmoji,
      String avatarColor,
      boolean self,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}
}
