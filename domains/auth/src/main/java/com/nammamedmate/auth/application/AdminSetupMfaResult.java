package com.nammamedmate.auth.application;

import java.util.List;

public record AdminSetupMfaResult(String totpUri, String totpSecret, List<String> backupCodes) {

  public AdminSetupMfaResult {
    backupCodes = List.copyOf(backupCodes);
  }
}
