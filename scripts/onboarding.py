"""Small platform checks and migration of the former Gradle-managed Git hook."""

import argparse
from pathlib import Path
import platform
import shutil
import subprocess


def install_hooks():
    # Only remove the old default-directory override, never a custom hooks path.
    configured = subprocess.run(
        ["git", "config", "--local", "--get", "core.hooksPath"],
        capture_output=True, text=True, check=False,
    )
    if configured.returncode not in (0, 1):
        raise RuntimeError(configured.stderr.strip())
    if configured.returncode == 0:
        common = subprocess.check_output(
            ["git", "rev-parse", "--git-common-dir"], text=True,
        ).strip()
        if Path(configured.stdout.strip()).resolve() == (Path(common) / "hooks").resolve():
            subprocess.run(["git", "config", "--local", "--unset", "core.hooksPath"], check=True)
    subprocess.run(["lefthook", "install"], check=True)


def doctor():
    checks = [
        ("C compiler", ["cc", "--version"]),
        ("Protocol Buffers compiler", ["protoc", "--version"]),
        ("PostgreSQL development library", ["pkg-config", "--exists", "libpq"]),
        ("OpenSSL development library", ["pkg-config", "--exists", "openssl"]),
        ("Docker daemon", ["docker", "info"]),
    ]
    missing = []
    for label, command in checks:
        available = False
        if shutil.which(command[0]):
            try:
                available = subprocess.run(
                    command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=15, check=False,
                ).returncode == 0
            except subprocess.TimeoutExpired:
                pass
        print(f"{'OK' if available else 'MISSING'}: {label}")
        if not available:
            missing.append(label)

    if missing:
        print("\nThese prerequisites are needed for the full build; see CONTRIBUTING.md.")
        if platform.system() == "Darwin":
            print("Native libraries: brew install libpq openssl@3 pkgconf")
            print("C compiler: xcode-select --install")
        else:
            print("Debian/Ubuntu native dependencies: sudo apt-get install build-essential libpq-dev libssl-dev pkg-config")
        print("Install/start a Docker-compatible daemon, then rerun: mise run doctor")
        return 1
    print("\nLocal prerequisites are ready. Try: mise exec -- just lint")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["hooks", "doctor"])
    args = parser.parse_args()
    if args.command == "hooks":
        install_hooks()
        return 0
    return doctor()


if __name__ == "__main__":
    raise SystemExit(main())
