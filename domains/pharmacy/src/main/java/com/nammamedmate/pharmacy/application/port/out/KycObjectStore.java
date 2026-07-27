package com.nammamedmate.pharmacy.application.port.out;

/**
 * Port for writing KYC document bytes to object storage. ponytail: story multipart contract;
 * migrate to client-side presign PUT later. Production adapter uses S3; local adapter writes to
 * /tmp/medmate-kyc/.
 */
public interface KycObjectStore {

  /** Store bytes at the given key with specified content type. */
  void put(String key, byte[] bytes, String contentType);

  /** Best-effort delete when malware is detected. */
  void delete(String key);
}
