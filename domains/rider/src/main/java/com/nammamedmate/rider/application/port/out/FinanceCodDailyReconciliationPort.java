package com.nammamedmate.rider.application.port.out;

import java.time.LocalDate;

/**
 * Daily 23:00 IST COD finance reconciliation (EPIC-012/STORY-006). Stub no-op in rider unit tests;
 * bridged in apps/api to {@code CodFloatFacadeService}.
 */
public interface FinanceCodDailyReconciliationPort {

  void runForDate(LocalDate reportDate);
}
