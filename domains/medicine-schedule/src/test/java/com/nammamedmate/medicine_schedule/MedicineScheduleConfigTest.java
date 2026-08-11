package com.nammamedmate.medicine_schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.TodayAdherencePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MedicineScheduleConfigTest {

  @Test
  void stubsAndTodayAdherence() {
    MedicineScheduleConfig config = new MedicineScheduleConfig();
    assertThat(config.medicineScheduleClock()).isNotNull();
    assertThat(config.stubCustomerNamePort().nameFor(Ids.newId())).isEqualTo("Customer");
    NotificationDispatchPort n = config.medicineScheduleStubNotificationDispatchPort();
    n.notifyDoseReminderDue(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId());
    n.notifyRefillAlert(Ids.newId(), Ids.newId(), 5, 10);

    DoseLogStore doseLogs = mock(DoseLogStore.class);
    when(doseLogs.countsForMemberOn(any(), any())).thenReturn(new TodayCounts(4, 2, 1, 0, 1));
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T06:30:00Z"), ZoneOffset.UTC);
    TodayAdherencePort port = config.doseLogTodayAdherencePort(doseLogs, clock);
    TodayAdherencePort.TodayAdherence a = port.todayForMember(Ids.newId());
    assertThat(a.totalDoses()).isEqualTo(4);
    assertThat(a.taken()).isEqualTo(2);
    assertThat(a.adherencePct()).isEqualTo(50.0);
  }

  @Test
  void todayAdherenceNullPctWhenEmpty() {
    MedicineScheduleConfig config = new MedicineScheduleConfig();
    DoseLogStore doseLogs = mock(DoseLogStore.class);
    when(doseLogs.countsForMemberOn(any(), any())).thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T06:30:00Z"), ZoneOffset.UTC);
    assertThat(
            config
                .doseLogTodayAdherencePort(doseLogs, clock)
                .todayForMember(Ids.newId())
                .adherencePct())
        .isNull();
  }
}
