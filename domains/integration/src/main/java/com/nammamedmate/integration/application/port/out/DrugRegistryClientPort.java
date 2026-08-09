package com.nammamedmate.integration.application.port.out;

import java.time.LocalDate;
import java.util.List;

public interface DrugRegistryClientPort {

  record DrugLicenceResult(
      boolean async,
      boolean manualReviewRequired,
      boolean found,
      boolean valid,
      String holderName,
      LocalDate issuedDate,
      LocalDate expiryDate,
      List<String> drugsPermitted,
      String state,
      String licenceType,
      String status) {
    public DrugLicenceResult {
      drugsPermitted = List.copyOf(drugsPermitted);
    }
  }

  DrugLicenceResult verify(String licenceNumber, String state, String licenceType);
}
