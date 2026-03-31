use std::path::Path;
use std::process::Command;

fn main() -> Result<(), Box<dyn std::error::Error>> {
  // ── Proto codegen ──────────────────────────────────────────────
  tonic_prost_build::configure()
    .build_server(true)
    .build_client(false)
    .compile_protos(
      &[
        "../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_events.proto",
        "../../lib/stove-dashboard-api/src/main/proto/stove/dashboard/v1/dashboard_service.proto",
      ],
      &["../../lib/stove-dashboard-api/src/main/proto/"],
    )?;

  // ── Version from gradle.properties ─────────────────────────────
  let gradle_props = std::fs::read_to_string("../../gradle.properties")
    .expect("Failed to read gradle.properties — is this running from tools/stove-cli?");
  let version = gradle_props
    .lines()
    .find_map(|line| line.strip_prefix("version="))
    .expect("No 'version=' line found in gradle.properties");
  println!("cargo:rustc-env=STOVE_VERSION={version}");
  println!("cargo:rerun-if-changed=../../gradle.properties");

  // ── Build SPA if needed ────────────────────────────────────────
  build_spa();

  Ok(())
}

/// Build the SPA when `spa/dist/index.html` is missing or SPA sources changed.
/// Skipped if `SKIP_SPA_BUILD=1` (useful for CI when SPA is pre-built).
fn build_spa() {
  if std::env::var("SKIP_SPA_BUILD").unwrap_or_default() == "1" {
    return;
  }

  let spa_dir = Path::new("spa");

  // Rebuild when any SPA source file changes
  println!("cargo:rerun-if-changed=spa/src");
  println!("cargo:rerun-if-changed=spa/index.html");
  println!("cargo:rerun-if-changed=spa/package.json");

  if !spa_dir.join("package.json").exists() {
    eprintln!("cargo:warning=spa/package.json not found — skipping SPA build");
    return;
  }

  // Install deps if node_modules is missing
  if !spa_dir.join("node_modules").exists() {
    run_npm(spa_dir, &["install"]);
  }

  // Always rebuild — cargo only re-runs build.rs when spa/src changes,
  // and Vite's own caching keeps no-op builds fast.
  run_npm(spa_dir, &["run", "build"]);
}

fn run_npm(dir: &Path, args: &[&str]) {
  let status = Command::new("npm")
    .args(args)
    .current_dir(dir)
    .status()
    .unwrap_or_else(|e| panic!("Failed to run npm {}: {e}", args.join(" ")));
  assert!(status.success(), "npm {} failed", args.join(" "));
}
