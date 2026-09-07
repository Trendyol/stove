use std::path::Path;
use std::process::Command;

fn main() -> Result<(), Box<dyn std::error::Error>> {
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
