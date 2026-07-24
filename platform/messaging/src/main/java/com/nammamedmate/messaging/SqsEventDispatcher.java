package com.nammamedmate.messaging;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Drains unpublished outbox messages and hands payloads to a transport (SQS send in production).
 */
public final class SqsEventDispatcher {

  private final OutboxStore store;
  private final Consumer<OutboxMessage> transport;
  private final int batchSize;

  public SqsEventDispatcher(OutboxStore store, Consumer<OutboxMessage> transport, int batchSize) {
    this.store = Objects.requireNonNull(store);
    this.transport = Objects.requireNonNull(transport);
    this.batchSize = batchSize < 1 ? 10 : batchSize;
  }

  public int dispatchOnce() {
    List<OutboxMessage> batch = store.findUnpublished(batchSize);
    for (OutboxMessage message : batch) {
      transport.accept(message);
      store.markPublished(message);
    }
    return batch.size();
  }
}
