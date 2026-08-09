#!/usr/bin/env sh
# Lightweight project-local Maven launcher. It uses an installed Maven when
# available and otherwise downloads Maven into an ignored .mvn cache.
set -eu

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

MAVEN_VERSION="3.9.11"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_DIR="$SCRIPT_DIR/.mvn/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_HOME="$DIST_DIR/apache-maven-$MAVEN_VERSION"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$DIST_DIR"
  ARCHIVE="$DIST_DIR/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" -O "$ARCHIVE"
  else
    echo "Maven is not installed and neither curl nor wget is available." >&2
    exit 1
  fi
  tar -xzf "$ARCHIVE" -C "$DIST_DIR"
  rm -f "$ARCHIVE"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
