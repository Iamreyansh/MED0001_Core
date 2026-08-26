package com.nammamedmate.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryOutboxStore implements OutboxStore {

  private final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

  @Override
  public void append(OutboxMessage message) {
    messages.add(message);
  }

  @Override
  public List<OutboxMessage> claimUnpublished(int limit, String lockedBy, Duration lease) {
    return messages.stream().filter(m -> !m.published()).limit(limit).toList();
  }

  @Override
  public void markPublished(OutboxMessage message) {
    messages.replaceAll(m -> m.id().equals(message.id()) ? m.markPublished() : m);
  }

  @Override
  public void markFailed(OutboxMessage message, String error) {
    // leave unpublished so a later dispatch retries
  }

  public List<OutboxMessage> all() {
    return new ArrayList<>(messages);
  }
}
