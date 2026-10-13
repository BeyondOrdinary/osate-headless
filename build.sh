#!/bin/sh
# SPDX-License-Identifier: EPL-2.0
# Compile the headless analysis plugin against an OSATE install and package it
# as a dropin jar in dist/.
#   OSATE_HOME=/path/to/osate sh build.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/bin/osate-env.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp=""
for j in "$OSATE_ECLIPSE"/plugins/*.jar; do cp="$cp$j:"; done
for d in "$OSATE_ECLIPSE"/plugins/*/; do
  for j in $(find "$d" -name '*.jar' 2>/dev/null); do cp="$cp$j:"; done
done
"$OSATE_JAVA_HOME/bin/javac" --release 21 -nowarn -d "$work/classes" -cp "$cp" \
  $(find "$here/app/src" -name '*.java')
version=$(sed -n 's/^Bundle-Version: //p' "$here/app/META-INF/MANIFEST.MF" | tr -d '\r')
# Callers key result caches on this version: the manifest and APP_VERSION must agree.
app_version=$(sed -n 's/.*APP_VERSION = "\(.*\)";.*/\1/p' "$here"/app/src/com/beyondordinary/osate/headless/Analyze.java)
[ "$app_version" = "$version" ] || { echo "Bundle-Version $version != APP_VERSION $app_version: bump both" >&2; exit 2; }
mkdir -p "$here/dist/plugins"
rm -f "$here"/dist/plugins/com.beyondordinary.osate.headless_*.jar
cp "$here/app/plugin.xml" "$work/classes/"
cp "$here/LICENSE" "$work/classes/" 2>/dev/null || true
(cd "$work/classes" && "$OSATE_JAVA_HOME/bin/jar" cfm \
  "$here/dist/plugins/com.beyondordinary.osate.headless_$version.jar" "$here/app/META-INF/MANIFEST.MF" .)
echo "built dist/plugins/com.beyondordinary.osate.headless_$version.jar"
