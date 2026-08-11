package com.nammamedmate.notification.application.port.out;

import java.util.Map;

/** Twilio fallback SMS provider. */
public interface TwilioClientPort {

  record SendRequest(
      String toPhone, String senderId, String content, Map<String, String> variables) {
    public SendRequest {
      variables = variables == null ? Map.of() : Map.copyOf(variables);
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
