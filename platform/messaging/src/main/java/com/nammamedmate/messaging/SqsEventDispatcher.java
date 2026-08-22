package com.nammamedmate.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Claims unpublished outbox messages and hands payloads to a transport (SQS send in production).
 */
public final class SqsEventDispatcher {

  private final OutboxStore store;
  private final Consumer<OutboxMessage> transport;
  private final int batchSize;
  private final String lockedBy;

  public SqsEventDispatcher(OutboxStore store, Consumer<OutboxMessage> transport, int batchSize) {
    this.store = Objects.requireNonNull(store);
    this.transport = Objects.requireNonNull(transport);
    this.batchSize = batchSize < 1 ? 10 : batchSize;
    this.lockedBy = "dispatcher-" + UUID.randomUUID();
  }

  public int dispatchOnce() {
    List<OutboxMessage> batch = store.claimUnpublished(batchSize, lockedBy, Duration.ofMinutes(2));
    int dispatched = 0;
    for (OutboxMessage message : batch) {
      try {
        transport.accept(message);
        store.markPublished(message);
        dispatched++;
      } catch (RuntimeException ex) {
        store.markFailed(message, ex.getMessage());
        break;
      }
    }
    return dispatched;
  }
}
