package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.BroadcastStore;
import com.nammamedmate.notification.application.port.out.DeviceTokenStore;
import com.nammamedmate.notification.domain.BroadcastAudience;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushBroadcast;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BroadcastService {

  private final BroadcastStore broadcasts;
  private final DeviceTokenStore tokens;
  private final PushSendService push;
  private final Clock clock;

  public BroadcastService(
      BroadcastStore broadcasts, DeviceTokenStore tokens, PushSendService push, Clock clock) {
    this.broadcasts = broadcasts;
    this.tokens = tokens;
    this.push = push;
    this.clock = clock;
  }

  public Map<String, Object> enqueue(
      UUID adminId,
      String audienceRaw,
      String title,
      String body,
      Map<String, Object> data,
      Instant scheduleAt) {
    BroadcastAudience audience;
    try {
      audience = BroadcastAudience.parse(audienceRaw);
    } catch (IllegalArgumentException e) {
      throw new AppException("INVALID_AUDIENCE", "audience not in allowed set", 400);
    }
    PushSendService.SendCommand probe =
        new PushSendService.SendCommand(
            audience.toUserType().name(),
            List.of(Ids.newId()),
            title,
            body,
            data,
            null,
            null,
            "NORMAL",
            null);
    try {
      push.assertPayloadSize(probe);
    } catch (AppException ex) {
      if ("PAYLOAD_TOO_LARGE".equals(ex.code())) {
        throw new AppException("PAYLOAD_TOO_LARGE", "Push payload > 4 KB", 422);
      }
      throw ex;
    }

    Instant now = clock.instant();
    // Estimate only — AC-008 resolves recipients at execution, not schedule time.
    int estimate = tokens.countActiveByUserType(audience.toUserType());
    UUID id = Ids.newId();
    PushBroadcast row =
        broadcasts.insert(
            new PushBroadcast(
                id,
                audience,
                title,
                body,
                data == null ? Map.of() : data,
                scheduleAt,
                BroadcastStatus.QUEUED,
                estimate,
                adminId,
                now,
                null));

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("broadcast_id", row.id().toString());
    resp.put("audience", row.audience().name());
    resp.put("status", row.status().name());
    resp.put("estimated_recipients", row.estimatedRecipients());
    resp.put("scheduled_at", row.scheduleAt() == null ? null : row.scheduleAt().toString());
    resp.put("queued_at", row.createdAt().toString());
    return resp;
  }

  /** Process due QUEUED broadcasts; audience tokens resolved here (execution time). */
  public int processDue(int limit) {
    Instant now = clock.instant();
    List<PushBroadcast> due = broadcasts.findDueQueued(now, limit);
    int processed = 0;
    for (PushBroadcast b : due) {
      if (!broadcasts.claimRunning(b.id(), now)) {
        continue;
      }
      try {
        execute(b);
        broadcasts.updateStatus(b.id(), BroadcastStatus.COMPLETED, clock.instant(), null);
      } catch (RuntimeException ex) {
        broadcasts.updateStatus(b.id(), BroadcastStatus.FAILED, clock.instant(), null);
      }
      processed++;
    }
    return processed;
  }

  void execute(PushBroadcast broadcast) {
    // AC-008: resolve active tokens at execution time (not when broadcast was queued).
    NotificationUserType type = broadcast.audience().toUserType();
    List<DeviceToken> active = tokens.findActiveByUserType(type);
    Set<UUID> recipients = new LinkedHashSet<>();
    for (DeviceToken t : active) {
      recipients.add(t.userId());
    }
    broadcasts.updateStatus(broadcast.id(), BroadcastStatus.RUNNING, null, recipients.size());
    if (recipients.isEmpty()) {
      return;
    }
    push.send(
        new PushSendService.SendCommand(
            type.name(),
            new ArrayList<>(recipients),
            broadcast.title(),
            broadcast.body(),
            broadcast.data(),
            null,
            null,
            "NORMAL",
            broadcast.id()));
  }
}
