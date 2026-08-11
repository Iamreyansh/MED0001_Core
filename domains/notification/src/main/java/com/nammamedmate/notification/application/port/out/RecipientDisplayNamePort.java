package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.NotificationUserType;
import java.util.Optional;
import java.util.UUID;

public interface RecipientDisplayNamePort {

  Optional<String> displayName(UUID userId, NotificationUserType userType);
}
