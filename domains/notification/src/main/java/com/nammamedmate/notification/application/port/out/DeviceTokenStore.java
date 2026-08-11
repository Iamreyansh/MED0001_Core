package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenStore {

  DeviceToken upsert(
      UUID userId,
      NotificationUserType userType,
      String token,
      DevicePlatform platform,
      String deviceId,
      Instant now);

  Optional<DeviceToken> findByUserAndDevice(
      UUID userId, NotificationUserType userType, String deviceId);

  boolean deactivate(UUID userId, NotificationUserType userType, String deviceId, Instant now);

  void deactivateById(UUID tokenId, Instant now);

  List<DeviceToken> findActiveByUser(UUID userId, NotificationUserType userType);

  List<DeviceToken> findActiveByUserType(NotificationUserType userType);

  int countActiveByUserType(NotificationUserType userType);
}
