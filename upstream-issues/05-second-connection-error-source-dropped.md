# Only one connection error source per connection is analysed; the others are dropped without a warning

**Labels (suggested):** bug or enhancement, error model / propagation graph

## Summary

A `connection error` section may declare several error sources on the same connection, and the model compiles without warnings. Only the first one declared is analysed. The others appear in no propagation path, no fault-impact row and no fault tree, and nothing reports that they were ignored.

`EMV2Util.findConnectionErrorSourceForConnection(ConnectionInstance)` returns a single `ErrorSource`, and the propagation graph appears to be built from it, so the second source never reaches any analysis.

This came up while modelling two different threats on one radio link (an outside attacker and an insider). The second threat silently disappeared from the fault trees, which made the hazard look better defended than the model said it was.

## Steps to reproduce

`fixtures/g6-second-connection-source-dropped/Conn.aadl`: `Tx` declares `o: out propagation {Forged, Replayed}`, `Rx` declares `i: in propagation {Forged, Replayed}` and a sink, and `Top.i` declares on the one connection `c`:

```
connection error
  ce_forge: error source c {Forged};
  ce_replay: error source c {Replayed};
end connection;
```

```
Actual:   one propagation path, from ce_forge {Forged}; ce_replay appears nowhere, no marker
Expected: paths from both sources, or a validation error saying only one source per connection is allowed
```

The control `fixtures/g6-one-source-per-connection-control` puts the same two sources on two connections, `c1` and `c2`. Both are analysed.

## Questions

1. Is more than one error source per connection meant to be allowed? The grammar and validator accept it.
2. If it is, could the propagation graph use every source declared on the connection?
3. If it is not, could the validator reject the second declaration?
