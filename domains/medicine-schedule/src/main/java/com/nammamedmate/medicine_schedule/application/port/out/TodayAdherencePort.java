package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Today's dose adherence for a care-circle member (DoseLog — STORY-003/004). */
public interface TodayAdherencePort {

  TodayAdherence todayForMember(UUID memberId);

  record TodayAdherence(
      int totalDoses, int taken, int skipped, int missed, int upcoming, Double adherencePct) {}
}
