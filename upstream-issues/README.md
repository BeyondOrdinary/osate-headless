# Upstream issue drafts (osate/osate2)

**Status: drafts, not filed.** They are written to be filed on <https://github.com/osate/osate2/issues> by a person, after the checklist below. Each one has a minimal model under `fixtures/` and a control model that behaves correctly. `test/run_fixtures.py` asserts OSATE's current behaviour, so when a fix lands the matching fixture fails and the guard in `osate-headless` can be retired.

| # | Draft | Kind | Fixtures |
|---|---|---|---|
| 01 | [Package-qualified classifier on a bus access feature does not resolve](01-bus-access-qualified-classifier.md) | Bug | `l2-bus-access-qualified`, `l2-bus-access-renames` |
| 02 | [Fault-tree node names are lowercased, so a state and an event that differ only in case are merged](02-fta-case-insensitive-node-merge.md) | Bug | `g2-name-case-collision`, `g2-name-case-control` |
| 03 | [Connection and binding error sources are silently dropped when nothing carries them to a receiver](03-unreached-error-sources-silent.md) | Bug or enhancement (asks which) | `g3-connection-source-unreached`, `g3-connection-source-control`, `g3-binding-source-unreached` |
| 04 | [Fault tree for an outgoing propagation drops a cut set depending on state names](04-fta-drops-branch-name-order.md) | Bug | `g4-shared-state-drops-branch`, `g4-control-names`, `g4-control-no-transition` |

## Before filing

1. **Reproduce in the OSATE IDE**, not only headless. The headless application uses the same Xtext builder, instantiation and EMV2 analysis entry points as the IDE menu commands, but maintainers will reproduce through the IDE. Record the menu path used (Analyses → Error Model → Fault Tree Analysis, and so on).
2. **Search open and closed issues** on osate/osate2 for each title and its key error message. A web search on 12 October 2026 found no match; search the tracker itself.
3. **Check the current release.** These were found on OSATE 2.20.0. Retest on the latest release or nightly before filing.
4. **Attach only the fixture model.** Nothing else from any project leaves this repository.
5. **Post from the account that will maintain the conversation.**

## Environment (all four)

- OSATE 2.20.0: `org.osate.core` 5.1.1.v20260609-1339, `org.osate.aadl2` 7.0.0.v20260830-1344, `org.osate.xtext.aadl2.errormodel` 8.0.0.v20260923-2033, `org.osate.aadl2.errormodel.faulttree.generation` 1.3.0.v20260918-1739
- Bundled JustJ OpenJDK 21.0.12, macOS 27.0.1 on Apple silicon (aarch64)
