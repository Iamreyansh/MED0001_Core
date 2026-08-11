package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.MemberCascadePort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderRecalcPort;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcMemberCascadeAdapterTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");

  @Test
  void cascadeArchivesAndCancels() {
    ScheduleMedicineStore medicines = mock(ScheduleMedicineStore.class);
    ReminderRecalcPort reminders = mock(ReminderRecalcPort.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    JdbcMemberCascadeAdapter adapter = new JdbcMemberCascadeAdapter(medicines, reminders, clock);

    UUID memberId = Ids.newId();
    UUID medicineId = Ids.newId();
    ScheduleMedicineRecord med =
        new ScheduleMedicineRecord(
            medicineId,
            Ids.newId(),
            memberId,
            null,
            "Med",
            null,
            "1",
            "TABLET",
            List.of(new DoseSlot("MORNING", "08:00")),
            "ANY",
            "ONGOING",
            null,
            LocalDate.parse("2026-07-01"),
            null,
            null,
            null,
            0,
            0,
            null,
            true,
            NOW,
            NOW);
    when(medicines.listActiveByMember(memberId)).thenReturn(List.of(med));
    when(medicines.softArchiveByMember(eq(memberId), any(), any())).thenReturn(1);
    when(reminders.cancelFuture(medicineId)).thenReturn(7);

    MemberCascadePort.CascadeResult result = adapter.cascadeOnDelete(memberId);

    assertThat(result.medicinesArchived()).isEqualTo(1);
    assertThat(result.remindersCancelled()).isEqualTo(7);
    verify(medicines).softArchiveByMember(memberId, LocalDate.parse("2026-07-24"), NOW);
    verify(reminders).cancelFuture(medicineId);
  }
}
