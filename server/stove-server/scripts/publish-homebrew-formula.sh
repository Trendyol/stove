#!/usr/bin/env bash

set -euo pipefail

formula="${1:?usage: publish-homebrew-formula.sh <stove|stove-next> <version> <checksum-directory>}"
version="${2:?version is required}"
checksum_dir="${3:?checksum directory is required}"
: "${TAP_TOKEN:?TAP_TOKEN is required}"

case "$formula" in
  stove|stove-next) ;;
  *) echo "Unknown Homebrew formula: $formula" >&2; exit 1 ;;
esac

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
publish_dir="$(mktemp -d)"
trap 'rm -rf "$publish_dir"' EXIT

cp "$script_dir/../Formula/$formula.rb" "$publish_dir/$formula.rb"
sed -i "s/__VERSION__/${version}/" "$publish_dir/$formula.rb"
for platform in darwin-arm64 darwin-amd64 linux-amd64; do
  checksum="$(awk '{print $1}' "$checksum_dir/stove-${version}-${platform}.tar.gz.sha256")"
  placeholder="${platform^^}"
  placeholder="${placeholder//-/_}"
  sed -i "s/__SHA256_${placeholder}__/${checksum}/" "$publish_dir/$formula.rb"
done

# Both channels push to the same tap. Retry with a fresh clone if another
# publication wins the race; an unchanged formula makes reruns idempotent.
attempt_push() {
  local checkout_dir="$publish_dir/tap-$1"
  git clone --depth 1 "https://x-access-token:${TAP_TOKEN}@github.com/Trendyol/homebrew-trendyol-tap.git" "$checkout_dir" &&
    cp "$publish_dir/$formula.rb" "$checkout_dir/$formula.rb" &&
    git -C "$checkout_dir" add "$formula.rb" &&
    if git -C "$checkout_dir" diff --cached --quiet; then
      echo "Formula already up to date in tap — nothing to push"
    else
      git -C "$checkout_dir" config user.name "github-actions[bot]" &&
        git -C "$checkout_dir" config user.email "github-actions[bot]@users.noreply.github.com" &&
        git -C "$checkout_dir" commit -m "$formula $version" &&
        git -C "$checkout_dir" push
    fi
}

for attempt in 1 2 3; do
  if attempt_push "$attempt"; then
    exit 0
  fi
  echo "Tap push failed (attempt ${attempt}/3) — retrying with a fresh clone"
  sleep 5
done
echo "Failed to push $formula formula after 3 attempts" >&2
exit 1
