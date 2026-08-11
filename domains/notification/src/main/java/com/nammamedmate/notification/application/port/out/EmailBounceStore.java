package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.EmailBounce;
import java.util.Optional;

public interface EmailBounceStore {

  void insert(EmailBounce bounce);

  boolean hasHardBounce(String email);

  Optional<EmailBounce> findLatestHard(String email);
}
