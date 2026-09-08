#!/bin/sh
# Maven Wrapper bootstrap script. Requires a POSIX shell on Unix-like systems.
set -e
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS")
MAVEN_HOME="$BASE_DIR/.mvn/wrapper/apache-maven"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$BASE_DIR/.mvn/wrapper"
  ARCHIVE="$BASE_DIR/.mvn/wrapper/maven.zip"
  curl -fsSL "$DIST_URL" -o "$ARCHIVE"
  unzip -q "$ARCHIVE" -d "$BASE_DIR/.mvn/wrapper"
  mv "$BASE_DIR/.mvn/wrapper"/apache-maven-* "$MAVEN_HOME"
  rm -f "$ARCHIVE"
fi
exec "$MAVEN_HOME/bin/mvn" "$@"

