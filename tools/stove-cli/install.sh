#!/usr/bin/env sh
# Stove CLI installer (https://github.com/Trendyol/stove)
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | sh
#   curl -fsSL ... | sh -s -- --version 0.23.0
#   curl -fsSL ... | sh -s -- --dir /usr/local/bin

set -eu

REPO="Trendyol/stove"
BINARY_NAME="stove"
INSTALL_DIR="${STOVE_INSTALL_DIR:-}"
VERSION=""

# ── Parse arguments ─────────────────────────────────────────────────

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --dir)     INSTALL_DIR="$2"; shift 2 ;;
    *)         echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ── Detect platform ────────────────────────────────────────────────

detect_platform() {
  OS="$(uname -s)"
  ARCH="$(uname -m)"

  case "$OS" in
    Darwin) OS_LABEL="darwin" ;;
    Linux)  OS_LABEL="linux" ;;
    *)      echo "Error: Unsupported OS: $OS"; exit 1 ;;
  esac

  case "$ARCH" in
    arm64|aarch64) ARCH_LABEL="arm64" ;;
    x86_64|amd64)  ARCH_LABEL="amd64" ;;
    *)             echo "Error: Unsupported architecture: $ARCH"; exit 1 ;;
  esac

  # Linux arm64 is not currently built
  if [ "$OS_LABEL" = "linux" ] && [ "$ARCH_LABEL" = "arm64" ]; then
    echo "Error: Linux arm64 binaries are not available yet."
    echo "Supported platforms: macOS (arm64, amd64), Linux (amd64)"
    exit 1
  fi

  PLATFORM="${OS_LABEL}-${ARCH_LABEL}"
}

# ── Resolve latest version ─────────────────────────────────────────

resolve_version() {
  if [ -n "$VERSION" ]; then
    return
  fi

  echo "Fetching latest release..."
  VERSION=$(
    curl -fsSL "https://api.github.com/repos/${REPO}/releases" \
      | grep -o '"tag_name":\s*"v[^"]*"' \
      | head -1 \
      | sed 's/.*"v\([^"]*\)".*/\1/'
  )

  if [ -z "$VERSION" ]; then
    echo "Error: Could not determine latest version. Specify --version manually."
    exit 1
  fi
}

# ── Resolve install directory ──────────────────────────────────────

resolve_install_dir() {
  if [ -n "$INSTALL_DIR" ]; then
    return
  fi

  if [ -d "/usr/local/bin" ] && [ -w "/usr/local/bin" ]; then
    INSTALL_DIR="/usr/local/bin"
  elif [ -d "$HOME/.local/bin" ]; then
    INSTALL_DIR="$HOME/.local/bin"
  else
    mkdir -p "$HOME/.local/bin"
    INSTALL_DIR="$HOME/.local/bin"
  fi
}

# ── Download and install ───────────────────────────────────────────

install() {
  ARCHIVE="stove-${VERSION}-${PLATFORM}.tar.gz"
  DOWNLOAD_URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ARCHIVE}"
  CHECKSUM_URL="${DOWNLOAD_URL}.sha256"

  TMPDIR="$(mktemp -d)"
  trap 'rm -rf "$TMPDIR"' EXIT

  echo "Downloading ${BINARY_NAME} v${VERSION} for ${PLATFORM}..."
  curl -fsSL -o "${TMPDIR}/${ARCHIVE}" "$DOWNLOAD_URL"
  curl -fsSL -o "${TMPDIR}/${ARCHIVE}.sha256" "$CHECKSUM_URL"

  # Verify checksum
  echo "Verifying checksum..."
  cd "$TMPDIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "${ARCHIVE}.sha256"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -c "${ARCHIVE}.sha256"
  else
    echo "Warning: No sha256sum or shasum found, skipping checksum verification."
  fi

  # Extract
  tar xzf "${ARCHIVE}"

  # Install
  if [ -w "$INSTALL_DIR" ]; then
    mv "${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
  else
    echo "Installing to ${INSTALL_DIR} (requires sudo)..."
    sudo mv "${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
  fi

  chmod +x "${INSTALL_DIR}/${BINARY_NAME}"
}

# ── Main ───────────────────────────────────────────────────────────

main() {
  detect_platform
  resolve_version
  resolve_install_dir
  install

  echo ""
  echo "Stove CLI v${VERSION} installed to ${INSTALL_DIR}/${BINARY_NAME}"

  # Check if install dir is in PATH
  case ":$PATH:" in
    *":${INSTALL_DIR}:"*) ;;
    *)
      echo ""
      echo "Note: ${INSTALL_DIR} is not in your PATH."
      echo "Add it with:  export PATH=\"${INSTALL_DIR}:\$PATH\""
      ;;
  esac
}

main
