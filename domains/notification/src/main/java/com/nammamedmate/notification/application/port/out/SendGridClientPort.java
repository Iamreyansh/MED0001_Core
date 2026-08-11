package com.nammamedmate.notification.application.port.out;

import java.util.List;
import java.util.Map;

/** SendGrid primary email provider. */
public interface SendGridClientPort {

  record Attachment(String filename, byte[] content, String contentType) {
    public Attachment {
      content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    public int sizeBytes() {
      return content.length;
    }
  }

  record SendRequest(
      String toEmail,
      String toName,
      String subject,
      String htmlBody,
      String textBody,
      List<Attachment> attachments,
      Map<String, String> customArgs) {
    public SendRequest {
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
      customArgs = customArgs == null ? Map.of() : Map.copyOf(customArgs);
    }
  }

  record SendResult(boolean success, boolean serverError, String messageId, String errorMessage) {
    public static SendResult ok(String messageId) {
      return new SendResult(true, false, messageId, null);
    }

    public static SendResult serverError(String error) {
      return new SendResult(false, true, null, error);
    }

    public static SendResult fail(String error) {
      return new SendResult(false, false, null, error);
    }
  }

  SendResult send(SendRequest request);
}
