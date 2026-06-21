#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Windows/Git-Bash: convert the MSYS path (/c/...) to mixed form (C:/...) so the
# Windows JDK tools (javac/java/jar) and Equinox understand the derived paths.
if command -v cygpath >/dev/null 2>&1; then
  REPO_ROOT="$(cygpath -m "$REPO_ROOT")"
fi
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

# Platform portability: classpath separator and file-URL form differ on Windows.
CPSEP=":"
FILEURL="file:"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    CPSEP=";"            # ':' collides with C: drive letters on Windows
    FILEURL="file:/"     # Equinox needs file:/C:/... (with leading slash)
    ;;
esac
if [[ -n "${ECLIPSE_SDK:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  ECLIPSE_SDK="$(cygpath -m "$ECLIPSE_SDK")"
fi

TINYLOG_VERSION="2.7.0"
PLUGIN_VERSION=$(grep "^Bundle-Version:" "$REPO_ROOT/META-INF/MANIFEST.MF" | awk '{print $2}' | tr -d '\r' | sed 's/\.qualifier$//')
PLUGIN_JAR="org.knime.targetinstaller_${PLUGIN_VERSION}.jar"

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

CLASSPATH=$(printf "%s${CPSEP}" "$ECLIPSE_PLUGINS_DIR"/*.jar)
CLASSPATH+="$LIB_DIR/tinylog-api-${TINYLOG_VERSION}.jar${CPSEP}$LIB_DIR/tinylog-impl-${TINYLOG_VERSION}.jar"

echo "Compiling bundle"
# Pass options/sources via an @argfile: the full classpath (~700 SDK jars) plus the
# source list exceeds the OS command-line length limit ("Argument list too long").
JAVAC_ARGS="$BUILD_DIR/javac-bundle-args.txt"
{
  echo "--release"
  echo "21"
  echo "-cp"
  printf '"%s"\n' "$CLASSPATH"
  echo "-d"
  printf '"%s"\n' "$PLUGIN_BUILD_DIR/classes"
  find "$REPO_ROOT/src" -name '*.java' | while IFS= read -r f; do printf '"%s"\n' "$f"; done
} > "$JAVAC_ARGS"
"$JAVAC_BIN" "@$JAVAC_ARGS"

echo "Packaging bundle"
jar cfm "$PLUGIN_DIR/$PLUGIN_JAR" \
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
  # Install the target installer plugin into the runtime
  cp "$PLUGIN_DIR/$PLUGIN_JAR" "$RUNTIME_DIR/plugins/"
  echo "org.knime.targetinstaller,$PLUGIN_VERSION,plugins/$PLUGIN_JAR,4,true" \
    >> "$RUNTIME_DIR/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
else
  LAUNCHER_JAR=$(ls "$ECLIPSE_SDK"/plugins/org.eclipse.equinox.launcher_*.jar | head -n 1)
  if [[ -z "$LAUNCHER_JAR" ]]; then
    echo "Unable to locate org.eclipse.equinox.launcher in $ECLIPSE_SDK" >&2
    exit 1
  fi

  echo "Publishing p2 repository (target-installer plugin)"
  "$JAVA_BIN" -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "${FILEURL}$REPO_DIR" \
    -artifactRepository "${FILEURL}$REPO_DIR" \
    -source "$PLUGIN_BUILD_DIR" \
    -compress -publishArtifacts

  # Publish the Eclipse SDK into a local p2 repo so the runtime IUs (felix.scr,
  # equinox p2 touchpoints, ...) resolve deterministically offline, independent of
  # remote release-repo availability / p2 transport-proxy quirks.
  SDK_REPO_DIR="$BUILD_DIR/sdk-p2repo"
  echo "Publishing p2 repository (Eclipse SDK)"
  "$JAVA_BIN" -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "${FILEURL}$SDK_REPO_DIR" \
    -artifactRepository "${FILEURL}$SDK_REPO_DIR" \
    -source "$ECLIPSE_SDK" \
    -compress -publishArtifacts

  REPOS="${FILEURL}$REPO_DIR,${FILEURL}$SDK_REPO_DIR"
  if [[ -n "$P2_REPOSITORIES" ]]; then
    REPOS+="${REPOS:+,}$P2_REPOSITORIES"
  fi

  # org.eclipse.osgi.services was removed in newer Eclipse (its packages are folded
  # into org.eclipse.osgi); include the IU only when the SDK still ships it.
  INSTALL_IUS="org.knime.targetinstaller,org.apache.felix.scr,org.eclipse.equinox.p2.transport.ecf,org.eclipse.equinox.p2.touchpoint.natives,org.eclipse.equinox.p2.touchpoint.eclipse,org.eclipse.equinox.frameworkadmin,org.eclipse.equinox.frameworkadmin.equinox,org.eclipse.equinox.simpleconfigurator.manipulator,org.eclipse.osgi.compatibility.state"
  if ls "$ECLIPSE_PLUGINS_DIR"/org.eclipse.osgi.services_*.jar >/dev/null 2>&1; then
    INSTALL_IUS+=",org.eclipse.osgi.services"
  fi

  echo "Materializing runtime"
  "$JAVA_BIN" -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.director \
    -repository "$REPOS" \
    -installIU "$INSTALL_IUS" \
    -destination "$RUNTIME_DIR" \
    -profile DefaultProfile \
    -bundlepool "$RUNTIME_DIR"

  # The Equinox launcher exits 0 even when the director reports "Installation failed",
  # so verify the target-installer plugin actually landed in the runtime.
  if ! ls "$RUNTIME_DIR"/plugins/org.knime.targetinstaller_*.jar >/dev/null 2>&1; then
    echo "Runtime materialization failed: org.knime.targetinstaller missing from $RUNTIME_DIR/plugins" >&2
    exit 1
  fi

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
"$JAVAC_BIN" --release 21 \
  -d "$LAUNCHER_BUILD_DIR/classes" \
  "$REPO_ROOT/launcher/src/org/knime/targetinstaller/launcher/Bootstrap.java"

jar cfm "$DIST_DIR/target-installer-launcher.jar" \
  "$REPO_ROOT/launcher/manifest.mf" \
  -C "$LAUNCHER_BUILD_DIR/classes" . \
  -C "$LAUNCHER_BUILD_DIR" runtime.zip

echo "Launcher created: $DIST_DIR/target-installer-launcher.jar"
