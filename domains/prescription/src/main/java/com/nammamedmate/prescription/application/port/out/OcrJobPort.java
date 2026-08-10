package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** Schedules OCR after upload (sync stub locally; async later via queue). */
public interface OcrJobPort {

  void schedule(UUID prescriptionId, byte[] fileBytes, String mimeType);
}
