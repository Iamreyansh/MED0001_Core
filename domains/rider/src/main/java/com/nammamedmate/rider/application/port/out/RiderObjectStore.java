package com.nammamedmate.rider.application.port.out;

/**
 * Port for writing rider KYC bytes to object storage.
 *
 * <p>ponytail: story multipart contract; migrate to client-side {@code
 * PresignedUrlService.createPutUrl} later. Production adapter uses S3 under private {@code
 * kyc/riders/…}; local adapter writes to disk.
 */
public interface RiderObjectStore {

  void put(String key, byte[] bytes, String contentType);

  void delete(String key);
}
