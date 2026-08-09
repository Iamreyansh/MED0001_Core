package com.nammamedmate.crm.application.port.out;

import java.time.Duration;
import java.time.Instant;

public interface SaasInvoicePdfPort {

  record SignedUrl(String url, Instant expiresAt) {}

  void put(String objectKey, byte[] pdfBytes);

  SignedUrl signedGet(String objectKey, Duration ttl);
}
