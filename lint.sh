#!/bin/sh
#
# Lint & format all projects in the Stove monorepo.
#
#   ./lint.sh --check    Check only (git hooks, CI)
#   ./lint.sh --format   Auto-fix everything
#   ./lint.sh            Same as --check
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
CLI_DIR="$REPO_ROOT/tools/stove-cli"
SPA_DIR="$CLI_DIR/spa"
RECIPES_DIR="$REPO_ROOT/recipes/jvm"

# ── Parse args ────────────────────────────────────────────────────────

MODE="check"
RUN_ALL=false
PROJECTS=""

for arg in "$@"; do
  case "$arg" in
    --check)  MODE="check" ;;
    --format) MODE="format" ;;
    --all)    RUN_ALL=true ;;
    jvm|rust|spa|recipes|go) PROJECTS="$PROJECTS $arg" ;;
    *)
      echo "Usage: $0 [--check|--format] [--all] [jvm] [rust] [spa] [recipes]"
      exit 1
      ;;
  esac
done

# ── Detect changed projects when no explicit selection ────────────────

detect_changed() {
  # In a git hook context, use cached diff; otherwise use working tree diff
  if git diff --cached --name-only 2>/dev/null | grep -q .; then
    DIFF_CMD="git diff --cached --name-only"
  else
    DIFF_CMD="git diff --name-only HEAD"
  fi

  CHANGED=$($DIFF_CMD 2>/dev/null || true)

  if echo "$CHANGED" | grep -qE '\.(kt|kts|java)$'; then
    PROJECTS="$PROJECTS jvm"
  fi
  if echo "$CHANGED" | grep -qE "^tools/stove-cli/.*\.(rs|toml)$"; then
    PROJECTS="$PROJECTS rust"
  fi
  if echo "$CHANGED" | grep -qE "^tools/stove-cli/spa/src/.*\.(ts|tsx|js|jsx|css)$"; then
    PROJECTS="$PROJECTS spa"
  fi
  if echo "$CHANGED" | grep -qE "^recipes/"; then
    PROJECTS="$PROJECTS recipes"
  fi
  if echo "$CHANGED" | grep -qE '\.go$'; then
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

section() {
  echo ""
  echo "── $1 ──"
}

# ── JVM (Kotlin / Java) ──────────────────────────────────────────────

lint_jvm() {
  section "JVM (Kotlin / Java)"
  if [ "$MODE" = "format" ]; then
    run "$REPO_ROOT/gradlew" -p "$REPO_ROOT" --no-daemon spotlessApply detekt apiDump
  else
    run "$REPO_ROOT/gradlew" -p "$REPO_ROOT" --no-daemon spotlessCheck detekt apiCheck
  fi
}

# ── Rust ──────────────────────────────────────────────────────────────

lint_rust() {
  section "Rust"
  if [ "$MODE" = "format" ]; then
    (cd "$CLI_DIR" && run cargo fmt)
  else
    (cd "$CLI_DIR" && run cargo fmt -- --check)
  fi
  (cd "$CLI_DIR" && SKIP_SPA_BUILD=1 run cargo clippy -- -D warnings)
}

# ── SPA (TypeScript / React) ─────────────────────────────────────────

lint_spa() {
  section "SPA (TypeScript / React)"
  if [ ! -d "$SPA_DIR/node_modules" ]; then
    (cd "$SPA_DIR" && run npm install)
  fi
  if [ "$MODE" = "format" ]; then
    (cd "$SPA_DIR" && run npx biome check --write src)
  else
    (cd "$SPA_DIR" && run npx tsc -b)
    (cd "$SPA_DIR" && run npx biome check src)
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
        if [ -n "$(gofmt -l "$dir")" ]; then
          echo "gofmt: files need formatting in $dir:"
          gofmt -l "$dir"
          EXIT_CODE=1
        fi
      fi
      (cd "$dir" && run go vet ./...)
    fi
  done
}

# ── Recipes (Kotlin / Java / Scala) ──────────────────────────────────

lint_recipes() {
  section "Recipes (Kotlin / Java / Scala)"
  if [ "$MODE" = "format" ]; then
    run "$REPO_ROOT/gradlew" -p "$RECIPES_DIR" --no-daemon spotlessApply
  else
    run "$REPO_ROOT/gradlew" -p "$RECIPES_DIR" --no-daemon spotlessCheck
  fi
}

# ── Run selected projects concurrently ────────────────────────────────

echo "Mode: $MODE"

PIDS=""
for proj in $PROJECTS; do
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
