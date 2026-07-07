#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$REPO_ROOT/build"
PLUGIN_BUILD_DIR="$BUILD_DIR/plugin"
PLUGIN_DIR="$PLUGIN_BUILD_DIR/plugins"
RUNTIME_DIR="$BUILD_DIR/runtime"
REPO_DIR="$BUILD_DIR/p2repo"
DIST_DIR="$REPO_ROOT/dist"
LIB_DIR="$PLUGIN_BUILD_DIR/lib"

ECLIPSE_SDK="${ECLIPSE_SDK:-}"
P2_REPOSITORIES="${P2_REPOSITORIES:-}"
APP_LIBS="${APP_LIBS:-}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

APP_BSN="cn.varsa.pde"
APP_VERSION="1.0.0"
APP_JAR="$APP_BSN-$APP_VERSION.jar"

if [[ -z "$APP_LIBS" ]]; then
  echo "APP_LIBS must contain the analyzer application libraries" >&2
  exit 1
fi

if [[ -z "$ECLIPSE_SDK" ]]; then
  echo "ECLIPSE_SDK must be set" >&2
  exit 1
fi

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$PLUGIN_DIR" "$RUNTIME_DIR" "$REPO_DIR" "$DIST_DIR" "$LIB_DIR"

ECLIPSE_PLUGINS_DIR="$ECLIPSE_SDK/plugins"

if [[ ! -d "$ECLIPSE_PLUGINS_DIR" ]] || ! compgen -G "$ECLIPSE_PLUGINS_DIR/*.jar" >/dev/null; then
  echo "ERROR: no Eclipse plugin jars found in '$ECLIPSE_PLUGINS_DIR'." >&2
  echo "       Point ECLIPSE_SDK at a real Eclipse SDK install." >&2
  exit 1
fi

echo "Packaging analyzer application bundle"
IFS=':' read -ra libs <<< "$APP_LIBS"
for lib in "${libs[@]}"; do
  [[ -n "$lib" ]] || continue
  cp "$lib" "$LIB_DIR/"
done

BUNDLE_CLASSPATH="."
while IFS= read -r lib; do
  BUNDLE_CLASSPATH+=",lib/$(basename "$lib")"
done < <(find "$LIB_DIR" -maxdepth 1 -name '*.jar' | sort)

MANIFEST="$BUILD_DIR/MANIFEST.MF"
cat > "$MANIFEST" <<EOF
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-SymbolicName: $APP_BSN;singleton:=true
Bundle-Version: $APP_VERSION
Bundle-Name: pde API Analyzer
Bundle-RequiredExecutionEnvironment: JavaSE-21
Bundle-ClassPath: $BUNDLE_CLASSPATH
Require-Bundle: org.eclipse.equinox.app,org.eclipse.core.runtime,org.eclipse.core.resources,org.eclipse.core.filesystem,org.eclipse.core.filebuffers,org.eclipse.core.variables,org.eclipse.text,org.eclipse.jdt.core,org.eclipse.jdt.launching,org.eclipse.pde.api.tools
EOF

jar cfm "$PLUGIN_DIR/$APP_JAR" \
  "$MANIFEST" \
  -C "$REPO_ROOT" plugin.xml \
  -C "$PLUGIN_BUILD_DIR" lib

LAUNCHER_JAR=$(ls "$ECLIPSE_SDK"/plugins/org.eclipse.equinox.launcher_*.jar | head -n 1)
if [[ -z "$LAUNCHER_JAR" ]]; then
  echo "Unable to locate org.eclipse.equinox.launcher in $ECLIPSE_SDK" >&2
  exit 1
fi

echo "Publishing analyzer p2 repository"
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

INSTALL_IUS="$APP_BSN,org.eclipse.equinox.launcher,org.eclipse.equinox.simpleconfigurator,org.eclipse.osgi.compatibility.state,org.apache.felix.scr,org.eclipse.pde.api.tools,org.eclipse.pde.core,org.eclipse.jdt.core,org.eclipse.jdt.launching"

echo "Materializing analyzer runtime"
"$JAVA_BIN" -jar "$LAUNCHER_JAR" \
  -application org.eclipse.equinox.p2.director \
  -repository "$REPOS" \
  -installIU "$INSTALL_IUS" \
  -destination "$RUNTIME_DIR" \
  -profile DefaultProfile \
  -bundlepool "$RUNTIME_DIR"

if ! ls "$RUNTIME_DIR"/plugins/${APP_BSN}_*.jar >/dev/null 2>&1; then
  echo "Runtime materialization failed: $APP_BSN missing from $RUNTIME_DIR/plugins" >&2
  exit 1
fi

mkdir -p "$RUNTIME_DIR/plugins"
cp "$LAUNCHER_JAR" "$RUNTIME_DIR/plugins/"

echo "Creating analyzer runtime archive"
jar cf "$DIST_DIR/api-analyzer-runtime.zip" -C "$RUNTIME_DIR" .

echo "Runtime created: $DIST_DIR/api-analyzer-runtime.zip"
