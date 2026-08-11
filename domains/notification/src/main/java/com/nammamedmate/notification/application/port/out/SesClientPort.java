package com.nammamedmate.notification.application.port.out;

import java.util.List;
import java.util.Map;

/** AWS SES fallback email provider. */
public interface SesClientPort {

  record Attachment(String filename, byte[] content, String contentType) {
    public Attachment {
      content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  record SendRequest(
      String toEmail,
      String toName,
      String subject,
      String htmlBody,
      String textBody,
      List<Attachment> attachments,
      Map<String, String> tags) {
    public SendRequest {
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
      tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
  }

  record SendResult(boolean success, String messageId, String errorMessage) {
    public static SendResult ok(String messageId) {
      return new SendResult(true, messageId, null);
    }

    public static SendResult fail(String error) {
      return new SendResult(false, null, error);
    }
  }

  SendResult send(SendRequest request);
}
