package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.PushPriority;
import java.util.Map;

public interface FcmClientPort {

  record PushRequest(
      String token,
      String title,
      String body,
      Map<String, String> data,
      String imageUrl,
      String actionUrl,
      PushPriority priority,
      boolean silent) {
    public PushRequest {
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }

  record PushResult(boolean success, String messageId, String errorCode, String errorMessage) {
    public static PushResult ok(String messageId) {
      return new PushResult(true, messageId, null, null);
    }

    public static PushResult fail(String errorCode, String errorMessage) {
      return new PushResult(false, null, errorCode, errorMessage);
    }
  }

  PushResult send(PushRequest request);
}
