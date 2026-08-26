package com.nammamedmate.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DomainEnumsTest {

  @Test
  void parsesPlatformsAudiencesAndPriorities() {
    assertThat(DevicePlatform.parse("ios")).isEqualTo(DevicePlatform.IOS);
    assertThatThrownBy(() -> DevicePlatform.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevicePlatform.parse(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevicePlatform.parse("WEB")).hasMessageContaining("INVALID_PLATFORM");

    assertThat(NotificationUserType.parse("customer")).isEqualTo(NotificationUserType.CUSTOMER);
    assertThatThrownBy(() -> NotificationUserType.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NotificationUserType.parse("X"))
        .hasMessageContaining("INVALID_RECIPIENT_TYPE");

    assertThat(PushPriority.parseOrDefault(null)).isEqualTo(PushPriority.NORMAL);
    assertThat(PushPriority.parseOrDefault("HIGH")).isEqualTo(PushPriority.HIGH);

    assertThat(BroadcastAudience.parse("ALL_RIDERS").toUserType())
        .isEqualTo(NotificationUserType.RIDER);
    assertThat(BroadcastAudience.ALL_PHARMACIES.toUserType())
        .isEqualTo(NotificationUserType.PHARMACY_STAFF);
    assertThatThrownBy(() -> BroadcastAudience.parse("")).hasMessageContaining("INVALID_AUDIENCE");
    assertThatThrownBy(() -> BroadcastAudience.parse("NOPE"))
        .hasMessageContaining("INVALID_AUDIENCE");

    assertThat(SmsCategory.parse("otp")).isEqualTo(SmsCategory.OTP);
    assertThatThrownBy(() -> SmsCategory.parse(null)).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> SmsCategory.parse(" ")).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> SmsCategory.parse("X")).hasMessageContaining("INVALID_CATEGORY");

    assertThat(SmsLogStatus.parse("sent")).isEqualTo(SmsLogStatus.SENT);
    assertThatThrownBy(() -> SmsLogStatus.parse(null)).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> SmsLogStatus.parse(" ")).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> SmsLogStatus.parse("X")).hasMessageContaining("INVALID_STATUS");

    assertThat(SmsProvider.MSG91.costRs()).isEqualByComparingTo("0.12");
    assertThat(SmsProvider.TWILIO.costRs()).isEqualByComparingTo("0.20");

    assertThat(PreferenceChangeSource.parse("user")).isEqualTo(PreferenceChangeSource.USER);
    assertThatThrownBy(() -> PreferenceChangeSource.parse(null))
        .hasMessageContaining("INVALID_CHANGE_SOURCE");
    assertThatThrownBy(() -> PreferenceChangeSource.parse(" "))
        .hasMessageContaining("INVALID_CHANGE_SOURCE");
    assertThatThrownBy(() -> PreferenceChangeSource.parse("X"))
        .hasMessageContaining("INVALID_CHANGE_SOURCE");

    assertThat(PreferenceEntityType.parse("customer")).isEqualTo(PreferenceEntityType.CUSTOMER);
    assertThatThrownBy(() -> PreferenceEntityType.parse(null))
        .hasMessageContaining("INVALID_ENTITY_TYPE");
    assertThatThrownBy(() -> PreferenceEntityType.parse(" "))
        .hasMessageContaining("INVALID_ENTITY_TYPE");
    assertThatThrownBy(() -> PreferenceEntityType.parse("X"))
        .hasMessageContaining("INVALID_ENTITY_TYPE");

    assertThat(InAppNotificationType.parse("promo")).isEqualTo(InAppNotificationType.PROMO);
    assertThat(InAppNotificationType.ORDER_UPDATE.retentionDays()).isEqualTo(90);
    assertThat(InAppNotificationType.PROMO.retentionDays()).isEqualTo(30);
    assertThat(InAppNotificationType.PROMO.canDelete()).isTrue();
    assertThat(InAppNotificationType.SYSTEM.canDelete()).isTrue();
    assertThat(InAppNotificationType.ORDER_UPDATE.canDelete()).isFalse();
    assertThat(InAppNotificationType.REFILL_REMINDER.canDelete()).isFalse();
    assertThatThrownBy(() -> InAppNotificationType.parse(null))
        .hasMessageContaining("INVALID_TYPE");
    assertThatThrownBy(() -> InAppNotificationType.parse(" ")).hasMessageContaining("INVALID_TYPE");
    assertThatThrownBy(() -> InAppNotificationType.parse("X")).hasMessageContaining("INVALID_TYPE");
  }
}
