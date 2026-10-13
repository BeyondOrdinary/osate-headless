#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0
"""Run every fixture under fixtures/ through bin/osate-headless and check expect.json.

    OSATE_HOME=/path/to/osate python3 -I test/run_fixtures.py [NAME ...]

A fixture is a directory with .aadl files and an expect.json:
  root, analyses?, fta_starts?, requirement_property?, requirement_ids?  -> the request
  expect: status, lint_codes, marker_contains, note_contains, note_absent,
          compile_errors, graph_paths_min, unhandled, hazards_min,
          requirements_untraced, sources_reached {name: bool},
          cut_sets {"component|start": [[event ids]]}        (result, after guards)
          osate_tree_leaves {"component|start": [event ids]} (OSATE's own tree)
Fixtures that reproduce OSATE defects assert OSATE's current wrong answer, so
a fixed OSATE shows up as a failing fixture: the signal to retire a guard.
"""
import json
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
CLI = REPO / "bin" / "osate-headless"


def leaves(e):
    kids = e.get("children") or []
    return [e["id"]] if not kids else [x for k in kids for x in leaves(k)]


def check(name, exp, res):
    errs = []
    e = exp["expect"]
    if "status" in e and res.get("status") != e["status"]:
        errs.append(f"status {res.get('status')} != {e['status']} (notes: {res.get('notes')})")
    codes = {l["code"] for l in res.get("lints", [])}
    for c in e.get("lint_codes", []):
        if c not in codes:
            errs.append(f"lint {c} missing (got {sorted(codes)})")
    if "marker_contains" in e:
        msgs = [m["message"] for m in res.get("compile", {}).get("markers", [])]
        if not any(e["marker_contains"] in m for m in msgs):
            errs.append(f"no marker contains {e['marker_contains']!r}: {msgs}")
    notes = " | ".join(res.get("notes", []))
    if "note_contains" in e and e["note_contains"] not in notes:
        errs.append(f"notes lack {e['note_contains']!r}: {notes}")
    if "note_absent" in e and e["note_absent"] in notes:
        errs.append(f"notes unexpectedly contain {e['note_absent']!r}")
    if "compile_errors" in e and res.get("compile", {}).get("errors") != e["compile_errors"]:
        errs.append(f"compile errors {res.get('compile', {}).get('errors')} != {e['compile_errors']}")
    emv2 = res.get("emv2", {})
    graph = emv2.get("graph", {})
    if "graph_paths_min" in e and graph.get("paths", 0) < e["graph_paths_min"]:
        errs.append(f"graph paths {graph.get('paths')} < {e['graph_paths_min']}")
    if "unhandled" in e and graph.get("unhandled") != e["unhandled"]:
        errs.append(f"unhandled {graph.get('unhandled')} != {e['unhandled']}")
    if "hazards_min" in e and len(emv2.get("hazards", [])) < e["hazards_min"]:
        errs.append(f"hazards {len(emv2.get('hazards', []))} < {e['hazards_min']}")
    if "requirements_untraced" in e:
        got = res.get("requirements", {}).get("untraced")
        if got != e["requirements_untraced"]:
            errs.append(f"untraced {got} != {e['requirements_untraced']}")
    for src, want in e.get("sources_reached", {}).items():
        hit = [s for s in emv2.get("sources", []) if s.get("connection_source") == src or s.get("name") == src]
        if not hit or hit[0].get("reached") != want:
            errs.append(f"source {src} reached={hit[0].get('reached') if hit else None}, want {want}")
    trees = {f"{t['component']}|{t['start']}": t for t in emv2.get("fault_trees", [])}
    for key, want in e.get("cut_sets", {}).items():
        t = trees.get(key)
        got = sorted(sorted(ev["id"] for ev in c["events"]) for c in t["cut_sets"]) if t else None
        if got != sorted(sorted(s) for s in want):
            errs.append(f"cut sets {key}: {got} != {want}")
    for key, want in e.get("osate_tree_leaves", {}).items():
        t = trees.get(key)
        got = sorted(set(leaves(t["tree"]))) if t and "tree" in t else None
        if got != sorted(want):
            errs.append(f"OSATE tree leaves {key}: {got} != {want}")
    return errs


def main(names):
    fixtures = sorted(p.parent for p in (REPO / "fixtures").glob("*/expect.json"))
    if names:
        fixtures = [f for f in fixtures if f.name in names]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for f in fixtures:
            exp = json.loads((f / "expect.json").read_text())
            req = {"schema": "osate_headless.request.v1", "model_dir": str(f), "root": exp["root"]}
            for k in ("analyses", "fta_starts", "requirement_property", "requirement_ids", "hazard_format"):
                if k in exp:
                    req[k] = exp[k]
            out = pathlib.Path(tmp, f.name)
            out.mkdir()
            (out / "request.json").write_text(json.dumps(req))
            subprocess.run([str(CLI), "analyze", str(out / "request.json"), str(out)], capture_output=True, text=True)
            rp = out / "result.json"
            errs = ["no result.json: " + (out / "osate.log").read_text()[-800:]] if not rp.exists() else check(f.name, exp, json.loads(rp.read_text()))
            print(f"{'ok  ' if not errs else 'FAIL'} {f.name}")
            for x in errs:
                print("       " + x)
            failed += bool(errs)
    print(f"\n{len(fixtures) - failed}/{len(fixtures)} fixtures pass")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
