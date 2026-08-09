package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Hook from settlement release → TCS register (BR-007). Implemented by {@code TaxFacadeService}.
 */
public interface TcsRegisterWriterPort {

  void recordReleasedSettlement(
      UUID settlementId, UUID pharmacyId, String month, long gmvPaise, long tcsPaise, Instant now);
}
