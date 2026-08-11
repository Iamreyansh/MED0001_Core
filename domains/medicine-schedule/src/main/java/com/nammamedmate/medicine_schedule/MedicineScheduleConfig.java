package com.nammamedmate.medicine_schedule;

import com.nammamedmate.medicine_schedule.application.ReminderRecalcService;
import com.nammamedmate.medicine_schedule.application.port.out.CustomerNamePort;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.TodayAdherencePort;
import com.nammamedmate.medicine_schedule.domain.AdherenceMath;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MedicineScheduleConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock medicineScheduleClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(CustomerNamePort.class)
  CustomerNamePort stubCustomerNamePort() {
    return customerId -> "Customer";
  }

  /** Stub records SENT without FCM; apps/api MedicineScheduleBridgeConfig writes outbox events. */
  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort medicineScheduleStubNotificationDispatchPort() {
    return new NotificationDispatchPort() {
      @Override
      public void notifyDoseReminderDue(
          java.util.UUID customerId,
          java.util.UUID reminderId,
          java.util.UUID doseLogId,
          java.util.UUID medicineId) {
        // no-op: DueReminderDispatcher still marks SENT after this returns
      }

      @Override
      public void notifyRefillAlert(
          java.util.UUID customerId,
          java.util.UUID medicineId,
          int unitsInHand,
          int refillRemindAtUnits) {
        // no-op stub
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(TodayAdherencePort.class)
  TodayAdherencePort doseLogTodayAdherencePort(DoseLogStore doseLogs, Clock clock) {
    return memberId -> {
      LocalDate today = LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
      DoseLogStore.TodayCounts c = doseLogs.countsForMemberOn(memberId, today);
      // ponytail: story wrote -100; treat as ×100
      Double pct = AdherenceMath.pct(c.taken(), c.total());
      return new TodayAdherencePort.TodayAdherence(
          c.total(), c.taken(), c.skipped(), c.missed(), c.upcoming(), pct);
    };
  }
}
