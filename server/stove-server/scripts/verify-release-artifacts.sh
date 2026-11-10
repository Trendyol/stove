#!/usr/bin/env bash

set -euo pipefail

version="${1:?usage: verify-release-artifacts.sh <version> <artifact-directory>}"
artifact_dir="${2:?artifact directory is required}"
cd "$artifact_dir"

# Every formula references both platforms. Reject an incomplete download
# before either release channel uploads assets or updates the tap.
for platform in darwin-arm64 linux-amd64; do
  archive="stove-${version}-${platform}.tar.gz"
  for artifact in "$archive" "${archive}.sha256"; do
    if [[ ! -s "$artifact" ]]; then
      echo "Missing or empty release artifact: $artifact" >&2
      exit 1
    fi
  done
  read -r checksum filename < "${archive}.sha256"
  if [[ ! "$checksum" =~ ^[[:xdigit:]]{64}$ || "$filename" != "$archive" ]]; then
    echo "Invalid checksum manifest: ${archive}.sha256" >&2
    exit 1
  fi
  shasum -a 256 --check "${archive}.sha256"
done
