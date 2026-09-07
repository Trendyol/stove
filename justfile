default:
    @just --list

# Install Git hooks and migrate the old Gradle hook configuration.
hooks:
    python3 scripts/onboarding.py hooks

# Check every project, including on a clean working tree.
lint: lint-jvm lint-rust lint-spa lint-recipes lint-go

# Apply formatters without updating API baselines.
format: format-jvm format-rust format-spa format-recipes format-go

# Kotlin formatting, static analysis, and public API compatibility.
lint-jvm:
    ./gradlew spotlessCheck detekt apiCheck

format-jvm:
    ./gradlew spotlessApply

# Deliberately update public API baselines; review the resulting diff.
api-dump:
    ./gradlew apiDump

# Reuse the server's native recipes.
lint-rust:
    just --justfile server/stove-server/justfile fmt-check clippy

format-rust:
    just --justfile server/stove-server/justfile fmt

# Install SPA dependencies after checkout or lockfile changes.
setup-spa:
    npm --prefix server/stove-server/spa ci

lint-spa:
    npm --prefix server/stove-server/spa run typecheck
    npm --prefix server/stove-server/spa run check

format-spa:
    npm --prefix server/stove-server/spa run format

lint-recipes:
    ./recipes/jvm/gradlew -p recipes/jvm spotlessCheck

format-recipes:
    ./recipes/jvm/gradlew -p recipes/jvm spotlessApply

lint-go:
    #!/bin/sh
    set -eu
    unformatted=$(gofmt -l go/stove-kafka recipes/process/golang/go-showcase)
    if [ -n "$unformatted" ]; then
        printf 'Go files need formatting:\n%s\n' "$unformatted"
        exit 1
    fi
    (cd go/stove-kafka && go vet ./...)
    (cd recipes/process/golang/go-showcase && go vet ./...)

format-go:
    gofmt -w go/stove-kafka recipes/process/golang/go-showcase
