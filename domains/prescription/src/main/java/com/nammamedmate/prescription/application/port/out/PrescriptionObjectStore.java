package com.nammamedmate.prescription.application.port.out;

public interface PrescriptionObjectStore {

  void put(String key, byte[] bytes, String contentType);

  void delete(String key);
}
