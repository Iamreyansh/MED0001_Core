package com.nammamedmate.prescription.adapter.out.client;

import com.nammamedmate.prescription.application.PrescriptionService;
import com.nammamedmate.prescription.application.port.out.OcrJobPort;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * ponytail: runs OCR inline after upload (no queue yet). Upgrade: SQS OCR worker + outbox. Keeps
 * upload transactional commit before OCR mutate via REQUIRES_NEW on applyOcr path later.
 */
@Component
public class SyncOcrJobAdapter implements OcrJobPort {

  private final PrescriptionService service;

  public SyncOcrJobAdapter(@Lazy PrescriptionService service) {
    this.service = service;
  }

  @Override
  public void schedule(UUID prescriptionId, byte[] fileBytes, String mimeType) {
    service.applyOcr(prescriptionId, fileBytes, mimeType);
  }
}
