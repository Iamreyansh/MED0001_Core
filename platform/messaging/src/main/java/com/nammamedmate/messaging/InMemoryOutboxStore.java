package com.nammamedmate.messaging;

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
  public List<OutboxMessage> findUnpublished(int limit) {
    return messages.stream().filter(m -> !m.published()).limit(limit).toList();
  }

  @Override
  public void markPublished(OutboxMessage message) {
    messages.replaceAll(m -> m.id().equals(message.id()) ? m.markPublished() : m);
  }

  public List<OutboxMessage> all() {
    return new ArrayList<>(messages);
  }
}
