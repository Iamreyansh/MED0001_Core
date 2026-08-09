package com.nammamedmate.integration.application.port.out;

import java.time.LocalDate;
import java.util.Optional;

public interface FssaiClientPort {

  record FssaiResult(
      boolean found,
      boolean valid,
      boolean manualReviewRequired,
      String businessName,
      String category,
      LocalDate expiryDate,
      String status) {}

  Optional<FssaiResult> verify(String licenceNumber);
}
