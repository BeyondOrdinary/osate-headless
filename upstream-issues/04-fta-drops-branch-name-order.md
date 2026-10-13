# Fault tree for an outgoing propagation drops a cut set depending on state names

**Labels (suggested):** bug, error model / fault tree analysis

## Summary

Two error behavior states, `Wait` and `Dead`, both propagate `Omission` on port `o` unconditionally. `Wait` also has a transition to `Dead`. The fault tree for `outgoing propagation on o{Omission}` omits the events that lead to `Wait`. **Renaming the states so that the source of the transition sorts alphabetically before its target gives the correct tree.** The per-state trees (`state Wait`, `state Dead`) are always correct.

We found this in a device lifecycle model with one nominal and three failure states, every failure state propagating "no service", and transitions between the failure states. Three of the six causes were missing from the tree.

## Steps to reproduce

Model: `fixtures/g4-shared-state-drops-branch/Repro.aadl`:

```aadl
error behavior Life
  use types Repro;
  events
    ea: error event {Fault_A};
    ef: error event {Fault_F};
  states
    Op: initial state;
    Wait: state;
    Dead: state;
  transitions
    t_a: Op -[ ea ]-> Wait;
    t_f: Op -[ ef ]-> Dead;
    t_wd: Wait -[ ef ]-> Dead;
end behavior;

device Unit
  features
    o: out data port;
  annex EMV2 {**
    use types Repro;
    use behavior Repro::Life;
    error propagations
      o: out propagation {Omission};
    end propagations;
    component error behavior
      propagations
        p_wait: Wait -[ ]-> o{Omission};
        p_dead: Dead -[ ]-> o{Omission};
    end component;
  **};
end Unit;
```

Instantiate `Repro::Top.i` and run Fault Tree Analysis on `u`, start `outgoing propagation on o{Omission}`.

## Actual

```
OR u-out-o-omission
  Basic u-ef-fault_f
```

## Expected

```
OR u-out-o-omission
  Basic u-ea-fault_a
  Basic u-ef-fault_f
```

## Controls (each gives the expected tree)

| Fixture | Change |
|---|---|
| `g4-control-names` | States renamed `A` (was `Wait`) and `F` (was `Dead`). Nothing else changes |
| `g4-control-no-transition` | Transition `t_wd` removed |

Event typing (distinct or shared error types), recovery events and extra states made no difference while we bisected. The name order and the transition between the two propagating states are both necessary.

## Impact

The tree silently under-reports causes. In our model it listed three of six causes of "no service"; in a reduced version of that model, one of five. A guard that unions the per-state trees of every unconditionally propagating state recovers the missing cut sets.
