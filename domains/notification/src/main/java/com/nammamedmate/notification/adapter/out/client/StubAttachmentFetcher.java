package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Attachment fetcher stub — configure 404 vs OK per URL. */
public class StubAttachmentFetcher implements AttachmentFetcherPort {

  private final Map<String, FetchResult> overrides = new ConcurrentHashMap<>();
  private boolean defaultNotFound;

  public void setDefaultNotFound(boolean value) {
    defaultNotFound = value;
  }

  public void putOk(String url, byte[] content, String contentType) {
    overrides.put(url, FetchResult.ok(content, contentType));
  }

  public void putNotFound(String url) {
    overrides.put(url, FetchResult.notFound("404 Not Found"));
  }

  public void reset() {
    overrides.clear();
    defaultNotFound = false;
  }

  @Override
  public FetchResult fetch(String url) {
    if (url != null && overrides.containsKey(url)) {
      return overrides.get(url);
    }
    if (defaultNotFound) {
      return FetchResult.notFound("404 Not Found");
    }
    return FetchResult.ok(("pdf-for-" + url).getBytes(StandardCharsets.UTF_8), "application/pdf");
  }
}
