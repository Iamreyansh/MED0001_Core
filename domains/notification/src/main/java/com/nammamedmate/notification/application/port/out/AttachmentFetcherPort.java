package com.nammamedmate.notification.application.port.out;

/** Fetches attachment bytes from a URL (typically S3 pre-signed). */
public interface AttachmentFetcherPort {

  record FetchResult(boolean found, byte[] content, String contentType, String errorMessage) {
    public FetchResult {
      content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    public static FetchResult ok(byte[] content, String contentType) {
      return new FetchResult(true, content, contentType, null);
    }

    public static FetchResult notFound(String error) {
      return new FetchResult(false, new byte[0], null, error);
    }
  }

  FetchResult fetch(String url);
}
