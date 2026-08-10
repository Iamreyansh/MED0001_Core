package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PrescriptionRecord;
import java.time.LocalDate;
import java.util.List;

public interface OcrPort {

  record OcrResult(
      String doctorName,
      String registrationNo,
      String qualification,
      String specialty,
      LocalDate prescriptionDate,
      List<PrescriptionRecord.MedicineExtracted> medicines) {

    public OcrResult {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }

    /** Back-compat for tests that only supply name/date/meds. */
    public OcrResult(
        String doctorName,
        LocalDate prescriptionDate,
        List<PrescriptionRecord.MedicineExtracted> medicines) {
      this(doctorName, null, null, null, prescriptionDate, medicines);
    }
  }

  /** Returns null on failure — caller leaves extracted fields null. */
  OcrResult extract(byte[] fileBytes, String mimeType);
}
