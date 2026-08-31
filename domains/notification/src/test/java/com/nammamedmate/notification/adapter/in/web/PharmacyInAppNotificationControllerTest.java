package com.nammamedmate.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.PharmacyInAppNotificationService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyInAppNotificationControllerTest {

  @Test
  void delegates() {
    PharmacyInAppNotificationService service = mock(PharmacyInAppNotificationService.class);
    PharmacyInAppNotificationController controller =
        new PharmacyInAppNotificationController(service);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    when(service.unreadCount(owner)).thenReturn(Map.of("unread", 1L));
    assertThat(controller.count(owner).data()).containsEntry("unread", 1L);
    when(service.list(owner, true, 1, 20))
        .thenReturn(
            new PharmacyInAppNotificationService.HistoryPage(
                Map.of("notifications", java.util.List.of()), 1, 20, 0));
    ApiResponse<Map<String, Object>> listed = controller.list(owner, true, 1, 20);
    assertThat(listed.success()).isTrue();
    when(service.markAllRead(owner)).thenReturn(Map.of("updated", 2));
    assertThat(controller.markAll(owner).data()).containsEntry("updated", 2);
    UUID id = UUID.randomUUID();
    when(service.markRead(owner, id)).thenReturn(Map.of("is_read", true));
    assertThat(controller.read(owner, id).data()).containsEntry("is_read", true);
  }
}
