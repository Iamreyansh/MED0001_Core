package com.nammamedmate.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.DeviceTokenStore;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.PushLogStore;
import com.nammamedmate.notification.application.port.out.RecipientDisplayNamePort;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import com.nammamedmate.notification.domain.PushPriority;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PushSendService {

  static final int MAX_PAYLOAD_BYTES = 4096;

  private final DeviceTokenStore tokens;
  private final PushLogStore logs;
  private final FcmClientPort fcm;
  private final PreferenceGatePort preferences;
  private final RecipientDisplayNamePort names;
  private final ObjectMapper mapper;
  private final Clock clock;

  public PushSendService(
      DeviceTokenStore tokens,
      PushLogStore logs,
      FcmClientPort fcm,
      PreferenceGatePort preferences,
      RecipientDisplayNamePort names,
      ObjectMapper mapper,
      Clock clock) {
    this.tokens = tokens;
    this.logs = logs;
    this.fcm = fcm;
    this.preferences = preferences;
    this.names = names;
    this.mapper = mapper;
    this.clock = clock;
  }

  public record SendCommand(
      String recipientType,
      List<UUID> recipientIds,
      String title,
      String body,
      Map<String, Object> data,
      String imageUrl,
      String actionUrl,
      String priority,
      UUID broadcastId) {
    public SendCommand {
      recipientIds = recipientIds == null ? null : List.copyOf(recipientIds);
      data =
          data == null
              ? null
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }

  public Map<String, Object> send(SendCommand cmd) {
    NotificationUserType type;
    try {
      type = NotificationUserType.parse(cmd.recipientType());
    } catch (IllegalArgumentException e) {
      throw new AppException("INVALID_RECIPIENT_TYPE", "recipient_type not in allowed set", 422);
    }
    List<UUID> ids = cmd.recipientIds() == null ? List.of() : cmd.recipientIds();
    if (ids.isEmpty()) {
      throw new AppException("MISSING_RECIPIENT", "recipient_ids empty and no segment_id", 400);
    }
    assertPayloadSize(cmd);

    PushPriority priority = PushPriority.parseOrDefault(cmd.priority());
    boolean silent = isBlank(cmd.title()) && isBlank(cmd.body());
    Map<String, String> dataStrings = toStringMap(cmd.data());

    int sent = 0;
    int failed = 0;
    int targeted = 0;
    List<String> logIds = new ArrayList<>();
    Instant now = clock.instant();

    for (UUID recipientId : ids) {
      if (!preferences.allowsPush(recipientId, type, "TRANSACTIONAL")) {
        continue;
      }
      List<DeviceToken> active = tokens.findActiveByUser(recipientId, type);
      for (DeviceToken token : active) {
        targeted++;
        FcmClientPort.PushResult result =
            fcm.send(
                new FcmClientPort.PushRequest(
                    token.token(),
                    cmd.title(),
                    cmd.body(),
                    dataStrings,
                    cmd.imageUrl(),
                    cmd.actionUrl(),
                    priority,
                    silent));
        UUID logId = Ids.newId();
        PushLogStatus status;
        String error = null;
        String fcmId = null;
        if (result.success()) {
          status = PushLogStatus.SENT;
          fcmId = result.messageId();
          sent++;
        } else {
          status = PushLogStatus.FAILED;
          error = result.errorMessage() == null ? result.errorCode() : result.errorMessage();
          failed++;
          if (isUnregistered(result.errorCode())) {
            tokens.deactivateById(token.id(), now);
          }
        }
        logs.insert(
            new PushNotificationLog(
                logId,
                cmd.broadcastId(),
                recipientId,
                type,
                token.id(),
                cmd.title(),
                cmd.body(),
                priority,
                fcmId,
                status,
                now,
                null,
                null,
                error));
        logIds.add(logId.toString());
      }
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sent", sent);
    data.put("failed", failed);
    data.put("tokens_targeted", targeted);
    data.put("log_ids", logIds);
    return data;
  }

  public Map<String, Object> markOpened(UUID logId, UUID actorId) {
    if (logId == null) {
      throw new AppException("MISSING_LOG_ID", "log_id is required", 400);
    }
    boolean ok = logs.markOpened(logId, actorId, clock.instant());
    if (!ok) {
      throw new AppException("LOG_NOT_FOUND", "Push log not found", 404);
    }
    return Map.of("opened", true, "log_id", logId.toString());
  }

  public record LogPage(Map<String, Object> data, int page, int limit, long total) {
    public LogPage {
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }

  public LogPage listLogs(
      String recipientType,
      String status,
      Instant dateFrom,
      Instant dateTo,
      Integer page,
      Integer limit) {
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    NotificationUserType type = null;
    if (recipientType != null && !recipientType.isBlank()) {
      try {
        type = NotificationUserType.parse(recipientType);
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_RECIPIENT_TYPE", "recipient_type not in allowed set", 422);
      }
    }
    PushLogStatus st = null;
    if (status != null && !status.isBlank()) {
      try {
        st = PushLogStatus.valueOf(status.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new AppException("INVALID_STATUS", "status not in allowed set", 400);
      }
    }
    PushLogStore.Page result =
        logs.list(new PushLogStore.ListFilter(type, st, dateFrom, dateTo, p, lim));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (PushNotificationLog log : result.logs()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("log_id", log.id().toString());
      row.put("recipient_type", log.recipientType().name());
      row.put("recipient_id", log.recipientUserId().toString());
      row.put(
          "recipient_name",
          names.displayName(log.recipientUserId(), log.recipientType()).orElse(null));
      row.put("title", log.title());
      row.put("priority", log.priority().name());
      row.put("status", log.status().name());
      row.put("sent_at", log.sentAt() == null ? null : log.sentAt().toString());
      row.put("delivered_at", log.deliveredAt() == null ? null : log.deliveredAt().toString());
      row.put("opened_at", log.openedAt() == null ? null : log.openedAt().toString());
      row.put("error_message", log.errorMessage());
      rows.add(row);
    }
    return new LogPage(Map.of("logs", rows), p, lim, result.total());
  }

  void assertPayloadSize(SendCommand cmd) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", cmd.title());
    payload.put("body", cmd.body());
    payload.put("data", cmd.data() == null ? Map.of() : cmd.data());
    payload.put("image_url", cmd.imageUrl());
    payload.put("action_url", cmd.actionUrl());
    try {
      byte[] bytes = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_PAYLOAD_BYTES) {
        throw new AppException("PAYLOAD_TOO_LARGE", "Serialized payload exceeds 4 KB", 400);
      }
    } catch (JsonProcessingException e) {
      throw new AppException("INVALID_PAYLOAD", "Unable to serialize payload", 400);
    }
  }

  private static boolean isUnregistered(String code) {
    return "NOT_REGISTERED".equals(code) || "INVALID_REGISTRATION".equals(code);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static Map<String, String> toStringMap(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : data.entrySet()) {
      out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
    }
    return out;
  }
}
