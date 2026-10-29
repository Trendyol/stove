use std::path::Path;
use std::process::Command;

fn main() -> Result<(), Box<dyn std::error::Error>> {
  build_profiling_bridge();
  // ── Proto codegen ──────────────────────────────────────────────
  println!(
    "cargo:rerun-if-changed=../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_events.proto"
  );
  println!(
    "cargo:rerun-if-changed=../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_service.proto"
  );
  tonic_prost_build::configure()
    .build_server(true)
    // The generated client is used by the black-box acceptance suite to send
    // events to the real server process over its public gRPC boundary.
    .build_client(true)
    .compile_protos(
      &[
        "../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_events.proto",
        "../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_service.proto",
      ],
      &["../../lib/stove-dashboard-api/src/main/proto/"],
    )?;

  // ── Version: env override (CI snapshot/next builds) or gradle.properties ──
  println!("cargo:rerun-if-env-changed=STOVE_VERSION");
  let version = std::env::var("STOVE_VERSION").unwrap_or_else(|_| {
    let gradle_props = std::fs::read_to_string("../../gradle.properties")
      .expect("Failed to read gradle.properties — is this running from server/stove-server?");
    gradle_props
      .lines()
      .find_map(|line| line.strip_prefix("version="))
      .expect("No 'version=' line found in gradle.properties")
      .to_string()
  });
  println!("cargo:rustc-env=STOVE_VERSION={version}");
  println!("cargo:rerun-if-changed=../../gradle.properties");

  // ── Build SPA if needed ────────────────────────────────────────
  build_spa();

  Ok(())
}

/// Build the SPA when `spa/dist/index.html` is missing or SPA sources changed.
/// Skipped if `SKIP_SPA_BUILD=1` (useful for CI when SPA is pre-built).
fn build_spa() {
  println!("cargo:rerun-if-env-changed=SKIP_SPA_BUILD");
  if std::env::var("SKIP_SPA_BUILD").unwrap_or_default() == "1" {
    return;
  }

  let spa_dir = Path::new("spa");

  // Rebuild when any SPA source file changes
  println!("cargo:rerun-if-changed=spa/src");
  println!("cargo:rerun-if-changed=spa/index.html");
  println!("cargo:rerun-if-changed=spa/package.json");
  println!("cargo:rerun-if-changed=spa/package-lock.json");

  if !spa_dir.join("package.json").exists() {
    eprintln!("cargo:warning=spa/package.json not found — skipping SPA build");
    return;
  }

  // Install deps if node_modules is missing
  if !spa_dir.join("node_modules").exists() {
    run_npm(spa_dir, &["install"]);
  }

  // Cargo already holds its build lock. Type generation runs before Cargo through
  // `npm run build` / `just build`; this internal hook only bundles the SPA.
  run_npm(spa_dir, &["run", "build:assets"]);
}

fn run_npm(dir: &Path, args: &[&str]) {
  let status = Command::new("npm")
    .args(args)
    .current_dir(dir)
    .status()
    .unwrap_or_else(|e| panic!("Failed to run npm {}: {e}", args.join(" ")));
  assert!(status.success(), "npm {} failed", args.join(" "));
}

fn build_profiling_bridge() {
  println!("cargo:rerun-if-changed=profiling-bridge");
  println!("cargo:rerun-if-env-changed=GO");
  let target = std::env::var("TARGET").unwrap();
  let goos = if target.contains("apple-darwin") {
    "darwin"
  } else if target.contains("linux") {
    "linux"
  } else {
    panic!("profiling bridge supports Linux and macOS")
  };
  let goarch = if target.starts_with("aarch64") {
    "arm64"
  } else if target.starts_with("x86_64") {
    "amd64"
  } else {
    panic!("unsupported profiling architecture: {target}")
  };
  let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
  let status = Command::new(std::env::var("GO").unwrap_or_else(|_| "go".into()))
    .current_dir("profiling-bridge")
    .env("GOOS", goos)
    .env("GOARCH", goarch)
    .env("CGO_ENABLED", "1")
    .args([
      "build",
      "-mod=readonly",
      "-trimpath",
      "-buildmode=c-archive",
      "-o",
    ])
    .arg(out.join("libstove_profiles.a"))
    .arg(".")
    .status()
    .expect("Go is required to build the embedded JFR converter");
  assert!(status.success(), "profiling bridge build failed");
  println!("cargo:rustc-link-search=native={}", out.display());
  println!("cargo:rustc-link-lib=static=stove_profiles");
  if goos == "darwin" {
    println!("cargo:rustc-link-lib=framework=CoreFoundation");
    println!("cargo:rustc-link-lib=framework=Security");
    println!("cargo:rustc-link-lib=resolv");
  } else {
    println!("cargo:rustc-link-lib=pthread");
    println!("cargo:rustc-link-lib=dl");
    println!("cargo:rustc-link-lib=m");
  }
}
