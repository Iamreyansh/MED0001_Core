package com.nammamedmate.auth.application.port.out;

public interface AdminAuthEventStore {
  void save(AdminAuthEventRecord event);
}
