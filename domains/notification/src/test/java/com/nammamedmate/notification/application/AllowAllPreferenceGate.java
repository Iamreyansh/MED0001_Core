package com.nammamedmate.notification.application;

import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.domain.NotificationUserType;
import java.util.UUID;

/** Test double — allow all channels/categories. */
final class AllowAllPreferenceGate implements PreferenceGatePort {

  static final PreferenceGatePort INSTANCE = new AllowAllPreferenceGate();

  @Override
  public boolean allowsPush(UUID userId, NotificationUserType userType, String category) {
    return true;
  }

  @Override
  public boolean allowsSms(String toPhone, String category) {
    return true;
  }

  @Override
  public boolean allowsWhatsApp(String toPhone) {
    return true;
  }

  @Override
  public boolean allowsEmail(UUID customerId, String toEmail, String category) {
    return true;
  }
}
