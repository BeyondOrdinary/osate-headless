# Fault-tree node names are lowercased, so a state and an event that differ only in case are merged

**Labels (suggested):** bug, error model / fault tree analysis

## Summary

Fault-tree generation names events by lowercasing the component path and the EMV2 element name (for example `rx-key_exposed`). An error behavior state `Key_Exposed` and an error event `key_exposed` are distinct, legal EMV2 declarations, but they produce the same node name and are merged. The AND gate "key exposed AND forged input" collapses to "forged input" alone: the minimal cut set loses an event and its order drops from 2 to 1. There is no error or warning.

This matters because cut-set order is read as defence depth. The defect makes a hazard look reachable by a single failure when the model requires two.

## Steps to reproduce

Model: `fixtures/g2-name-case-collision/Case.aadl` (59 lines). The essentials:

```aadl
error behavior Receiver
  events
    key_exposed: error event;
  states
    Nominal: initial state;
    Key_Exposed: state;
    Compromised: state;
  transitions
    t_exp: Nominal -[ key_exposed ]-> Key_Exposed;
end behavior;
...
device Rx
  ...
  component error behavior
    transitions
      t_acc: Key_Exposed -[ i{Forged} ]-> Compromised;
  end component;
```

An `Attacker` device is the error source of `Forged` on the connection to `Rx.i`. Instantiate `Case::Top.i` and run Fault Tree Analysis on `rx`, start `state Compromised`, as a minimal cut set.

## Actual

```
Minimal cut sets for rx state Compromised:
  {attacker-es-forged}                          (order 1)
```

## Expected

```
  {attacker-es-forged, rx-key_exposed}          (order 2)
```

## Control

`fixtures/g2-name-case-control` is the same model with the event renamed `key_extracted`. It gives the expected order-2 cut set.

## Suggestion

Use case-preserving names for fault-tree events, or disambiguate state and event nodes (for example by element kind), or reject the collision with a validation error.
