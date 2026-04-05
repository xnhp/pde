#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$REPO_ROOT/build"
PLUGIN_BUILD_DIR="$BUILD_DIR/plugin"
PLUGIN_DIR="$PLUGIN_BUILD_DIR/plugins"
LAUNCHER_BUILD_DIR="$BUILD_DIR/launcher"
RUNTIME_DIR="$BUILD_DIR/runtime"
REPO_DIR="$BUILD_DIR/p2repo"
DIST_DIR="$REPO_ROOT/dist"
DEPS_DIR="$BUILD_DIR/deps"
LIB_DIR="$BUILD_DIR/lib"
BOOTSTRAP_RUNTIME_DIR="$BUILD_DIR/bootstrap-runtime"

ECLIPSE_SDK="${ECLIPSE_SDK:-}"
P2_REPOSITORIES="${P2_REPOSITORIES:-}"
RUNTIME_ZIP="${RUNTIME_ZIP:-}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

TINYLOG_VERSION="2.7.0"

if [[ -z "$RUNTIME_ZIP" && -z "$ECLIPSE_SDK" ]]; then
  echo "Either RUNTIME_ZIP (prebuilt runtime archive) or ECLIPSE_SDK must be set" >&2
  exit 1
fi

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$PLUGIN_DIR" "$LAUNCHER_BUILD_DIR" "$RUNTIME_DIR" "$REPO_DIR" "$DIST_DIR" "$DEPS_DIR" "$LIB_DIR"

ECLIPSE_PLUGINS_DIR="${ECLIPSE_SDK:+$ECLIPSE_SDK/plugins}"
if [[ -z "$ECLIPSE_PLUGINS_DIR" ]]; then
  unzip -q "$RUNTIME_ZIP" -d "$BOOTSTRAP_RUNTIME_DIR"
  ECLIPSE_PLUGINS_DIR="$BOOTSTRAP_RUNTIME_DIR/plugins"
fi

function download_dep() {
  local url="$1"
  local out="$2"
  if [[ ! -f "$out" ]]; then
    curl -fL "$url" -o "$out"
  fi
}

echo "Downloading tinylog dependencies"
download_dep "https://repo1.maven.org/maven2/org/tinylog/tinylog-api/${TINYLOG_VERSION}/tinylog-api-${TINYLOG_VERSION}.jar" \
  "$LIB_DIR/tinylog-api-${TINYLOG_VERSION}.jar"
download_dep "https://repo1.maven.org/maven2/org/tinylog/tinylog-impl/${TINYLOG_VERSION}/tinylog-impl-${TINYLOG_VERSION}.jar" \
  "$LIB_DIR/tinylog-impl-${TINYLOG_VERSION}.jar"

CLASSPATH=$(printf "%s:" "$ECLIPSE_PLUGINS_DIR"/*.jar)
CLASSPATH+="$LIB_DIR/tinylog-api-${TINYLOG_VERSION}.jar:$LIB_DIR/tinylog-impl-${TINYLOG_VERSION}.jar"

echo "Compiling bundle"
"$JAVAC_BIN" --release 17 -cp "$CLASSPATH" \
  -d "$PLUGIN_BUILD_DIR/classes" \
  $(find "$REPO_ROOT/src" -name '*.java')

echo "Packaging bundle"
jar cfm "$PLUGIN_DIR/org.knime.targetinstaller_1.0.0.jar" \
  "$REPO_ROOT/META-INF/MANIFEST.MF" \
  -C "$PLUGIN_BUILD_DIR/classes" . \
  -C "$REPO_ROOT" plugin.xml \
  -C "$BUILD_DIR" lib

if [[ -n "$RUNTIME_ZIP" ]]; then
  echo "Using prebuilt runtime archive: $RUNTIME_ZIP"
  cp -R "$BOOTSTRAP_RUNTIME_DIR"/. "$RUNTIME_DIR"/
  LAUNCHER_JAR=$(ls "$RUNTIME_DIR"/plugins/org.eclipse.equinox.launcher_*.jar | head -n 1)
  if [[ -z "$LAUNCHER_JAR" ]]; then
    echo "Unable to locate org.eclipse.equinox.launcher in $RUNTIME_ZIP" >&2
    exit 1
  fi
else
  LAUNCHER_JAR=$(ls "$ECLIPSE_SDK"/plugins/org.eclipse.equinox.launcher_*.jar | head -n 1)
  if [[ -z "$LAUNCHER_JAR" ]]; then
    echo "Unable to locate org.eclipse.equinox.launcher in $ECLIPSE_SDK" >&2
    exit 1
  fi

  echo "Publishing p2 repository"
  "$JAVA_BIN" -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "file:$REPO_DIR" \
    -artifactRepository "file:$REPO_DIR" \
    -source "$PLUGIN_BUILD_DIR" \
    -compress -publishArtifacts

  REPOS="file:$REPO_DIR"
  if [[ -n "$P2_REPOSITORIES" ]]; then
    REPOS+="${REPOS:+,}$P2_REPOSITORIES"
  fi

  echo "Materializing runtime"
  "$JAVA_BIN" -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.director \
    -repository "$REPOS" \
    -installIU org.knime.targetinstaller,org.apache.felix.scr,org.eclipse.equinox.p2.transport.ecf,org.eclipse.equinox.p2.touchpoint.natives,org.eclipse.equinox.p2.touchpoint.eclipse,org.eclipse.equinox.frameworkadmin,org.eclipse.equinox.frameworkadmin.equinox,org.eclipse.equinox.simpleconfigurator.manipulator,org.eclipse.osgi.compatibility.state,org.eclipse.osgi.services \
    -destination "$RUNTIME_DIR" \
    -profile DefaultProfile \
    -bundlepool "$RUNTIME_DIR"

  echo "Adding Equinox launcher"
  mkdir -p "$RUNTIME_DIR/plugins"
  cp "$LAUNCHER_JAR" "$RUNTIME_DIR/plugins/"
fi

if [[ -d "$REPO_ROOT/out/partial-runtime/config" ]]; then
  echo "Seeding runtime configuration"
  mkdir -p "$RUNTIME_DIR/config"
  cp -R "$REPO_ROOT/out/partial-runtime/config"/* "$RUNTIME_DIR/config/"
fi

echo "Creating runtime archive"
jar cf "$LAUNCHER_BUILD_DIR/runtime.zip" -C "$RUNTIME_DIR" .

echo "Building launcher"
"$JAVAC_BIN" --release 17 \
  -d "$LAUNCHER_BUILD_DIR/classes" \
  "$REPO_ROOT/launcher/src/org/knime/targetinstaller/launcher/Bootstrap.java"

jar cfm "$DIST_DIR/target-installer-launcher.jar" \
  "$REPO_ROOT/launcher/manifest.mf" \
  -C "$LAUNCHER_BUILD_DIR/classes" . \
  -C "$LAUNCHER_BUILD_DIR" runtime.zip

echo "Launcher created: $DIST_DIR/target-installer-launcher.jar"
