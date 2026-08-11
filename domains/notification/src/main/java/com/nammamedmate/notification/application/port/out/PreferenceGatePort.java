package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.NotificationUserType;
import java.util.UUID;

/** Preference gate consulted by push/SMS/WhatsApp/email send paths. */
public interface PreferenceGatePort {

  boolean allowsPush(UUID userId, NotificationUserType userType, String category);

  boolean allowsSms(String toPhone, String category);

  boolean allowsWhatsApp(String toPhone);

  /**
   * Email channel + category. Non-transactional also respects {@code email_unsubscribes} inside the
   * gate when {@code toEmail} is provided.
   */
  boolean allowsEmail(UUID customerId, String toEmail, String category);
}
