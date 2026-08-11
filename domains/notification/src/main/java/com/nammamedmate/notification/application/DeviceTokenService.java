package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.application.port.out.DeviceTokenStore;
import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.NotificationUserType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DeviceTokenService {

  private final DeviceTokenStore tokens;
  private final Clock clock;

  public DeviceTokenService(DeviceTokenStore tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  public Map<String, Object> register(
      UUID userId, NotificationUserType userType, String token, String platform, String deviceId) {
    if (token == null || token.isBlank()) {
      throw new AppException("MISSING_TOKEN", "token field is empty", 400);
    }
    if (deviceId == null || deviceId.isBlank()) {
      throw new AppException("MISSING_DEVICE_ID", "device_id is required", 400);
    }
    DevicePlatform parsed;
    try {
      parsed = DevicePlatform.parse(platform);
    } catch (IllegalArgumentException e) {
      throw new AppException("INVALID_PLATFORM", "Platform not IOS or ANDROID", 400);
    }
    var row =
        tokens.upsert(userId, userType, token.trim(), parsed, deviceId.trim(), clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("registered", true);
    data.put("device_id", row.deviceId());
    data.put("platform", row.platform().name());
    return data;
  }

  public Map<String, Object> unregister(
      UUID userId, NotificationUserType userType, String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      throw new AppException("MISSING_DEVICE_ID", "device_id is required", 400);
    }
    tokens.deactivate(userId, userType, deviceId.trim(), clock.instant());
    return Map.of("unregistered", true);
  }
}
