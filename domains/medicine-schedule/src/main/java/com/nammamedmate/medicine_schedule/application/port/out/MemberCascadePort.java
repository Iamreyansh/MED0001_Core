package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Archives medicines and cancels reminders when a care-circle member is removed. */
public interface MemberCascadePort {

  CascadeResult cascadeOnDelete(UUID memberId);

  record CascadeResult(int medicinesArchived, int remindersCancelled) {}
}
