package com.nammamedmate.messaging;

import java.util.List;

public interface OutboxStore {

  void append(OutboxMessage message);

  List<OutboxMessage> findUnpublished(int limit);

  void markPublished(OutboxMessage message);
}
