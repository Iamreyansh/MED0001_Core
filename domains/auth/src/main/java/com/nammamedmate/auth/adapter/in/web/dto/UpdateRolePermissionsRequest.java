package com.nammamedmate.auth.adapter.in.web.dto;

import java.util.List;

public record UpdateRolePermissionsRequest(List<String> permissions) {
  public UpdateRolePermissionsRequest {
    permissions = permissions == null ? null : List.copyOf(permissions);
  }
}
