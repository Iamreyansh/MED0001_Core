package com.nammamedmate.auth.application.port.out;

public interface LoginAuditStore {

  void save(LoginAuditRecord audit);
}
