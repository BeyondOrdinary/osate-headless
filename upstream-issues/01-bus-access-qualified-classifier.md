# Package-qualified classifier on a bus access feature does not resolve

**Labels (suggested):** bug, core / name resolution

## Summary

A `requires bus access` feature whose classifier is package-qualified (`PA::B1`) fails to resolve with *Couldn't resolve reference to BusFeatureClassifier 'PA::B1'*. The same qualified name resolves on a bus subcomponent in the same file, and the same feature resolves when the classifier is declared in the same package. A downstream error then appears at instantiation (*incompatible classifiers*).

## Steps to reproduce

Two packages in one AADL project:

```aadl
package PA
public
  bus B1
  end B1;
end PA;
```

```aadl
package PB
public
  with PA;
  system S1
    features
      x: requires bus access PA::B1;     -- line 6: error here
  end S1;
  system Top
  end Top;
  system implementation Top.i
    subcomponents
      s: system S1;
      b: bus PA::B1;                     -- resolves fine
    connections
      c: bus access b <-> s.x;
  end Top.i;
end PB;
```

Build the project. Instantiate `PB::Top.i`.

## Actual

```
ERROR PB.aadl:6  Couldn't resolve reference to BusFeatureClassifier 'PA::B1'.
WARNING PB.aadl:15 Expected feature 'x' to have classifier 'PA::B1'
ERROR (instance) 'b' and 's.x' have incompatible classifiers.
```

The same resolution error appears with `provides bus access` and with a bus implementation (`PA::B1.impl`). We first saw it in a larger model, on six different buses.

## Expected

`PA::B1` resolves for the bus access feature exactly as it does for the bus subcomponent `b`.

## Workaround

A local alias resolves:

```aadl
  with PA;
  renames bus PA::B1;
  ...
      x: requires bus access B1;
```

## Notes

- `BusFeatureClassifier` is the declared reference type for bus access features. Bus types implement it, and resolution succeeds for unqualified and renamed names, so the failure looks specific to qualified-name scoping for that reference type.
- Fixtures: `fixtures/l2-bus-access-qualified` (reproduction) and `fixtures/l2-bus-access-renames` (workaround). The reproduction was first seen in a 2,000-line model with six such features.
