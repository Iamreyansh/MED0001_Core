package com.nammamedmate.auth.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.RolePermissionCache;
import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RbacDtoRecordsTest {

  @Test
  void copiesAndNullPermissions() {
    assertThat(new CreatePharmacyRoleRequest("a", "A", null).permissions()).isNull();
    assertThat(new CreatePharmacyRoleRequest("a", "A", List.of("orders:read")).permissions())
        .containsExactly("orders:read");
    assertThat(new UpdateRolePermissionsRequest(null).permissions()).isNull();
    assertThat(new UpdateRolePermissionsRequest(List.of("orders:read")).permissions())
        .containsExactly("orders:read");

    Instant now = Instant.parse("2026-07-26T04:00:00Z");
    PharmacyRoleRecord withNull =
        new PharmacyRoleRecord(Ids.newId(), null, "x", "X", false, null, null, now, now, null);
    assertThat(withNull.permissions()).isEmpty();
    List<String> mutable = new ArrayList<>(List.of("orders:read"));
    PharmacyRoleRecord copied =
        new PharmacyRoleRecord(Ids.newId(), null, "y", "Y", false, mutable, null, now, now, null);
    mutable.add("orders:write");
    assertThat(copied.permissions()).containsExactly("orders:read");

    assertThat(RolePermissionCache.OptionalPermissions.miss().permissions()).isEmpty();
    assertThat(RolePermissionCache.OptionalPermissions.hit(null).permissions()).isEmpty();
    assertThat(RolePermissionCache.OptionalPermissions.hit(List.of("a:b")).permissions())
        .containsExactly("a:b");
  }
}
