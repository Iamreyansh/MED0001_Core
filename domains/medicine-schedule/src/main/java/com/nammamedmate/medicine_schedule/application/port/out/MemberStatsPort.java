package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Aggregate counts for care-circle list rows (medicines / refill — later stories). */
public interface MemberStatsPort {

  MemberListStats statsForMember(UUID memberId);

  record MemberListStats(
      int medicinesCount,
      int todayDosesTotal,
      int todayDosesTaken,
      Double todayAdherencePct,
      int refillAlertsCount) {}
}
