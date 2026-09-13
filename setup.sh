#!/usr/bin/env sh
set -e
command -v java >/dev/null 2>&1 || { echo "Java is required (>=25)."; exit 1; }
if command -v gradle >/dev/null 2>&1; then
  echo "Using existing Gradle on PATH."
else
  GRADLE_DIR=".gradle-dist"
  GRADLE_VERSION=9.4.0
  ZIP="$GRADLE_DIR/gradle-$GRADLE_VERSION-bin.zip"
  mkdir -p "$GRADLE_DIR"
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -fSL -o "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  fi
  echo "Extracting..."
  unzip -qo "$ZIP" -d "$GRADLE_DIR"
  GRADLE_BIN="$GRADLE_DIR/gradle-$GRADLE_VERSION/bin/gradle"
  chmod +x "$GRADLE_BIN" || true
  export PATH="$(cd "$GRADLE_DIR/gradle-$GRADLE_VERSION/bin" && pwd):$PATH"
fi
gradle wrapper --gradle-version 9.4.0
echo "Wrapper created. You can now use ./gradlew"
