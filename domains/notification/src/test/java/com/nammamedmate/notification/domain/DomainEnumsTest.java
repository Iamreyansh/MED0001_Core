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

    assertThat(WhatsAppCategory.parse("utility")).isEqualTo(WhatsAppCategory.UTILITY);
    assertThat(WhatsAppCategory.UTILITY.costRs()).isEqualByComparingTo("0.85");
    assertThat(WhatsAppCategory.MARKETING.costRs()).isEqualByComparingTo("2.00");
    assertThatThrownBy(() -> WhatsAppCategory.parse(null)).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> WhatsAppCategory.parse(" ")).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> WhatsAppCategory.parse("X")).hasMessageContaining("INVALID_CATEGORY");

    assertThat(WhatsAppTemplateStatus.parse("approved")).isEqualTo(WhatsAppTemplateStatus.APPROVED);
    assertThatThrownBy(() -> WhatsAppTemplateStatus.parse(null))
        .hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> WhatsAppTemplateStatus.parse(" "))
        .hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> WhatsAppTemplateStatus.parse("X"))
        .hasMessageContaining("INVALID_STATUS");

    assertThat(WhatsAppLogStatus.parse("read")).isEqualTo(WhatsAppLogStatus.READ);
    assertThatThrownBy(() -> WhatsAppLogStatus.parse(null)).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> WhatsAppLogStatus.parse(" ")).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> WhatsAppLogStatus.parse("X")).hasMessageContaining("INVALID_STATUS");

    assertThat(WhatsAppOptoutSource.parse("wa_reply")).isEqualTo(WhatsAppOptoutSource.WA_REPLY);
    assertThatThrownBy(() -> WhatsAppOptoutSource.parse(null))
        .hasMessageContaining("INVALID_OPTOUT_SOURCE");
    assertThatThrownBy(() -> WhatsAppOptoutSource.parse(" "))
        .hasMessageContaining("INVALID_OPTOUT_SOURCE");
    assertThatThrownBy(() -> WhatsAppOptoutSource.parse("X"))
        .hasMessageContaining("INVALID_OPTOUT_SOURCE");

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

    assertThat(EmailCategory.parse("marketing")).isEqualTo(EmailCategory.MARKETING);
    assertThat(EmailCategory.TRANSACTIONAL.isTransactional()).isTrue();
    assertThat(EmailCategory.MARKETING.requiresUnsubscribeLink()).isTrue();
    assertThat(EmailCategory.LIFECYCLE.requiresUnsubscribeLink()).isTrue();
    assertThatThrownBy(() -> EmailCategory.parse(null)).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> EmailCategory.parse(" ")).hasMessageContaining("INVALID_CATEGORY");
    assertThatThrownBy(() -> EmailCategory.parse("X")).hasMessageContaining("INVALID_CATEGORY");

    assertThat(EmailLogStatus.parse("opened")).isEqualTo(EmailLogStatus.OPENED);
    assertThatThrownBy(() -> EmailLogStatus.parse(null)).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> EmailLogStatus.parse(" ")).hasMessageContaining("INVALID_STATUS");
    assertThatThrownBy(() -> EmailLogStatus.parse("X")).hasMessageContaining("INVALID_STATUS");

    assertThat(EmailBounceType.parse("hard")).isEqualTo(EmailBounceType.HARD);
    assertThatThrownBy(() -> EmailBounceType.parse(null))
        .hasMessageContaining("INVALID_BOUNCE_TYPE");
    assertThatThrownBy(() -> EmailBounceType.parse(" "))
        .hasMessageContaining("INVALID_BOUNCE_TYPE");
    assertThatThrownBy(() -> EmailBounceType.parse("X"))
        .hasMessageContaining("INVALID_BOUNCE_TYPE");

    assertThat(EmailUnsubscribeSource.parse("link_click"))
        .isEqualTo(EmailUnsubscribeSource.LINK_CLICK);
    assertThatThrownBy(() -> EmailUnsubscribeSource.parse(null))
        .hasMessageContaining("INVALID_UNSUBSCRIBE_SOURCE");
    assertThatThrownBy(() -> EmailUnsubscribeSource.parse(" "))
        .hasMessageContaining("INVALID_UNSUBSCRIBE_SOURCE");
    assertThatThrownBy(() -> EmailUnsubscribeSource.parse("X"))
        .hasMessageContaining("INVALID_UNSUBSCRIBE_SOURCE");

    assertThat(EmailProvider.SENDGRID).isNotNull();

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
