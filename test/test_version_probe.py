#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0
"""Check that `osate-headless version` changes exactly when the analysis can change.

    python3 -I test/test_version_probe.py

Needs no OSATE: it builds a minimal fake install (plugins, JRE and launcher
placeholders) and a fake dropins directory, then compares probe lines.
Callers key result caches on the line without its OSATE_HOME part, so:
  - replacing an org.osate.* plugin, even under the same file name, must change it
  - editing a file inside a directory-form org.osate.* plugin must change it
  - rebuilding this tool's plugin jar must change it
  - changing a plugin outside org.osate.* must not
  - moving the same install to another path must not
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
CLI = REPO / "bin" / "osate-headless"


def make_install(root):
    plugins = root / "plugins"
    (plugins / "org.eclipse.justj.openjdk.hotspot.jre.full_21" / "jre" / "bin").mkdir(parents=True)
    (plugins / "org.eclipse.equinox.launcher_1.6.jar").write_bytes(b"launcher")
    (plugins / "org.osate.core_5.1.1.v1.jar").write_bytes(b"core")
    (plugins / "org.osate.xtext.aadl2.errormodel_8.0.0.v1.jar").write_bytes(b"emv2 original")
    (plugins / "org.osate.aadl2.errormodel.faulttree.generation_1.3.0.v1.jar").write_bytes(b"fta")
    (plugins / "org.osate.aadl2.errormodel.propagationgraph_1.0.0.v1" / "lib").mkdir(parents=True)
    (plugins / "org.osate.aadl2.errormodel.propagationgraph_1.0.0.v1" / "lib" / "pg.jar").write_bytes(b"pg")
    (plugins / "org.eclipse.xtext_2.30.jar").write_bytes(b"xtext")
    return root


def make_dropins(root, content=b"headless"):
    (root / "plugins").mkdir(parents=True, exist_ok=True)
    (root / "plugins" / "com.beyondordinary.osate.headless_0.2.1.jar").write_bytes(content)
    return root


def probe(home, dropins):
    env = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "OSATE_HOME": str(home), "OSATE_HEADLESS_DROPINS": str(dropins)}
    run = subprocess.run(["sh", str(CLI), "version"], capture_output=True, text=True, env=env)
    if run.returncode != 0:
        raise SystemExit(f"probe failed ({run.returncode}): {run.stderr.strip()}")
    return re.sub(r";?\s*OSATE_HOME=[^)]*", "", run.stdout.strip())


def main():
    failed = 0

    def expect(name, changed, before, after):
        nonlocal failed
        ok = (before != after) == changed
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
        if not ok:
            print(f"     before: {before}\n     after:  {after}")
            failed += 1

    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t)
        home = make_install(t / "osate")
        dropins = make_dropins(t / "dropins")
        base = probe(home, dropins)
        if "org.osate plugins " not in base:
            print(f"FAIL probe line lacks the plugin fingerprint: {base}")
            return 1

        emv2 = home / "plugins" / "org.osate.xtext.aadl2.errormodel_8.0.0.v1.jar"
        emv2.write_bytes(b"emv2 patched, same file name")
        expect("EMV2 plugin replaced under the same name", True, base, probe(home, dropins))
        emv2.write_bytes(b"emv2 original")
        expect("restored EMV2 plugin gives the original line", False, base, probe(home, dropins))

        inner = home / "plugins" / "org.osate.aadl2.errormodel.propagationgraph_1.0.0.v1" / "lib" / "pg.jar"
        inner.write_bytes(b"pg patched")
        expect("file inside a directory-form org.osate plugin changed", True, base, probe(home, dropins))
        inner.write_bytes(b"pg")

        (home / "plugins" / "org.eclipse.xtext_2.30.jar").write_bytes(b"xtext patched")
        expect("plugin outside org.osate.* changed", False, base, probe(home, dropins))
        (home / "plugins" / "org.eclipse.xtext_2.30.jar").write_bytes(b"xtext")

        make_dropins(dropins, b"headless rebuilt")
        expect("this tool's plugin jar rebuilt", True, base, probe(home, dropins))
        make_dropins(dropins)

        moved = t / "elsewhere" / "osate"
        shutil.copytree(home, moved)
        expect("same install at another path", False, base, probe(moved, dropins))

    print("\nall probe checks pass" if not failed else f"\n{failed} probe check(s) failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
