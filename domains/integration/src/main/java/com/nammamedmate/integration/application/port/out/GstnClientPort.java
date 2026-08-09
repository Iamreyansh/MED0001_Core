package com.nammamedmate.integration.application.port.out;

import java.time.LocalDate;
import java.util.Optional;

public interface GstnClientPort {

  record GstnResult(
      boolean found,
      boolean valid,
      String tradeName,
      String legalName,
      String registrationStatus,
      String filingStatus,
      String state,
      String stateCode,
      LocalDate registeredAt) {}

  /**
   * @return empty when portal has no record
   */
  Optional<GstnResult> verify(String gstin);
}
