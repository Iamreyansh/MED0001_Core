package com.nammamedmate.notification.application.port.out;

import java.util.List;
import java.util.Map;

public interface MetaWhatsAppClientPort {

  record SendRequest(
      String toPhone, String templateName, String language, List<Map<String, Object>> components) {}

  record SendResult(boolean success, String waMessageId, String errorMessage) {
    public static SendResult ok(String waMessageId) {
      return new SendResult(true, waMessageId, null);
    }

    public static SendResult fail(String errorMessage) {
      return new SendResult(false, null, errorMessage);
    }
  }

  record SubmitTemplateRequest(
      String name,
      String category,
      String language,
      String body,
      Map<String, Object> header,
      String footer,
      List<Map<String, Object>> buttons) {}

  record SubmitTemplateResult(boolean success, String metaTemplateId, String errorMessage) {
    public static SubmitTemplateResult ok(String metaTemplateId) {
      return new SubmitTemplateResult(true, metaTemplateId, null);
    }

    public static SubmitTemplateResult fail(String errorMessage) {
      return new SubmitTemplateResult(false, null, errorMessage);
    }
  }

  SendResult sendTemplate(SendRequest request);

  SubmitTemplateResult submitTemplate(SubmitTemplateRequest request);

  /** Meta {@code X-Hub-Signature-256}: {@code sha256=<hex>}. */
  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);
}
