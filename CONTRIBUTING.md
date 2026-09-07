# Local checks and CI

Run commands from the repository root unless a working directory is shown.
Use the checked-in Gradle wrappers so local builds use the same version as CI.
JVM builds need JDK 25 plus the JDK 17/21 toolchains used by individual modules;
container tests need a running Docker-compatible daemon.

## Fast local feedback

```sh
./lint.sh                       # Projects touched by staged, unstaged or untracked files
./lint.sh --staged              # Projects touched by the index; used by pre-commit
./lint.sh --check jvm spa       # Explicit project selection
./lint.sh --format recipes      # Format the JVM recipes
./lint.sh --check --all         # All supported lint checks
```

Selection is by project, not individual file. Checks read the working tree, including
unstaged edits in a selected project. JVM recipes do not trigger the main JVM build,
and Go recipe changes do not trigger JVM recipe lint. Changes to the shared version
catalog select both JVM builds. The supported groups are `jvm`, `recipes`, `rust`,
`spa`, and `go`; process-recipe Kotlin tests are checked by the process E2E build below.

Gradle reuses its daemon, configuration cache and task outputs locally. Avoid `clean`
or `--no-daemon` during normal iteration. For a focused test run, select a module:

```sh
./gradlew :lib:stove:test
```

The lint runner's routing and failure handling can be tested without project toolchains:

```sh
python3 -m unittest discover -s scripts/tests -v
```

## Reproduce GitHub checks

```sh
# Main JVM build, all tests and one aggregate coverage report
./gradlew build :koverXmlReport

# Standalone JVM recipes
./recipes/jvm/gradlew -p recipes/jvm build e2eTest --parallel

# Go process recipes: sarama, franz, segmentio, then the sarama container test
GO_EXECUTABLE="$(command -v go)" \
  ./recipes/process/golang/go-showcase/gradlew \
  -p recipes/process/golang/go-showcase e2eTest e2eTest-container
```

The container test removes its image in a Gradle finalizer, including after a test
failure. Process tests retain their sequential ordering to bound container usage.

For the server, use Rust stable, Node.js 24, `protoc`, and the native PostgreSQL/OpenSSL
development libraries required by Cargo. Build the SPA before testing embedded assets:

```sh
cd server/stove-server/spa
npm ci
npm run check
npm test
npm run build
cd ..
just verify
```

`just verify` includes Rust formatting, Clippy, unit/API/MCP tests, acceptance tests
and the PostgreSQL load test. The SPA build is skipped inside Cargo because it was
already built above.

## Cache behavior

GitHub's Gradle setup action owns the Gradle cache; a second cache of the same
directories would duplicate downloads and uploads. CI uses the runner's Docker
daemon directly and does not archive its live `/var/lib/docker` directory.

Rust dependency artifacts are cached using the toolchain and Cargo inputs. CI turns
off incremental compilation and debug symbols to reduce cache size; local Cargo
defaults stay unchanged. Gradle and Rust cache writes are limited to `main`, with
pull requests restoring those caches. Existing workflow concurrency cancels obsolete
runs for the same branch.
