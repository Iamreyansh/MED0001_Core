package com.nammamedmate.medicine_schedule.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ReminderSchedulersTest {

  @Test
  void bulkScheduleScheduler() {
    DoseReminderService doses = mock(DoseReminderService.class);
    when(doses.bulkScheduleAllCustomers()).thenReturn(3);
    new ReminderBulkScheduleScheduler(doses).extendWindow();
    verify(doses).bulkScheduleAllCustomers();
  }

  @Test
  void missedDoseScheduler() {
    DoseReminderService doses = mock(DoseReminderService.class);
    when(doses.markMissedDoses()).thenReturn(2);
    new MissedDoseScheduler(doses).markMissed();
    verify(doses).markMissedDoses();
  }

  @Test
  void dueDispatcher() {
    DoseReminderService doses = mock(DoseReminderService.class);
    when(doses.dispatchDueReminders(100)).thenReturn(1);
    new DueReminderDispatcher(doses).dispatchDue();
    verify(doses).dispatchDueReminders(100);
  }

  @Test
  void schedulersNoopWhenZero() {
    DoseReminderService doses = mock(DoseReminderService.class);
    when(doses.bulkScheduleAllCustomers()).thenReturn(0);
    when(doses.markMissedDoses()).thenReturn(0);
    when(doses.dispatchDueReminders(100)).thenReturn(0);
    new ReminderBulkScheduleScheduler(doses).extendWindow();
    new MissedDoseScheduler(doses).markMissed();
    new DueReminderDispatcher(doses).dispatchDue();
  }

  @Test
  void supplyAndRefillPushSchedulers() {
    RefillAlertService refills = mock(RefillAlertService.class);
    when(refills.runNightlySupplyDecrement()).thenReturn(2);
    when(refills.dispatchDailyRefillAlerts()).thenReturn(1);
    new SupplyDecrementScheduler(refills).decrementSupply();
    new RefillAlertPushScheduler(refills).pushRefillAlerts();
    verify(refills).runNightlySupplyDecrement();
    verify(refills).dispatchDailyRefillAlerts();
    when(refills.runNightlySupplyDecrement()).thenReturn(0);
    when(refills.dispatchDailyRefillAlerts()).thenReturn(0);
    new SupplyDecrementScheduler(refills).decrementSupply();
    new RefillAlertPushScheduler(refills).pushRefillAlerts();
  }
}
