package com.nammamedmate.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Objects;

public final class OutboxPublisher {

  private final OutboxStore store;
  private final ObjectMapper objectMapper;

  public OutboxPublisher(OutboxStore store, ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store);
    ObjectMapper mapper = Objects.requireNonNull(objectMapper).copy();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.objectMapper = mapper;
  }

  public void publish(DomainEvent event) {
    try {
      String json = objectMapper.writeValueAsString(event);
      store.append(OutboxMessage.pending(event.type(), json));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize domain event", e);
    }
  }
}
