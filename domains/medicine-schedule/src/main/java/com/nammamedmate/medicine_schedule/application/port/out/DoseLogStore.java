package com.nammamedmate.medicine_schedule.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DoseLogStore {

  /** Upsert by (medicine_id, dose_date, slot). Returns the persisted row. */
  DoseLogRecord upsertUpcoming(DoseLogRecord draft);

  Optional<DoseLogRecord> findByMedicineDateSlot(UUID medicineId, LocalDate doseDate, String slot);

  Optional<DoseLogRecord> findById(UUID doseLogId);

  List<DoseLogRecord> listByMemberAndDate(UUID memberId, LocalDate doseDate);

  List<DoseLogRecord> listUpcomingByMemberUntil(UUID memberId, Instant until);

  DoseLogRecord updateStatus(
      UUID doseLogId, String status, Instant takenAt, boolean locked, Instant updatedAt);

  /** Mark UPCOMING logs MISSED when their scheduled instant is before cutoff. Returns count. */
  int markMissedBefore(Instant cutoff, Instant updatedAt);

  TodayCounts countsForMemberOn(UUID memberId, LocalDate doseDate);

  TodayCounts countsForMedicineOn(UUID medicineId, LocalDate doseDate);

  /** Per-day aggregates for a member (inclusive date range). Empty days omitted. */
  List<DailyCounts> dailyCountsForMember(
      UUID memberId, LocalDate fromInclusive, LocalDate toInclusive);

  /**
   * Per-day aggregates for a medicine (inclusive). Empty days omitted. Pass null bounds for
   * all-time.
   */
  List<DailyCounts> dailyCountsForMedicine(
      UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive);

  /** Aggregate totals for a member over an inclusive date range. */
  TodayCounts countsForMemberBetween(UUID memberId, LocalDate fromInclusive, LocalDate toInclusive);

  /** Aggregate totals for a medicine over an inclusive date range (null = unbounded). */
  TodayCounts countsForMedicineBetween(
      UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive);

  record DoseLogRecord(
      UUID id,
      UUID medicineId,
      UUID customerId,
      UUID memberId,
      LocalDate doseDate,
      String slot,
      LocalTime reminderTime,
      String status,
      Instant takenAt,
      boolean locked,
      Instant createdAt,
      Instant updatedAt) {}

  record TodayCounts(int total, int taken, int skipped, int missed, int upcoming) {}

  record DailyCounts(
      LocalDate doseDate, int total, int taken, int skipped, int missed, int upcoming) {}
}
