#!/usr/bin/env bash

set -euo pipefail

image="${1:?usage: verify-image.sh <image> <expected-version>}"
expected_version="${2:?usage: verify-image.sh <image> <expected-version>}"
container_id=""
probe_dir="$(mktemp -d)"

cleanup() {
  local result=$?
  if [[ -n "$container_id" ]]; then
    if [[ "$result" -ne 0 ]]; then
      docker logs "$container_id" >&2 || true
    fi
    docker rm -f "$container_id" >/dev/null
  fi
  rm -rf "$probe_dir"
}
trap cleanup EXIT

# Exercises dynamic linking, the non-root user, writable /data, migrations,
# the embedded SPA and the metrics route in the actual runtime base image.
container_id="$(docker run --detach --publish 127.0.0.1::4040 "$image")"
address="$(docker port "$container_id" 4040/tcp)"
base_url="http://${address}"

ready=false
for ((attempt = 0; attempt < 30; attempt++)); do
  if curl --silent --fail --max-time 2 "$base_url/api/v1/health" > "$probe_dir/health.json"; then
    ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_id")" != true ]]; then
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "Runtime image failed its health check" >&2
  exit 1
fi

jq --exit-status --arg version "$expected_version" \
  '.healthy == true and .backend == "sqlite" and .stove_server_version == $version' \
  "$probe_dir/health.json" >/dev/null
curl --silent --show-error --fail --max-time 5 "$base_url/" > "$probe_dir/index.html"
grep -q '<div id="root"></div>' "$probe_dir/index.html"
curl --silent --show-error --fail --max-time 5 "$base_url/metrics" > "$probe_dir/metrics.txt"
grep -q '^stove_db_process_start_timestamp_seconds{' "$probe_dir/metrics.txt"
echo "Runtime image verified: $image ($expected_version)"
