# Connection and binding error sources are silently dropped when nothing carries them to a receiver

**Labels (suggested):** question or enhancement, error model / propagation graph

## Summary

Two ways of modelling an error that originates on a link produce no analysis result, and nothing says so:

1. **A connection error source** (`connection error ... error source c {Forged}` in the enclosing implementation) does nothing unless the component at the sending end of `c` declares an EMV2 out propagation on that port. With no out propagation there is no propagation path, no fault-impact row, no fault-tree event, and no warning. Add an out propagation to the sender (with no source of its own) and the connection source is analysed.
2. **A bus error source on `bindings`**, with a connection bound to that bus (`Actual_Connection_Binding`), produces a propagation path from the bus to the connection, but none from the connection to its destination. The receiver's fault tree shows the input as an undeveloped event rather than the bus source.

We'd like to know whether either behaviour is intended. If it is, a validation warning ("connection error source on c is not reachable: tx.o declares no out propagation") would have saved us a day. In a 75-component model, removing one out propagation silently disconnected two error sources from every analysis. We found it only by checking the propagation graph for each declared source.

## Steps to reproduce

**Case 1:** `fixtures/g3-connection-source-unreached/Conn.aadl`: `Tx` has no EMV2 annex, `Rx` declares `i: in propagation {Forged}` and a sink, and `Top.i` declares `ce_attack: error source c {Forged}`. The propagation graph has no paths, the fault-impact report has no rows, and there are no markers.

The control `fixtures/g3-connection-source-control` adds only `o: out propagation {Forged}` to `Tx`. One path then appears and `ce_attack` is analysed.

**Case 2:** `fixtures/g3-binding-source-unreached/Bind.aadl`: bus `Air` declares `bindings: out propagation {Forged}` and `es_air: error source bindings {Forged}`, connection `c` is bound to `air`, and `Rx` has `ef: error path i -> shown`. Fault tree on `rx`, start `outgoing propagation on shown{Forged}`:

```
Actual:   {rx-in-i-forged}   (Undeveloped)
Expected: {air-es_air-forged}
```

## Questions

1. Should a connection error source propagate when the sending end has no out propagation, treating the connection as the origin?
2. Should errors propagated to a connection through its binding continue to the connection's destination?
3. If neither is intended: could the validator warn about error sources that no propagation path can carry?
