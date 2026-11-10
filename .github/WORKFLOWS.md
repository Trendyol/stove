# Server workflows

The entry points keep release policy and publication order visible. Shared build work lives in a reusable workflow and setup action.

| File | Responsibility |
| --- | --- |
| [stove-server-ci.yml](workflows/stove-server-ci.yml) | Verify generated API types, build the SPA, lint, and test the server. |
| [stove-server-release.yml](workflows/stove-server-release.yml) | Resolve a stable release, request binary builds, upload release assets, and update Homebrew. |
| [stove-server-next.yml](workflows/stove-server-next.yml) | Resolve a snapshot, request binary and image builds, and publish the rolling channel with stale-version and retry guards. |
| [stove-server-binaries.yml](workflows/stove-server-binaries.yml) | Build the shared native platform matrix, verify standalone linkage and the embedded version, and upload archives and checksums. Called by both release channels; manual dispatch builds without publishing. |
| [stove-server-image.yml](workflows/stove-server-image.yml) | Prepare tags and labels once, build and smoke-test the image, then publish tags. |
| [setup-stove-server](actions/setup-stove-server/action.yml) | Install Node, Rust, native build dependencies, and SPA dependencies after checkout. |

Both release channels use [publish-homebrew-formula.sh](../server/stove-server/scripts/publish-homebrew-formula.sh) to render the formula from checksums and retry pushes to the tap. Each workflow selects its checksum source and controls when publication is allowed. Snapshot publication keeps its serialization, stale-version guard, existing-asset recovery, and cleanup order in the entry-point workflow.

Standalone macOS builds support Apple Silicon only, using `macos-26` natively because the embedded Go profiler uses cgo. The Homebrew formulas require arm64 on macOS, and the shell installer rejects Intel Macs before downloading. Linux x86_64 is also supported. `MACOSX_DEPLOYMENT_TARGET=14.0` keeps the deployment baseline explicit when the build image changes. GitHub retired macOS 14 runners on November 2, 2026; changing runner labels requires a new workflow run from the updated commit, since reruns use the original workflow revision.

Rust caching stays with each build: CI, standalone archives, and container binaries use different profiles or linkage requirements. The Rust cache action includes the toolchain and Cargo inputs, with separate keys for each runner and target. Standalone Linux builds pin Ubuntu 24.04 to preserve the glibc baseline. The image build pins Ubuntu 22.04 for compatibility with Debian Bookworm; it does not inherit the standalone Linux runner. Upgrade these pins deliberately, checking native-library compatibility before reusing build artifacts. Packaging builds the SPA from checked-in API types; server CI checks their freshness.

Binary builds use `fail-fast: false`, so a platform failure does not cancel its siblings. Artifact uploads fail on missing files and replace artifacts when a job is retried. Both release channels run [verify-release-artifacts.sh](../server/stove-server/scripts/verify-release-artifacts.sh) before uploading, requiring every archive and verifying its SHA-256 checksum. Publication still requires the whole binary matrix to succeed. The snapshot's final status job runs even when a dependency fails or is cancelled and reports binary, image, and Homebrew publication results in its summary.

To validate runner or packaging changes, dispatch **Build Stove Server Binaries** with a version; this only uploads workflow artifacts. To recover a snapshot whose Maven publication already succeeded, start a new **Stove Server Next** run from the corrected ref and explicitly pass the original Maven snapshot version. This rebuilds the server without republishing Maven, and the existing stale-version guard prevents an older snapshot from replacing a newer Homebrew channel. Maven snapshot publishing uses the checked-in Gradle wrapper so local and CI Gradle versions agree.
