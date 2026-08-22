package com.nammamedmate.messaging;

import java.time.Duration;
import java.util.List;

public interface OutboxStore {

  void append(OutboxMessage message);

  /**
   * Claims unpublished rows with a lease so concurrent dispatchers cannot double-send.
   * Implementations that talk to Postgres must use {@code FOR UPDATE SKIP LOCKED}.
   */
  List<OutboxMessage> claimUnpublished(int limit, String lockedBy, Duration lease);

  /**
   * @deprecated use {@link #claimUnpublished(int, String, Duration)}
   */
  @Deprecated
  default List<OutboxMessage> findUnpublished(int limit) {
    return claimUnpublished(limit, "legacy", Duration.ofMinutes(1));
  }

  void markPublished(OutboxMessage message);

  void markFailed(OutboxMessage message, String error);
}
