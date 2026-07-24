package com.nammamedmate.kernel.storage;

import java.time.Duration;

public interface PresignedUrlService {

  PresignedUrl createPutUrl(String key, String contentType, Duration ttl);

  PresignedUrl createGetUrl(String key, Duration ttl);

  record PresignedUrl(String url, String key, Duration ttl) {}
}
