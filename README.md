# osate-headless

Run OSATE's AADL compiler and EMV2 safety and security analyses without the IDE: one request file in, one JSON result out. Built for CI pipelines and for tools that call OSATE as a service.

> **Status: internal, not published.** This repository is the public-candidate half of a two-part design. It contains no project-specific models or code, and is licensed EPL-2.0 to match OSATE so it *can* be published. Publication needs an explicit go-ahead first (see "Publishing").

## Why

OSATE's EMV2 analyses (propagation graph, fault impact, FHA, fault trees, minimal cut sets) are IDE menu commands. There is no supported headless entry point for them, and the question comes up on the OSATE list. Building one exposed a second problem: OSATE 2.20 produces **plausible-looking wrong results** in several situations, with no error or warning. This tool runs the analyses and **guards against those cases**, so a CI result of "0 unhandled faults" means something.

## Quick start

```sh
export OSATE_HOME=/Applications/osate2.app      # or the unpacked install on Linux
sh build.sh                                      # compiles the plugin into dist/
bin/osate-headless check path/to/models Pkg::System.impl out/
cat out/result.json
```

`check` compiles, instantiates and runs the EMV2 analyses. For fault trees, requirement tracing or a hazard format other than MIL-STD-882, write a request (see `schema/request.example.json`) and run:

```sh
bin/osate-headless analyze request.json out/
```

Exit status: 0 = a result was produced (any status except `failed`), 1 = `failed`, 2 = usage or tooling error.

The OSATE install is never modified. The plugin is loaded from `dist/` through a private Eclipse configuration area. The bundled JRE is called directly with the Equinox launcher jar, because the native launcher (`osate2.app/Contents/MacOS/osate`, `osate.exe`) shows a modal "JVM terminated. Exit code=1" dialog on any non-zero exit, which blocks unattended runs.

## Result

`out/result.json` (schema `osate_headless.result.v1`) holds:

| Key | Contents |
|---|---|
| `status` | `verified`, `unverified`, `undeclared` or `failed` (below) |
| `notes` | Why the status is what it is, and any guard that changed a result |
| `lints` | L1, L2 findings from the pre-compile text lints |
| `compile`, `instance_markers` | Error and warning counts, each marker with file, line and message |
| `instance` | Component and connection counts, end-to-end flows, system operation modes |
| `emv2.census` | EMV2 subclauses declared and parsed |
| `emv2.graph` | Propagation paths, each marked handled or unhandled |
| `emv2.sources` | Every error source, and whether a propagation path carries it |
| `emv2.fta_targets` | Valid fault-tree start strings for each component |
| `emv2.hazards` | Rows of OSATE's FHA report |
| `emv2.fault_trees` | For each requested start: OSATE's tree, the minimal cut sets after the guards, `min_order`, and notes |
| `requirements` | With `requirement_property`: which model elements carry each ID, plus untraced and undefined IDs |

OSATE's own CSV reports are copied to `out/reports/`.

### Status

| Status | Meaning |
|---|---|
| `verified` | Compiled with no errors, instantiated, and every requested analysis produced non-degenerate output |
| `unverified` | The analyses ran, but a guard says part of the output cannot be trusted. See `notes` |
| `undeclared` | Nothing to analyse, for example no EMV2 annex on any component |
| `failed` | Compile errors, no instance, or a crash |

Treat anything but `verified` as **no evidence**, never as "no findings".

## Lints and guards

| Code | Situation | Effect |
|---|---|---|
| L1 | An AADL reserved word used as an identifier (`sink`, `reference`). OSATE reports a parse error and then unrelated unresolved references | Lint names the real cause |
| L2 | A package-qualified classifier on a bus access feature. OSATE 2.20 does not resolve it (upstream-issues/01) | Lint suggests `renames bus Pkg::Name;` |
| G1 | EMV2 declared on connected components, but an empty propagation graph (typically containment-only models) | `unverified` |
| G2 | A state and an event whose names differ only in case. Fault-tree nodes are lowercased and merge, so cut sets lose events (upstream-issues/02) | `unverified` |
| G3 | A connection error source, or a bus `bindings` source, that no propagation path carries (upstream-issues/03) | `unverified` |
| G4 | The tree for an outgoing propagation omits cut sets from unconditional state propagations, depending on state names (upstream-issues/04) | **Corrected:** the per-state trees are unioned in and a note is added |
| G5 | Undeveloped events in a cut set: an incoming propagation with no modelled source | `unverified` |

## Fixtures

`fixtures/` holds one minimal model per lint and guard, plus control models that behave correctly and a positive control (sensor → filter → actuator). `python3 -I test/run_fixtures.py` runs them all. Fixtures that reproduce an OSATE defect assert OSATE's *current* wrong answer, so a fix upstream shows up as a failing fixture, which is the signal to retire a guard.

## Upstream

`upstream-issues/` has drafts of four reports for osate/osate2, each tied to fixtures. They are not filed yet; see the checklist there.

## Contributing

Issues and pull requests are welcome at <https://github.com/BeyondOrdinary/osate-headless>. A new lint or guard needs a fixture that reproduces the problem and a control model that behaves correctly, and `test/run_fixtures.py` must pass. Keep models minimal and generic: fixtures are public, so they must not come from, or resemble, any private project.

## License

EPL-2.0 (`LICENSE`), matching OSATE. OSATE is not redistributed here.
