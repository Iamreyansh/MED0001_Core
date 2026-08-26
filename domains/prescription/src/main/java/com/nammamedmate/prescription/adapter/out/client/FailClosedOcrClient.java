package com.nammamedmate.prescription.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.prescription.application.port.out.OcrPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Staging/prod: never invent doctor/medicine extracts. */
@Component
@Profile({"prod", "staging"})
public class FailClosedOcrClient implements OcrPort {

  @Override
  public OcrResult extract(byte[] fileBytes, String mimeType) {
    throw new AppException("OCR_UNAVAILABLE", "OCR provider is not configured", 503);
  }
}
