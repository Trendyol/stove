#!/usr/bin/env sh

set -eu

BINARY_PATH="${1:?usage: verify-release-linkage.sh <binary>}"

case "$(uname -s)" in
  Darwin)
    LINKED_LIBRARIES="$(otool -L "$BINARY_PATH")"
    ;;
  Linux)
    LINKED_LIBRARIES="$(ldd "$BINARY_PATH")"
    ;;
  *)
    echo "Unsupported release verification platform: $(uname -s)" >&2
    exit 1
    ;;
esac

printf '%s\n' "$LINKED_LIBRARIES"

if printf '%s\n' "$LINKED_LIBRARIES" | grep -Eiq '(^|[/[:space:]])lib(pq|ssl|crypto)([.][^/[:space:]]*)?([[:space:]]|$)'; then
  echo "Release binary depends on a non-system PostgreSQL/OpenSSL library" >&2
  exit 1
fi
