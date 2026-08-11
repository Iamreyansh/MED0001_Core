package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.SmsCategory;
import java.util.Map;

/** MSG91 primary SMS provider (send + DND check). */
public interface Msg91ClientPort {

  record SendRequest(
      String toPhone,
      String dltTemplateId,
      String senderId,
      String content,
      Map<String, String> variables,
      SmsCategory category) {
    public SendRequest {
      variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
  }

  record SendResult(boolean success, boolean timedOut, String messageId, String errorMessage) {
    public static SendResult ok(String messageId) {
      return new SendResult(true, false, messageId, null);
    }

    public static SendResult timeout() {
      return new SendResult(false, true, null, "MSG91 timeout");
    }

    public static SendResult fail(String error) {
      return new SendResult(false, false, null, error);
    }
  }

  /** Returns true when the number is on the TRAI DND registry for promotional SMS. */
  boolean isOnDnd(String toPhone);

  SendResult send(SendRequest request);
}
