package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.port.out.MemberCascadePort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderRecalcPort;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JdbcMemberCascadeAdapter implements MemberCascadePort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final ScheduleMedicineStore medicines;
  private final ReminderRecalcPort reminders;
  private final Clock clock;

  public JdbcMemberCascadeAdapter(
      ScheduleMedicineStore medicines, ReminderRecalcPort reminders, Clock clock) {
    this.medicines = medicines;
    this.reminders = reminders;
    this.clock = clock;
  }

  @Override
  public CascadeResult cascadeOnDelete(UUID memberId) {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    List<ScheduleMedicineRecord> active = medicines.listActiveByMember(memberId);
    int archived = medicines.softArchiveByMember(memberId, today, now);
    int cancelled = 0;
    for (ScheduleMedicineRecord med : active) {
      cancelled += reminders.cancelFuture(med.id());
    }
    return new CascadeResult(archived, cancelled);
  }
}
