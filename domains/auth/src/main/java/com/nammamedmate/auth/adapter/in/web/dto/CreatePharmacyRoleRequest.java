package com.nammamedmate.auth.adapter.in.web.dto;

import java.util.List;

public record CreatePharmacyRoleRequest(
    String name, String display_name, List<String> permissions) {

  public CreatePharmacyRoleRequest {
    permissions = permissions == null ? null : List.copyOf(permissions);
  }

  public String displayName() {
    return display_name;
  }
}
