package com.nammamedmate.auth.application.port.out;

public record PermissionRecord(String resource, String action, String description, String domain) {

  public String permission() {
    return resource + ":" + action;
  }
}
