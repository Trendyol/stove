# Contributing to Stove

This guide takes you from a fresh clone to a tested contribution. For an overview
of Stove, start with the [README](README.md); for using Stove in an application,
see the [user documentation](https://trendyol.github.io/stove/).

Run commands from the repository root unless a working directory is shown. The
examples use `mise exec --` so they work without changing your shell configuration.

## First-time setup

### 1. Clone the repository

If you plan to submit a pull request without write access, fork
[trendyol/stove](https://github.com/trendyol/stove) first and clone your fork instead.
Otherwise:

```sh
git clone https://github.com/trendyol/stove.git
cd stove
```

You need Git, internet access for tool and dependency downloads, and
[mise](https://mise.jdx.dev/installing-mise.html) 2026.9.1 or newer. The commands
below target macOS and Linux. On Windows, use WSL2 with the repository and tools
inside Linux, and enable your Docker runtime's WSL integration.

### 2. Install system prerequisites

Mise manages language runtimes and development tools. A C compiler, native
libraries, and a Docker-compatible daemon must be installed separately for the
full repository build. Install and start your Docker runtime before setup; check
that `docker info` succeeds from the same terminal you will use for development.

On macOS, with [Homebrew](https://brew.sh/):

```sh
brew install mise libpq openssl@3 pkgconf
# Only if Xcode Command Line Tools are not already installed:
xcode-select --install
```

On Debian/Ubuntu, install mise using its
[installation instructions](https://mise.jdx.dev/installing-mise.html), then:

```sh
sudo apt-get update
sudo apt-get install build-essential libpq-dev libssl-dev pkg-config
```

Other Linux distributions need the equivalent compiler, PostgreSQL client
library headers, OpenSSL headers, and pkg-config packages. You do not need to
start a system PostgreSQL service for the test containers. On macOS, the project
makes Homebrew's libpq and OpenSSL paths available inside the mise environment.

### 3. Run onboarding

From your clone:

```sh
mise trust
mise run setup
```

`mise trust` allows this repository's configuration and setup scripts to run.
Cloning alone does **not** install or enable Git hooks: each newcomer must run
setup once in their checkout.

Setup performs the following steps:

1. Installs the pinned tools from [mise.toml](mise.toml): Java 25/21/17, Node.js,
   Go, Rust with rustfmt and Clippy, just, Lefthook, protoc, and Python.
2. Installs the pre-commit hook and migrates the former Gradle hook's default-path
   setting when present.
3. Installs dashboard dependencies from the npm lockfile.
4. Checks the C compiler, protoc, PostgreSQL/OpenSSL libraries, and Docker daemon.

Successful setup ends with all prerequisite checks reporting `OK`. The first run
can take a while because it downloads several toolchains. It is safe to rerun
`mise run setup` after pulling updates. Running `mise install` directly also
installs the Git hook, but does not install dashboard dependencies or run doctor.

The setup only adjusts repository-local hook configuration. Git identity and
personal preferences stay unchanged. Custom hook paths are preserved; Lefthook
reports conflicts for manual resolution. If doctor fails, completed installation
steps remain usable. Fix the reported prerequisites and run `mise run doctor`.

### 4. Run your first check

```sh
mise exec -- just --list
mise run doctor
# Example: check the Kotlin/JVM projects
mise exec -- just lint-jvm
```

Choose the check for your area from the table below. A first JVM or Rust check
also downloads dependencies and can take longer than later runs. Lint checks are
separate from the test suites.

## How the development tools fit together

| Tool | Purpose | Configuration |
| --- | --- | --- |
| mise | Installs pinned tools and supplies the project environment | [mise.toml](mise.toml) |
| just | Provides named commands for checks, formatting, and setup | [justfile](justfile) |
| Lefthook | Runs the relevant checks before a commit | [lefthook.yml](lefthook.yml) |
| Gradle wrappers | Build and test the JVM projects using their checked-in Gradle version | [gradlew](gradlew), [JVM recipes wrapper](recipes/jvm/gradlew) |

You can optionally [activate mise in your shell](https://mise.jdx.dev/getting-started.html#activate-mise)
to type `just lint-jvm` instead of `mise exec -- just lint-jvm`. Git hooks invoke
`mise exec` themselves, so shell activation is optional; `mise` must still be on
`PATH` in the terminal or Git client that creates the commit.

Keep personal mise overrides in the ignored `mise.local.toml`. Use the shared
versions when reproducing a failure before attributing it to the repository.

## Find the right project

| Area | Location | Starting check |
| --- | --- | --- |
| Core library and system integrations | [lib/](lib/) | `mise exec -- just lint-jvm` |
| Framework, process, and container runners | [starters/](starters/) | `mise exec -- just lint-jvm` |
| JUnit/Kotest support and Gradle plugins | [test-extensions/](test-extensions/), [plugins/](plugins/) | `mise exec -- just lint-jvm` |
| JVM example applications | [examples/](examples/) | `mise exec -- just lint-jvm` |
| Standalone JVM recipes | [recipes/jvm/](recipes/jvm/) | `mise exec -- just lint-recipes` |
| Go library and process examples | [go/stove-kafka/](go/stove-kafka/), [recipes/process/golang/go-showcase/](recipes/process/golang/go-showcase/) | `mise exec -- just lint-go` |
| Rust dashboard server | [server/stove-server/](server/stove-server/) | `mise exec -- just lint-rust` |
| React dashboard | [server/stove-server/spa/](server/stove-server/spa/) | `mise exec -- just lint-spa` |
| User documentation | [docs/](docs/), [mkdocs.yml](mkdocs.yml) | See [documentation development](#documentation-development) |
| Shared build and onboarding | [build-logic/](build-logic/), [gradle/](gradle/), [scripts/](scripts/) | Run affected groups and the onboarding tests below |

The main JVM project, JVM recipes, and Go process recipe are separate Gradle
builds. Use each build's wrapper and working directory as shown in the test
commands below. Process-recipe Kotlin tests are covered by the process E2E build,
not `lint-recipes` or `lint-go`.

For a new system integration, read [Writing custom systems](docs/writing-custom-systems.md).
Existing modules and tests are useful examples of the repository's conventions.

## Editor setup

For Kotlin/JVM work, open the root Gradle project in an IDE with Kotlin support.
Use the checked-in Gradle wrapper and **JDK 25 as the Gradle JVM**. Individual
modules also use JDK 17 and 21; mise exports `JAVA_HOME`, `STOVE_JDK21`, and
`STOVE_JDK17`, which the Gradle configuration uses to discover those installations.

```sh
mise which java
mise exec -- ./gradlew javaToolchains
```

The first command identifies the Java executable in the managed JDK 25; its JDK
home is the directory above `bin`. Configure that home as the IDE's Gradle JVM.
An IDE launched from the desktop may not inherit mise's environment. Launch it
from a mise-enabled terminal or configure the additional JDK locations in its
Gradle environment, then reimport the project. Import standalone recipe builds
separately when working on them.

For Rust and dashboard work, point the editor at the corresponding server or SPA
directory and use the mise-managed Rust and Node.js tools. Enable EditorConfig
support for the shared [.editorconfig](.editorconfig) settings; the command-line
checks remain the reference for formatting and analysis.

## Daily workflow and Git hooks

Create a branch for your change, make focused edits, then format and check the
area you touched. For example, for JVM work:

```sh
mise exec -- just format-jvm
mise exec -- just lint-jvm
mise exec -- ./gradlew :lib:stove:test
```

Replace the test module with the one you changed. To discover Gradle projects,
run `mise exec -- ./gradlew projects`. Gradle test reports are written under each
module's `build/reports/tests/` directory.

Useful root commands:

```sh
mise exec -- just lint                     # Check every group, even on a clean tree
mise exec -- just lint-jvm lint-spa         # Check selected groups
mise exec -- just format                   # Apply all formatters
mise exec -- just format-recipes           # Format only the JVM recipes
mise exec -- just api-dump                 # Deliberately update JVM API baselines
mise exec -- lefthook run pre-commit       # Check groups selected by staged paths
```

Each group (`jvm`, `recipes`, `rust`, `spa`, `go`) has a `lint-` and a `format-`
command. Formatting applies the tools' formatting and automatic fixes without
running Detekt, Clippy, typechecks, Go vet, or API checks. Review the diff and rerun
lint after formatting. Update public API baselines only for intentional API
changes, and include the resulting `.api` diff for review.

The JVM command requests Spotless, Detekt, and API checks. The current Gradle
configuration disables Detekt on Java 25, so `detekt SKIPPED` is expected with
the default toolchain; a successful run does not mean Detekt analyzed the code.

When you commit, pre-commit checks run in parallel. Staged paths select whole
project groups, and those checks read the **working tree, including unstaged
edits** in a selected project. Hooks do not format or stage files. Review and
stage any fixes before retrying a commit.

JVM recipe changes do not select the main JVM build; Go recipe changes do not
select JVM recipe lint. The shared version catalog selects both JVM builds.
Changes to the root justfile, Lefthook configuration, or mise configuration and
environment script select all groups. A documentation-only commit may have no
matching lint jobs. Passing the hook does not replace running relevant tests.

After pulling changes to tool versions or setup configuration, rerun
`mise run setup`. For only an npm lockfile change, use
`mise exec -- just setup-spa`. To reinstall hooks independently, run
`mise exec -- just hooks`.

## Run tests and reproduce CI checks

CI workflows in [.github/workflows/](.github/workflows/) are the source of truth
for checks and tool setup. The commands below run their main checks locally with
the pinned mise environment. Container-backed tests require Docker and download
images on first use. Start with your affected module before running broader suites.

### JVM and recipes

```sh
# Main JVM build, tests, and aggregate coverage report
mise exec -- ./gradlew build :koverXmlReport

# Standalone JVM recipes
mise exec -- ./recipes/jvm/gradlew -p recipes/jvm build e2eTest --parallel

# Go process recipes and the sarama container test
mise exec -- sh -c '
  export GO_EXECUTABLE="$(command -v go)"
  ./recipes/process/golang/go-showcase/gradlew \
    -p recipes/process/golang/go-showcase e2eTest e2eTest-container
'
```

The Go executable is resolved inside mise's environment so Gradle uses the managed
version. The container test removes its image in a Gradle finalizer, including
after a test failure. Process tests run sequentially to bound container usage.

### Dashboard server and SPA

Build the SPA before testing embedded assets. These commands all run from the
repository root:

```sh
mise exec -- just setup-spa
mise exec -- npm --prefix server/stove-server/spa run check:api
mise exec -- just lint-spa
mise exec -- npm --prefix server/stove-server/spa test
mise exec -- npm --prefix server/stove-server/spa run build
mise exec -- just --justfile server/stove-server/justfile verify
```

The server's `verify` command includes Rust formatting, Clippy, unit/API/MCP tests,
acceptance tests, and the PostgreSQL load test. Cargo skips rebuilding the SPA
because it was built above. If you change the REST API, regenerate the dashboard
types with `mise exec -- npm --prefix server/stove-server/spa run generate:api`
and review the generated diff.

To try the dashboard locally with its PostgreSQL-backed server:

```sh
mise exec -- just --justfile server/stove-server/justfile postgres-up
# Stop the stack while keeping PostgreSQL data:
mise exec -- just --justfile server/stove-server/justfile postgres-down
```

The stack builds from source and serves the dashboard at <http://localhost:4040>
by default. For frontend iteration, leave the stack running and, in another
terminal, run `mise exec -- npm --prefix server/stove-server/spa run dev`. Open
the URL printed by Vite; its `/api` requests proxy to the server on port 4040.
See the [dashboard guide](docs/Components/18-dashboard.md) for usage.

### Onboarding and hook changes

The integration tests use temporary repositories and stubbed language tools to
verify onboarding, command execution, and hook routing:

```sh
mise exec -- python3 -m unittest discover -s scripts/tests -v
```

### Documentation development

Install the documentation dependencies in a local virtual environment:

```sh
mise exec -- python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-docs.txt
.venv/bin/mkdocs serve
```

Open the address printed by MkDocs. Before submitting documentation changes, run
`.venv/bin/mkdocs build --strict`, matching the documentation workflow. The virtual
environment and generated `site/` directory are ignored by Git. The documentation
hooks also regenerate the tracked `docs/assets/data/setup.json`; review any changes
to it when editing the data behind the setup wizard.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| `mise` is missing during setup or a commit | Install mise and make it available on `PATH` to your terminal or Git client. Restart the client after changing its environment. Shell activation alone is not required by the hook. |
| Mise says the repository is untrusted | Review the checkout and run `mise trust` from its root. |
| Doctor reports a missing prerequisite | Install the system packages listed above or start Docker, then run `mise run doctor`. Full setup need not be repeated for this check. |
| Docker is installed but tests cannot connect | Run `docker info` in the same environment as the tests; verify the active Docker context and any runtime-specific Testcontainers configuration. See [troubleshooting](docs/troubleshooting.md). |
| Gradle uses the wrong Java version or cannot find a toolchain | Run through `mise exec --`, inspect `./gradlew javaToolchains` in that environment, and check the IDE's Gradle JVM/environment settings. |
| Rust cannot find libpq, OpenSSL, or protoc | Install the native packages and rerun doctor. On macOS, use the mise environment so the Homebrew library paths are applied. |
| SPA tools or dependencies are missing | Run `mise exec -- just setup-spa`, especially after a lockfile update. |
| A formatting check fails | Run the relevant `just format-GROUP` through mise, inspect the diff, and rerun `lint-GROUP`. Stage the fixes if preparing a commit. |
| `apiCheck` fails | Decide whether the public API change is intended. For an intentional change, run `mise exec -- just api-dump` and review the baseline diff; otherwise fix the API regression. |
| Hook installation reports a custom hooks path | Inspect `git config --show-origin --get core.hooksPath`. Integrate Lefthook with your existing hook setup or remove the override only if you no longer need it, then rerun `mise exec -- just hooks`. |
| A commit checks more files than expected | Staged paths select whole groups, and shared configuration can select all groups. Unstaged edits in those groups are checked too. |

When reporting a build or setup issue, include your OS, the command that failed,
the relevant output, and whether `mise run doctor` succeeds. Include Docker runtime
information for container-related failures.

## Prepare a pull request

- Keep the change focused and explain the problem and resulting behavior. Link a
  related issue when there is one; discuss large design changes in an issue first.
- Follow nearby code conventions and add or update tests for changed behavior.
- Run the relevant formatting, lint, and test commands. Record what passed and any
  checks you could not run in the pull request description.
- Update user documentation and examples when public behavior changes. Review
  intentional public API baseline and generated-file changes alongside the code.
- Inspect your diff before committing. Keep local environment overrides, editor
  settings, and build outputs out of the contribution.
- Open the pull request against `main` and address applicable CI failures and
  review feedback.

## Build caches

Gradle reuses its daemon, configuration cache, and task outputs locally. Avoid
`clean` or `--no-daemon` during normal iteration unless diagnosing a specific issue.

GitHub's Gradle setup action owns the Gradle cache; a second cache of the same
directories would duplicate downloads and uploads. CI uses the runner's Docker
daemon directly and does not archive its live `/var/lib/docker` directory.

Rust dependency artifacts are cached using the toolchain and Cargo inputs. CI turns
off incremental compilation and debug symbols to reduce cache size; local Cargo
defaults stay unchanged. Gradle and Rust cache writes are limited to `main`, with
pull requests restoring those caches. Workflow concurrency cancels obsolete runs
for the same branch.
