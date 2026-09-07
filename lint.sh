#!/bin/sh
#
# Lint & format all projects in the Stove monorepo.
#
#   ./lint.sh --check    Check only (git hooks, CI)
#   ./lint.sh --format   Auto-fix everything
#   ./lint.sh            Same as --check
#   ./lint.sh --staged   Check projects with staged changes (git hooks)
#
# Pass project names to scope the run (default: all changed projects, or all if --all):
#
#   ./lint.sh --format jvm spa
#   ./lint.sh --check rust recipes
#   ./lint.sh --format --all
#
# Projects: jvm, rust, spa, recipes, go

set -e

# Ensure cargo is in PATH (not always inherited by subshells)
if [ -d "$HOME/.cargo/bin" ]; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$REPO_ROOT/server/stove-server"
SPA_DIR="$SERVER_DIR/spa"
RECIPES_DIR="$REPO_ROOT/recipes/jvm"
cd "$REPO_ROOT"

# ── Parse args ────────────────────────────────────────────────────────

MODE="check"
RUN_ALL=false
STAGED_ONLY=false
PROJECTS=""

for arg in "$@"; do
  case "$arg" in
    --check)  MODE="check" ;;
    --format) MODE="format" ;;
    --all)    RUN_ALL=true ;;
    --staged) STAGED_ONLY=true ;;
    jvm|rust|spa|recipes|go) PROJECTS="$PROJECTS $arg" ;;
    *)
      echo "Usage: $0 [--check|--format] [--all|--staged] [jvm] [rust] [spa] [recipes] [go]"
      exit 1
      ;;
  esac
done

# ── Detect changed projects when no explicit selection ────────────────

detect_changed() {
  if [ "$STAGED_ONLY" = true ]; then
    CHANGED=$(git diff --cached --name-only)
  else
    CHANGED=$(git diff --name-only; git diff --cached --name-only; git ls-files --others --exclude-standard)
  fi

  if echo "$CHANGED" | grep -v '^recipes/' | grep -qE '\.(kt|kts|java)$|^(gradle/|gradlew$|gradle.properties$|detekt.yml$|\.editorconfig$)'; then
    PROJECTS="$PROJECTS jvm"
  fi
  if echo "$CHANGED" | grep -qE '^server/stove-server/.*\.(rs|toml)$|^server/stove-server/Cargo.lock$'; then
    PROJECTS="$PROJECTS rust"
  fi
  if echo "$CHANGED" | grep -qE '^server/stove-server/spa/(src/|[^/]+\.(json|jsonc|ts|js|mjs)$)'; then
    PROJECTS="$PROJECTS spa"
  fi
  if echo "$CHANGED" | grep -qE '^recipes/jvm/|^gradle/libs.versions.toml$'; then
    PROJECTS="$PROJECTS recipes"
  fi
  if echo "$CHANGED" | grep -qE '\.go$|(^|/)go\.(mod|sum)$'; then
    PROJECTS="$PROJECTS go"
  fi
}

if [ -z "$PROJECTS" ]; then
  if [ "$RUN_ALL" = true ]; then
    PROJECTS="jvm rust spa recipes go"
  else
    detect_changed
    if [ -z "$PROJECTS" ]; then
      echo "No changes detected. Use --all to lint everything."
      exit 0
    fi
  fi
fi

# ── Helpers ───────────────────────────────────────────────────────────

EXIT_CODE=0

run() {
  echo "  \$ $*"
  if ! "$@"; then
    EXIT_CODE=1
  fi
}

# Keep failure accounting in the project process, outside the directory subshell.
in_dir() (
  cd "$1" || exit 1
  shift
  "$@"
)

section() {
  echo ""
  echo "── $1 ──"
}

# ── JVM (Kotlin / Java) ──────────────────────────────────────────────

lint_jvm() {
  section "JVM (Kotlin / Java)"
  if [ "$MODE" = "format" ]; then
    run "$REPO_ROOT/gradlew" -p "$REPO_ROOT" spotlessApply detekt apiDump
  else
    run "$REPO_ROOT/gradlew" -p "$REPO_ROOT" spotlessCheck detekt apiCheck
  fi
}

# ── Rust ──────────────────────────────────────────────────────────────

lint_rust() {
  section "Rust"
  if [ "$MODE" = "format" ]; then
    run in_dir "$SERVER_DIR" cargo fmt --all
  else
    run in_dir "$SERVER_DIR" cargo fmt --all -- --check
  fi
  SKIP_SPA_BUILD=1 run in_dir "$SERVER_DIR" cargo clippy --all-targets --locked -- -D warnings
}

# ── SPA (TypeScript / React) ─────────────────────────────────────────

lint_spa() {
  section "SPA (TypeScript / React)"
  if [ ! -d "$SPA_DIR/node_modules" ]; then
    run in_dir "$SPA_DIR" npm ci
  fi
  if [ "$MODE" = "format" ]; then
    run in_dir "$SPA_DIR" npx --no-install biome check --write src
  else
    run in_dir "$SPA_DIR" npx --no-install tsc -b
    run in_dir "$SPA_DIR" npx --no-install biome check src
  fi
}

# ── Go ───────────────────────────────────────────────────────────────

lint_go() {
  section "Go"
  GO_DIRS="$REPO_ROOT/go/stove-kafka $REPO_ROOT/recipes/process/golang/go-showcase"
  for dir in $GO_DIRS; do
    if [ -d "$dir" ]; then
      if [ "$MODE" = "format" ]; then
        run gofmt -w "$dir"
      else
        if ! UNFORMATTED=$(gofmt -l "$dir"); then
          EXIT_CODE=1
        elif [ -n "$UNFORMATTED" ]; then
          echo "gofmt: files need formatting in $dir:"
          echo "$UNFORMATTED"
          EXIT_CODE=1
        fi
      fi
      run in_dir "$dir" go vet ./...
    fi
  done
}

# ── Recipes (Kotlin / Java / Scala) ──────────────────────────────────

lint_recipes() {
  section "Recipes (Kotlin / Java / Scala)"
  if [ "$MODE" = "format" ]; then
    run "$RECIPES_DIR/gradlew" -p "$RECIPES_DIR" spotlessApply
  else
    run "$RECIPES_DIR/gradlew" -p "$RECIPES_DIR" spotlessCheck
  fi
}

# ── Run selected projects concurrently ────────────────────────────────

echo "Mode: $MODE"

PIDS=""
STARTED=" "
for proj in $PROJECTS; do
  case "$STARTED" in *" $proj "*) continue ;; esac
  STARTED="$STARTED$proj "
  (
    case "$proj" in
      jvm)     lint_jvm ;;
      rust)    lint_rust ;;
      spa)     lint_spa ;;
      recipes) lint_recipes ;;
      go)      lint_go ;;
    esac
    exit $EXIT_CODE
  ) &
  PIDS="$PIDS $!"
done

EXIT_CODE=0
for pid in $PIDS; do
  if ! wait "$pid"; then
    EXIT_CODE=1
  fi
done

echo ""
if [ $EXIT_CODE -ne 0 ]; then
  echo "Some checks failed. Run './lint.sh --format' to auto-fix."
  exit 1
else
  echo "All checks passed."
fi
