# Server workflows

The entry points keep release policy and publication order visible. Shared build work lives in a reusable workflow and setup action.

| File | Responsibility |
| --- | --- |
| [stove-server-ci.yml](workflows/stove-server-ci.yml) | Verify generated API types, build the SPA, lint, and test the server. |
| [stove-server-release.yml](workflows/stove-server-release.yml) | Resolve a stable release, request binary builds, upload release assets, and update Homebrew. |
| [stove-server-next.yml](workflows/stove-server-next.yml) | Resolve a snapshot, request binary and image builds, and publish the rolling channel with stale-version and retry guards. |
| [stove-server-binaries.yml](workflows/stove-server-binaries.yml) | Build the shared platform matrix, verify standalone linkage, and upload archives and checksums. Called by both release channels. |
| [stove-server-image.yml](workflows/stove-server-image.yml) | Prepare tags and labels once, build and smoke-test the image, then publish tags. |
| [setup-stove-server](actions/setup-stove-server/action.yml) | Install Node, Rust, native build dependencies, and SPA dependencies after checkout. |

Both release channels use [publish-homebrew-formula.sh](../server/stove-server/scripts/publish-homebrew-formula.sh) to render the formula from checksums and retry pushes to the tap. Each workflow selects its checksum source and controls when publication is allowed. Snapshot publication keeps its serialization, stale-version guard, existing-asset recovery, and cleanup order in the entry-point workflow.

Rust caching stays with each build: CI, standalone archives, and container binaries use different profiles or linkage requirements. The image's Ubuntu 22.04 runner is intentional for compatibility with Debian Bookworm; it does not inherit the standalone Linux runner.
