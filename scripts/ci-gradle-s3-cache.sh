#!/usr/bin/env bash
# Restore/save ~/.gradle caches + wrapper zips to the shared CI S3 bucket.
# Usage: ci-gradle-s3-cache.sh restore|save
# Env: CI_S3_BUCKET (required), AWS credentials already configured.
set -euo pipefail

CMD="${1:-}"
BUCKET="${CI_S3_BUCKET:-}"
if [ -z "$CMD" ] || [ -z "$BUCKET" ]; then
  echo "usage: CI_S3_BUCKET=… $0 restore|save" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Key from wrapper + catalog + root Gradle files (stable across PRs with same deps).
hash_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}
KEY="$(
  {
    cat gradle/wrapper/gradle-wrapper.properties
    cat gradle/libs.versions.toml 2>/dev/null || true
    cat settings.gradle.kts build.gradle.kts gradle.properties 2>/dev/null || true
  } | hash_stdin
)"
PREFIX="s3://${BUCKET}/gradle-cache/${KEY}"
ARCHIVE="gradle-user-home.tgz"

bucket_ready() {
  aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1
}

restore() {
  mkdir -p "${HOME}/.gradle"
  if ! bucket_ready; then
    echo "CI bucket ${BUCKET} missing — skip Gradle restore (apply staging CI bucket first)"
    return 0
  fi
  if ! aws s3 cp "${PREFIX}/${ARCHIVE}" "/tmp/${ARCHIVE}" 2>/dev/null; then
    echo "Gradle S3 cache miss: ${PREFIX}/${ARCHIVE}"
    return 0
  fi
  tar -xzf "/tmp/${ARCHIVE}" -C "${HOME}/.gradle"
  rm -f "/tmp/${ARCHIVE}"
  echo "Gradle S3 cache restored: ${PREFIX}/${ARCHIVE}"
}

save() {
  if ! bucket_ready; then
    echo "CI bucket ${BUCKET} missing — skip Gradle save (apply staging CI bucket first)"
    return 0
  fi
  mkdir -p "${HOME}/.gradle/caches" "${HOME}/.gradle/wrapper"
  # Skip daemon/native noise; keep deps + wrapper distributions.
  tar -czf "/tmp/${ARCHIVE}" \
    -C "${HOME}/.gradle" \
    --exclude='caches/*/fileHashes' \
    --exclude='caches/*/journal-*' \
    --exclude='caches/*/tmp' \
    --exclude='caches/build-cache-*' \
    caches wrapper 2>/dev/null || {
      echo "Nothing to save under ~/.gradle/{caches,wrapper}"
      return 0
    }
  aws s3 cp "/tmp/${ARCHIVE}" "${PREFIX}/${ARCHIVE}"
  rm -f "/tmp/${ARCHIVE}"
  echo "Gradle S3 cache saved: ${PREFIX}/${ARCHIVE}"
}

case "$CMD" in
  restore) restore ;;
  save) save ;;
  *)
    echo "usage: CI_S3_BUCKET=… $0 restore|save" >&2
    exit 1
    ;;
esac
