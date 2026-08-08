package com.nammamedmate.rider.application.port.out;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE hub for live rider location + ETA (STORY-004 AC-005; WS deferred). */
public interface OrderLocationPushPort {

  SseEmitter subscribe(UUID orderId);

  void publish(UUID orderId, Map<String, Object> payload);

  String channelUrl(UUID orderId);
}
