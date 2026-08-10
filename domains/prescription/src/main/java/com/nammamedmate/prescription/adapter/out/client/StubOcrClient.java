package com.nammamedmate.prescription.adapter.out.client;

import com.nammamedmate.prescription.application.port.out.OcrPort;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Dev/stub OCR — deterministic extract until real OCR queue lands. */
@Component
public class StubOcrClient implements OcrPort {

  @Override
  public OcrResult extract(byte[] fileBytes, String mimeType) {
    if (fileBytes == null || fileBytes.length == 0) {
      return null;
    }
    return new OcrResult(
        "Dr. OCR Stub",
        "MH-OCR-001",
        "MBBS",
        "General Medicine",
        LocalDate.of(2026, 7, 20),
        List.of(
            new MedicineExtracted("Metformin 500mg", "60 tablets", "1-0-1", "H"),
            new MedicineExtracted("Atorvastatin 10mg", "30 tablets", "0-0-1", null)));
  }
}
