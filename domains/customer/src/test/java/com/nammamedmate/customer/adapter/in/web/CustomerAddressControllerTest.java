package com.nammamedmate.customer.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.CustomerAddressService;
import com.nammamedmate.customer.application.CustomerAddressService.AddressCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerAddressControllerTest {

  @Mock private CustomerAddressService service;

  private CustomerAddressController controller;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new CustomerAddressController(service);
  }

  @Test
  void list_delegates() {
    when(service.list(customer)).thenReturn(List.of(Map.of("id", "1")));

    ApiResponse<List<Map<String, Object>>> response = controller.list(customer);

    assertThat(response.data()).hasSize(1);
    verify(service).list(customer);
  }

  @Test
  void create_mapsBody() {
    when(service.create(eq(customer), any(AddressCommand.class)))
        .thenReturn(Map.of("label", "HOME"));

    ApiResponse<Map<String, Object>> response =
        controller.create(
            customer,
            new CustomerAddressController.AddressRequest(
                "HOME", "F", "A", "C", "S", "560066", 12.0, 77.0, true));

    assertThat(response.data()).containsEntry("label", "HOME");
    ArgumentCaptor<AddressCommand> captor = ArgumentCaptor.forClass(AddressCommand.class);
    verify(service).create(eq(customer), captor.capture());
    assertThat(captor.getValue().isDefault()).isTrue();
  }

  @Test
  void create_nullBody_delegatesNullCommand() {
    when(service.create(eq(customer), isNull())).thenReturn(Map.of());

    controller.create(customer, null);

    verify(service).create(customer, null);
  }

  @Test
  void update_delegates() {
    UUID id = UUID.randomUUID();
    when(service.update(eq(customer), eq(id), any(AddressCommand.class)))
        .thenReturn(Map.of("id", id));

    controller.update(
        customer,
        id,
        new CustomerAddressController.AddressRequest(
            "WORK", "F", "A", "C", "S", "560066", 12.0, 77.0, null));

    verify(service).update(eq(customer), eq(id), any(AddressCommand.class));
  }

  @Test
  void delete_delegates() {
    UUID id = UUID.randomUUID();
    when(service.delete(customer, id)).thenReturn(Map.of("message", "ok"));

    assertThat(controller.delete(customer, id).data()).containsEntry("message", "ok");
  }

  @Test
  void setDefault_delegates() {
    UUID id = UUID.randomUUID();
    when(service.setDefault(customer, id)).thenReturn(Map.of("is_default", true));

    assertThat(controller.setDefault(customer, id).data()).containsEntry("is_default", true);
  }

  @Test
  void geocode_delegates() {
    when(service.geocode(customer, 12.97, 77.59)).thenReturn(Map.of("suggested_address", Map.of()));

    controller.geocode(customer, new CustomerAddressController.GeocodeRequest(12.97, 77.59));

    verify(service).geocode(customer, 12.97, 77.59);
  }

  @Test
  void geocode_nullBody_delegatesNullCoords() {
    when(service.geocode(customer, null, null)).thenReturn(Map.of());

    controller.geocode(customer, null);

    verify(service).geocode(customer, null, null);
  }
}
