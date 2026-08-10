package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SegmentStore {

  record SnapshotPoint(LocalDate date, int count) {}

  record MembershipCustomer(
      UUID customerId,
      String name,
      String phone,
      int totalOrders,
      long ltvPaise,
      Instant lastOrderAt) {}

  record PagedMemberships(List<MembershipCustomer> customers, long total) {}

  Segment insert(Segment segment);

  Optional<Segment> findById(UUID id);

  Optional<Segment> findByNameIgnoreCase(String name);

  List<Segment> list(SegmentType typeFilter, int offset, int limit);

  long count(SegmentType typeFilter);

  void softDelete(UUID id, Instant deletedAt);

  void updateComputeResult(
      UUID id,
      int customerCount,
      Long avgAovPaise,
      Long totalLtvPaise,
      Instant computedAt,
      String status);

  void replaceMemberships(UUID segmentId, List<UUID> customerIds, Instant addedAt);

  void upsertSnapshot(UUID segmentId, LocalDate snapshotDate, int customerCount);

  List<SnapshotPoint> growthChart(UUID segmentId, int limit);

  PagedMemberships listMembers(UUID segmentId, String sort, String order, int offset, int limit);

  boolean isMember(UUID segmentId, UUID customerId);

  UUID enqueueComputeJob(UUID segmentId, Instant createdAt);

  Optional<ComputeJob> findJob(UUID jobId);

  List<ComputeJob> findQueuedJobs(int limit);

  void markJobRunning(UUID jobId, Instant startedAt);

  void markJobCompleted(UUID jobId, Instant completedAt);

  void markJobFailed(UUID jobId, Instant completedAt, String error);

  record ComputeJob(UUID id, UUID segmentId, String status) {}

  /** Helper to rebuild criteria list when inserting. */
  static Segment newCustom(
      UUID id,
      String name,
      String description,
      List<SegmentCriterion> criteria,
      UUID createdBy,
      Instant now) {
    return new Segment(
        id,
        name,
        description,
        SegmentType.CUSTOM,
        criteria,
        "PENDING_COMPUTE",
        0,
        null,
        null,
        null,
        createdBy,
        now,
        now,
        null);
  }
}
