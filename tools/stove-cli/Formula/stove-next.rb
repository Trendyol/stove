# Homebrew formula for the Stove CLI dev channel.
# Managed by the stove-cli-next workflow — do not edit checksums manually.
#
# Install:
#   brew install Trendyol/trendyol-tap/stove-next
#
# Binaries come from the rolling `next` prerelease, published alongside every
# Maven snapshot. Note: the formula cannot be named `stove@next` — Homebrew
# only maps `@` to a loadable class name when a digit follows it.
class StoveNext < Formula
  desc "Local observability dashboard for Stove e2e test runs (dev channel)"
  homepage "https://github.com/Trendyol/stove"
  version "__VERSION__"
  license "Apache-2.0"

  livecheck do
    skip "Rolling dev channel published from the `next` prerelease"
  end

  conflicts_with "stove", because: "both install a `stove` binary"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/Trendyol/stove/releases/download/next/stove-#{version}-darwin-arm64.tar.gz"
      sha256 "__SHA256_DARWIN_ARM64__"
    end
    if Hardware::CPU.intel?
      url "https://github.com/Trendyol/stove/releases/download/next/stove-#{version}-darwin-amd64.tar.gz"
      sha256 "__SHA256_DARWIN_AMD64__"
    end
  end

  on_linux do
    if Hardware::CPU.intel?
      url "https://github.com/Trendyol/stove/releases/download/next/stove-#{version}-linux-amd64.tar.gz"
      sha256 "__SHA256_LINUX_AMD64__"
    end
  end

  def install
    bin.install "stove"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/stove --version")
  end
end
