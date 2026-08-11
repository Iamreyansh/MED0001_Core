package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import com.nammamedmate.notification.domain.PreferenceChangeSource;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EmailUnsubscribeService {

  private final EmailUnsubscribeStore unsubscribes;
  private final UnsubscribeTokenService tokens;
  private final PreferenceService preferences;
  private final Clock clock;

  public EmailUnsubscribeService(
      EmailUnsubscribeStore unsubscribes,
      UnsubscribeTokenService tokens,
      PreferenceService preferences,
      Clock clock) {
    this.unsubscribes = unsubscribes;
    this.tokens = tokens;
    this.preferences = preferences;
    this.clock = clock;
  }

  public Map<String, Object> unsubscribe(String token) {
    UnsubscribeTokenService.ParsedToken parsed = tokens.parse(token);
    Instant now = clock.instant();
    if (unsubscribes.isActivelyUnsubscribed(parsed.email())) {
      throw new AppException("ALREADY_UNSUBSCRIBED", "Customer already unsubscribed", 409);
    }
    unsubscribes.upsertActive(Ids.newId(), parsed.email(), EmailUnsubscribeSource.LINK_CLICK, now);
    if (parsed.customerId() != null) {
      preferences.disableCustomerEmailPromotions(
          parsed.customerId(), PreferenceChangeSource.UNSUBSCRIBE_LINK, null);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("unsubscribed", true);
    data.put("email", parsed.email());
    data.put(
        "message",
        "You have been unsubscribed from marketing emails. You will continue to receive order and account updates.");
    return data;
  }
}
