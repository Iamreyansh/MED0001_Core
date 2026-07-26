package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.AdminCustomerService;
import com.nammamedmate.customer.application.AdminCustomerService.AdminListResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCustomerControllerTest {

  @Mock private AdminCustomerService service;

  private AdminCustomerController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final UUID customerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    controller = new AdminCustomerController(service);
  }

  @Test
  void list_withPaginationMeta_wrapsResponse() {
    when(service.list(admin, 1, 20, null, null, null, null, null, null, false))
        .thenReturn(
            new AdminListResult(List.of(Map.of("id", customerId)), PaginationMeta.of(1, 20, 1)));

    ApiResponse<?> response =
        controller.list(admin, 1, 20, null, null, null, null, null, null, false);

    assertThat(response.meta()).isNotNull();
    assertThat(response.data()).isInstanceOf(List.class);
    verify(service).list(admin, 1, 20, null, null, null, null, null, null, false);
  }

  @Test
  void list_exportWithoutMeta_returnsDataOnly() {
    when(service.list(admin, null, null, null, null, null, null, null, null, true))
        .thenReturn(new AdminListResult(Map.of("export_url", "https://cdn/x.csv"), null));

    ApiResponse<?> response =
        controller.list(admin, null, null, null, null, null, null, null, null, true);

    assertThat(response.meta()).isNull();
    assertThat(response.data()).isInstanceOf(Map.class);
  }

  @Test
  void get_delegatesToService() {
    when(service.get(admin, customerId)).thenReturn(Map.of("id", customerId));

    assertThat(controller.get(admin, customerId).data()).containsEntry("id", customerId);
    verify(service).get(admin, customerId);
  }

  @Test
  void flag_delegatesMappedBody() {
    when(service.flag(admin, customerId, "OTHER", "note")).thenReturn(Map.of("is_flagged", true));

    controller.flag(admin, customerId, new AdminCustomerController.FlagRequest("OTHER", "note"));

    verify(service).flag(admin, customerId, "OTHER", "note");
  }

  @Test
  void flag_nullBody_delegatesNullFields() {
    when(service.flag(admin, customerId, null, null)).thenReturn(Map.of("is_flagged", true));

    controller.flag(admin, customerId, null);

    verify(service).flag(admin, customerId, null, null);
  }

  @Test
  void unflag_delegatesToService() {
    when(service.unflag(admin, customerId)).thenReturn(Map.of("is_flagged", false));

    assertThat(controller.unflag(admin, customerId).data()).containsEntry("is_flagged", false);
    verify(service).unflag(admin, customerId);
  }

  @Test
  void notify_delegatesMappedBody() {
    when(service.notify(admin, customerId, "SMS", null, "hi", null))
        .thenReturn(Map.of("delivered", false));

    controller.notify(
        admin, customerId, new AdminCustomerController.NotifyRequest("SMS", null, "hi", null));

    verify(service).notify(admin, customerId, "SMS", null, "hi", null);
  }

  @Test
  void notify_nullBody_delegatesNullFields() {
    when(service.notify(admin, customerId, null, null, null, null))
        .thenReturn(Map.of("delivered", false));

    controller.notify(admin, customerId, null);

    verify(service).notify(admin, customerId, null, null, null, null);
  }
}
