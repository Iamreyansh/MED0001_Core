package com.nammamedmate.rider.adapter.out.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.rider.application.port.out.OrderLocationPushPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** In-process SSE fan-out for order rider-location channels. */
public class InMemoryOrderLocationPush implements OrderLocationPushPort {

  private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters =
      new ConcurrentHashMap<>();
  private final ObjectMapper mapper;

  public InMemoryOrderLocationPush(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public SseEmitter subscribe(UUID orderId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    CopyOnWriteArrayList<SseEmitter> list =
        emitters.computeIfAbsent(orderId, id -> new CopyOnWriteArrayList<>());
    list.add(emitter);
    emitter.onCompletion(() -> removeEmitter(orderId, emitter));
    emitter.onTimeout(() -> removeEmitter(orderId, emitter));
    emitter.onError(e -> removeEmitter(orderId, emitter));
    return emitter;
  }

  public void removeEmitter(UUID orderId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(orderId);
    if (list != null) {
      list.remove(emitter);
    }
  }

  @Override
  public void publish(UUID orderId, Map<String, Object> payload) {
    List<SseEmitter> list = emitters.get(orderId);
    if (list == null || list.isEmpty()) {
      return;
    }
    String json;
    try {
      json = mapper.writeValueAsString(payload);
    } catch (Exception e) {
      return;
    }
    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event().name("rider-location").data(json));
      } catch (Exception ex) {
        list.remove(emitter);
      }
    }
  }

  @Override
  public String channelUrl(UUID orderId) {
    return "/api/v1/orders/" + orderId + "/rider-location/stream";
  }
}
