# SPDX-License-Identifier: EPL-2.0
# Sourced by build.sh and osate-headless: locate the OSATE install, its Eclipse
# directory, the bundled JRE and the Equinox launcher jar.
# OSATE_HOME may point at osate2.app (macOS) or the unpacked install (Linux, Windows).
if [ -z "${OSATE_HOME:-}" ]; then
  for c in /Applications/osate2.app "$HOME"/tools/osate-*/osate2.app /opt/osate /opt/osate2; do
    if [ -d "$c" ]; then OSATE_HOME=$c; fi
  done
fi
[ -n "${OSATE_HOME:-}" ] && [ -d "$OSATE_HOME" ] || { echo "OSATE not found: set OSATE_HOME" >&2; exit 2; }
if [ -d "$OSATE_HOME/Contents/Eclipse" ]; then OSATE_ECLIPSE="$OSATE_HOME/Contents/Eclipse"; else OSATE_ECLIPSE="$OSATE_HOME"; fi
OSATE_JAVA_HOME=$(ls -d "$OSATE_ECLIPSE"/plugins/org.eclipse.justj.openjdk.hotspot.jre.*/jre 2>/dev/null | head -1)
[ -n "$OSATE_JAVA_HOME" ] || { echo "bundled JRE not found under $OSATE_ECLIPSE/plugins" >&2; exit 2; }
OSATE_LAUNCHER=$(ls "$OSATE_ECLIPSE"/plugins/org.eclipse.equinox.launcher_*.jar 2>/dev/null | head -1)
[ -n "$OSATE_LAUNCHER" ] || { echo "Equinox launcher not found under $OSATE_ECLIPSE/plugins" >&2; exit 2; }
export OSATE_HOME OSATE_ECLIPSE OSATE_JAVA_HOME OSATE_LAUNCHER
