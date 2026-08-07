package com.nammamedmate.catalogue.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MappingService;
import com.nammamedmate.catalogue.application.MappingService.PageResult;
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
class MappingControllersTest {

  @Mock private MappingService service;

  private PharmacyCatalogueMappingController pharmacy;
  private AdminCatalogueMappingController admin;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    pharmacy = new PharmacyCatalogueMappingController(service);
    admin = new AdminCatalogueMappingController(service);
  }

  @Test
  void pharmacyEndpoints() {
    when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageResult(Map.of("mappings", List.of()), PaginationMeta.of(1, 20, 0)));
    ApiResponse<Map<String, Object>> list =
        pharmacy.list(owner, true, true, null, "a", "name", "asc", 1, 20);
    assertThat(list.success()).isTrue();

    when(service.create(any(), any(), any(), any())).thenReturn(Map.of("mapping_id", "x"));
    assertThat(pharmacy.create(owner, null).data()).containsEntry("mapping_id", "x");
    pharmacy.create(
        owner, new PharmacyCatalogueMappingController.CreateRequest(UUID.randomUUID(), 10, 1));

    UUID mid = UUID.randomUUID();
    when(service.update(any(), eq(mid), any(), any(), any())).thenReturn(Map.of("ok", true));
    pharmacy.update(owner, mid, null);
    pharmacy.update(owner, mid, new PharmacyCatalogueMappingController.UpdateRequest(9, 2, false));

    when(service.delete(any(), eq(mid))).thenReturn(Map.of("deleted", true));
    assertThat(pharmacy.delete(owner, mid).data()).containsEntry("deleted", true);
  }

  @Test
  void adminEndpoints() {
    UUID master = UUID.randomUUID();
    when(service.adminList(any(), eq(master), any(), any(), any(), any(), any()))
        .thenReturn(new PageResult(Map.of("pharmacies", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(admin.pharmacyMappings(superAdmin, master, null, null, true, 1, 20).success())
        .isTrue();

    when(service.bulkMap(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("job_id", "j"));
    assertThat(admin.bulkMap(superAdmin, null).data()).containsEntry("job_id", "j");
    admin.bulkMap(
        superAdmin,
        new AdminCatalogueMappingController.BulkMapRequest(
            master, List.of(UUID.randomUUID()), true, null, 0));
    verify(service).bulkMap(eq(superAdmin), eq(master), any(), eq(true), isNull(), eq(0));
  }
}
