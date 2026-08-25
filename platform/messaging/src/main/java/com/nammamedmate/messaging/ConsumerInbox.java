package com.nammamedmate.messaging;

import java.util.UUID;

/** Durable at-least-once consumer deduplication. */
public interface ConsumerInbox {

  boolean alreadyProcessed(String consumerName, UUID eventId);

  /**
   * @return false when this consumer already processed {@code eventId}.
   */
  boolean claim(String consumerName, UUID eventId);
}
