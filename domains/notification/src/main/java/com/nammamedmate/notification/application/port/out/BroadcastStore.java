package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.PushBroadcast;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BroadcastStore {

  PushBroadcast insert(PushBroadcast broadcast);

  Optional<PushBroadcast> findById(UUID id);

  List<PushBroadcast> findDueQueued(Instant now, int limit);

  boolean claimRunning(UUID id, Instant now);

  void updateStatus(
      UUID id, BroadcastStatus status, Instant executedAt, Integer estimatedRecipients);
}
