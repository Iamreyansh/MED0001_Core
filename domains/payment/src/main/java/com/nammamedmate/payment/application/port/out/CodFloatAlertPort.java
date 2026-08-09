package com.nammamedmate.payment.application.port.out;

import java.time.LocalDate;
import java.util.UUID;

/** Outbox-backed COD float variance alert to admin_finance (EPIC-017 / EPIC-013 consumes). */
public interface CodFloatAlertPort {

  void varianceAlert(
      UUID reportId, LocalDate reportDate, long variancePaise, String reconciliationStatus);
}
