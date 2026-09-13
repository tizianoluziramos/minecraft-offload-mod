#!/usr/bin/env sh
set -e
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is not installed or not on PATH."
  echo "Install Gradle 9.4.0 or run ./setup.sh to bootstrap automatically."
  exit 1
fi
gradle -p "$(cd "$(dirname "$0")" && pwd)" "$@"
