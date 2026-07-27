package com.nammamedmate.pharmacy.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.pharmacy.application.AutoKycService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** In-process outbox consumer for auto-KYC domain events (ponytail until SQS worker). */
@Component
public class AutoKycOutboxConsumer implements Consumer<OutboxMessage> {

  private final AutoKycService autoKyc;
  private final ObjectMapper objectMapper;

  public AutoKycOutboxConsumer(AutoKycService autoKyc, ObjectMapper objectMapper) {
    this.autoKyc = autoKyc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null || message.type() == null) {
      return;
    }
    switch (message.type()) {
      case "pharmacy.kyc.auto_verify_requested" -> handleAutoVerifyRequested(message);
      case "pharmacy.kyc.async_check_requested" -> handleAsyncCheckRequested(message);
      default -> {}
    }
  }

  private void handleAutoVerifyRequested(OutboxMessage message) {
    DomainEvent event = parseEvent(message);
    if (event == null) {
      return;
    }
    autoKyc.handleAutoVerifyRequested(event.aggregateId());
  }

  private void handleAsyncCheckRequested(OutboxMessage message) {
    DomainEvent event = parseEvent(message);
    if (event == null) {
      return;
    }
    Map<String, Object> payload = event.payload();
    Object verificationId = payload.get("verification_id");
    if (verificationId == null) {
      return;
    }
    autoKyc.processAsyncCheck(UUID.fromString(String.valueOf(verificationId)));
  }

  private DomainEvent parseEvent(OutboxMessage message) {
    try {
      return objectMapper.readValue(message.payloadJson(), DomainEvent.class);
    } catch (Exception ex) {
      return null;
    }
  }
}
