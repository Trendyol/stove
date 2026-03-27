#!/bin/sh
#
# Git pre-commit hook — runs lint checks on changed projects.
# Delegates to lint.sh which auto-detects changed files.

REPO_ROOT="$(git rev-parse --show-toplevel)"
exec "$REPO_ROOT/lint.sh" --check
