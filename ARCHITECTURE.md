# Architecture

FlowPath classifies hundreds of thousands of cells interactively — the user drags a threshold slider and every cell on the image updates in real time. This document explains the data structure and concurrency patterns that make this possible, focusing on **why** each design decision was made and how these patterns apply to any performance-sensitive analytical application.

## The Problem

Given N objects (cells), each with M numeric attributes (marker intensities), apply a tree of threshold decisions and return a classification for every object. This must run in under ~80ms so the UI stays responsive during interactive exploration.

The naive approach — iterating objects and looking up attributes by name from a map — does not scale. For 200,000 cells with 20 markers, that would be millions of hash-map lookups, boxed number conversions, and scattered memory accesses per gating pass.

## Column-Oriented Storage

### The Concept

Most languages and ORMs store data **row-oriented**: one object per entity, each containing all its attributes. This is natural for CRUD operations (create, read, update, delete one entity at a time) but performs poorly for analytical scans that touch one attribute across all entities.

**Column-oriented storage** transposes the data: instead of N objects with M fields, you store M arrays of length N. Each array holds one attribute for every entity, laid out contiguously in memory.

```
Row-oriented (object per entity):        Column-oriented (array per attribute):
┌───────────────────────────────┐        ┌──────────────────────────────┐
│ Entity 0: {a=1.2, b=0.8, …}  │        │ a: [1.2, 0.5, 2.1, …]       │  ← contiguous double[]
│ Entity 1: {a=0.5, b=1.1, …}  │  ──►   │ b: [0.8, 1.1, 0.3, …]       │  ← contiguous double[]
│ Entity 2: {a=2.1, b=0.3, …}  │        │ refs: [obj0, obj1, obj2, …]  │  ← back-references
│ …                             │        └──────────────────────────────┘
└───────────────────────────────┘
```

This is the same principle behind Apache Arrow, Parquet, DuckDB, and every modern analytical database.

### Why It's Faster

**CPU cache locality.** A modern CPU cache line is 64 bytes, which holds 8 `double` values. When you iterate a contiguous `double[]`, every cache line fetch gives you 8 entities worth of useful data. The hardware prefetcher detects the sequential pattern and begins loading the next cache line before you need it.

In the row-oriented layout, each entity is a separate heap object. Iterating them means pointer-chasing from object to object — each at a random memory location. A cache line fetch might contain just 1 useful value surrounded by unrelated fields. The prefetcher cannot predict the next location.

**No per-access overhead.** Row-oriented access typically involves:
1. Dereference the object pointer
2. Call `getAttributeMap()` or similar accessor
3. Hash the attribute name string
4. Walk the hash bucket chain
5. Unbox the `Number` wrapper to get the primitive

Column-oriented access is:
1. `array[index]` — a single indexed memory read

For N = 200,000 entities, eliminating the map lookup and unboxing overhead removes millions of redundant operations per analytical pass.

### When to Use Column-Oriented Storage

Use it when:
- You scan one or a few attributes across **all** entities (analytical queries, filtering, aggregation)
- The data is **read-heavy after construction** — built once, queried many times
- Entity count is large enough that cache effects matter (typically > 10,000)
- Attributes are **primitive numeric types** (doubles, ints, floats)

Don't use it when:
- You primarily access all attributes of **one entity at a time** (CRUD, rendering one entity)
- Data is frequently mutated per-entity (updates require touching multiple arrays)
- The attribute set varies per entity (sparse/heterogeneous data)

### How FlowPath Implements It

`CellIndex` stores the transposed data:

```java
double[][] values;          // values[markerIndex][cellIndex] — marker intensities
double[] areas;             // morphology columns
double[] perimeters;
double[] eccentricities;
PathObject[] objects;       // back-references to original QuPath objects
```

The `build()` factory method performs the transpose **once** when the image is loaded, extracting measurements from QuPath's row-oriented `PathObject` instances into contiguous arrays. After this, the original map-based measurements are never touched during gating.

Access during gating:
```java
double rawValue = index.getMarkerValues(markerIdx)[cellIdx];  // single array index
```

### Tradeoffs

| Advantage | Disadvantage |
|-----------|-------------|
| Sequential memory access (cache-friendly scans) | Data is duplicated out of the original objects |
| No per-access overhead (no hashing, no unboxing) | Construction cost is O(N x M) — paid upfront |
| Columns can be shared across threads (immutable) | Updating one entity requires writing to M arrays |
| Natural fit for SIMD and vectorization | Not suitable for entity-level CRUD patterns |
| Memory layout matches the analytical access pattern | Extra memory proportional to N x M doubles |

## Precomputed Statistical Indexes

### The Concept

When the same statistical queries are repeated many times over unchanging data, the results should be computed once and stored in a structure optimized for the query pattern. This is analogous to database indexes: you trade construction time and memory for query-time speed.

Common precomputed structures:
- **Sorted array** — Enables O(1) percentile/quantile lookups by direct index calculation
- **Histogram bins** — Pre-bucketed frequency counts, ready to render without re-scanning
- **Summary statistics** — Mean, standard deviation, min, max for normalization

### Sorted Arrays for O(1) Percentile Lookups

A sorted array of N values lets you find any percentile in constant time:

```
index = (percentile / 100) × (N - 1)
value = sorted[floor(index)] + frac × (sorted[ceil(index)] - sorted[floor(index)])
```

No binary search needed — the index is computed directly from the desired percentile. This is critical when percentile lookups happen frequently (e.g., outlier clipping checks on every gating pass).

Without a sorted array, finding the p-th percentile requires either:
- Sorting on demand: O(N log N) per query
- Selection algorithm (quickselect): O(N) per query, but with high constant factors

The tradeoff is O(N log N) sort + O(N) memory once, versus O(1) per lookup thereafter.

### Pre-Binned Histograms

If a histogram will be rendered repeatedly at the same resolution (e.g., 200 bins), bin the data once during construction. The rendering code then draws from a `double[200]` count array instead of scanning all N values to bucket them.

The bin width is fixed: `(max - min) / numBins`. Each value maps to a bin via: `bin = (int)((value - min) / binWidth)`.

### How FlowPath Implements It

`MarkerStats` computes all of the above per marker in a single pass:

```java
double[] passing = extractQualityPassingValues(raw, qualityMask);
Arrays.sort(passing);                          // sorted array for percentiles
double mean = sum(passing) / N;                // mean for z-scores
double std = sqrt(sumSquaredDev(passing) / N); // std for z-scores
double[] counts = binIntoHistogram(passing);   // 200-bin histogram
```

These are computed once per image load (and recomputed when quality filters change), not on every threshold drag.

### Tradeoffs

| Advantage | Disadvantage |
|-----------|-------------|
| O(1) percentile lookups from sorted arrays | O(N) extra memory per sorted column |
| O(1) z-score conversion from precomputed mean/std | Must recompute if underlying data changes |
| Histogram rendering is instant (no re-scanning) | Fixed bin resolution — zoom/re-range requires recomputation |
| Construction cost amortized over many queries | Stale if source data is mutated (must be treated as immutable) |

## Debounce + Snapshot Concurrency

### The Concept

In interactive applications, user input (mouse drags, slider moves) fires events at a rate far exceeding the computation rate. If each event triggers an expensive operation, the system either queues work faster than it can complete (growing latency) or blocks the UI thread (frozen interface).

The solution is a three-part pattern:

1. **Debounce** — Delay execution by a short interval (e.g., 80ms). If another event arrives before the delay expires, restart the timer. This coalesces rapid-fire events into a single computation.

2. **Immutable snapshot** — Before handing data to a background thread, create an immutable copy of any mutable state. The background thread works on the snapshot while the UI thread continues to accept input and modify live state. Data structures that are already immutable (like `CellIndex` and `MarkerStats`) can be shared without copying.

3. **Single worker thread** — Use a single-threaded executor for the heavy computation. If a new request arrives while one is running, it naturally queues behind and supersedes stale results when applied.

```
User input events (rapid):
  ╠══╦══╦══╦══╦══╦══════════╗
  ║  ║  ║  ║  ║  ║  80ms    ║
  ╚══╩══╩══╩══╩══╩══════╦═══╝
                         ▼
              Debounce fires once
                         │
                    ┌────┴─────┐
                    │ Snapshot  │  (deep-copy mutable state)
                    └────┬─────┘
                         │
              ┌──────────┴──────────┐
              │  Background thread  │  (heavy computation)
              └──────────┬──────────┘
                         │
              ┌──────────┴──────────┐
              │  UI thread applies  │  (results → visible state)
              └─────────────────────┘
```

### What Needs Copying vs. What Doesn't

The key insight: **only mutable state needs snapshotting.** If a data structure is immutable after construction, it can be freely shared across threads with no copying and no synchronization.

| Data | Mutable? | Copy needed? | Reason |
|------|----------|-------------|--------|
| Column arrays (`CellIndex`) | No (built once) | No | Read-only after construction |
| Statistics (`MarkerStats`) | No (built once) | No | Read-only after construction |
| Gate tree (`GateTree`) | Yes (user edits thresholds) | Yes (`deepCopy()`) | Background thread must not see mid-edit state |
| Output arrays | N/A | N/A | Created fresh by background thread |

This means the expensive data (200k+ cell values) is **never copied** — only the small gate tree (typically < 20 nodes) is deep-copied per gating pass.

### How FlowPath Implements It

`LivePreviewService` orchestrates all three layers:

- **Debounce:** JavaFX `PauseTransition` with 80ms duration. Each `requestUpdate()` call restarts it.
- **Snapshot:** `GateTree.deepCopy()` creates a frozen tree. `CellIndex` and `MarkerStats` references are captured (immutable, no copy).
- **Background thread:** Single daemon thread via `Executors.newSingleThreadExecutor()`. After gating completes, `Platform.runLater()` applies results on the JavaFX thread.
- **Result application:** One loop assigns `PathClass` to each cell, then a single `fireHierarchyChangedEvent()` triggers the viewer repaint.

### Tradeoffs

| Advantage | Disadvantage |
|-----------|-------------|
| UI never blocks — input stays responsive | Results are delayed by the debounce interval |
| No locks or synchronized blocks needed | Deep-copying mutable state has a cost (small if the mutable structure is small) |
| Easy to reason about (single writer, immutable readers) | Stale results may briefly appear if computation is slow |
| Naturally handles "last write wins" semantics | No incremental/partial update — always a full recomputation |

## Parallel Output Arrays

### The Concept

When a computation produces multiple attributes per entity (e.g., a label, a color, and a flag), a common pattern is to create one object per entity to hold the results. For large N, this means N heap allocations, N constructor calls, and scattered memory.

An alternative is **parallel arrays**: one array per output attribute, all of length N, indexed by the same entity index.

```java
// Instead of:
Result[] results = new Result[n];  // N object allocations

// Use:
String[] labels = new String[n];   // one array allocation
int[] colors = new int[n];         // one array allocation
boolean[] flags = new boolean[n];  // one array allocation
```

The arrays are "parallel" because `labels[i]`, `colors[i]`, and `flags[i]` all describe entity `i`.

### Why It's Faster

- **Fewer allocations:** 3 array allocations instead of N object allocations. Less GC pressure.
- **Cache-friendly writes:** When the computation iterates entities sequentially, writes to each array are sequential — good for write-combine buffers.
- **No object headers:** Each Java object has a 12–16 byte header. For 200k result objects, that's ~3 MB of headers alone.
- **Pass by reference through recursion:** The arrays can be passed as parameters into recursive tree walks, with each level writing directly to the final output. No intermediate collections to merge.

### Tradeoffs

| Advantage | Disadvantage |
|-----------|-------------|
| Minimal allocation (3 arrays vs. N objects) | Less readable — parallel arrays lack the named-field clarity of objects |
| Cache-friendly sequential writes | Easy to introduce bugs if arrays get out of sync |
| Can be passed through deep call stacks without wrapping | Not suitable when the result set is sparse or variable-length |
| Low GC pressure | |

## Sharing One Index Between Two Views

FlowPath has two views onto the same cells: the gate tree that phenotypes them and the UMAP that embeds them. They used to be separate extensions, and the way they communicated is worth recording as a cautionary pattern.

### The pattern to avoid: coordinating through global mutable state

The gating side wrote each cell's phenotype into QuPath's `PathClass` — a field on the shared object graph. The UMAP side read it back:

```java
// Producer, in the gating extension
obj.setPathClass(PathClass.fromString(phenotype, color));

// Consumer, in the UMAP extension — a different JAR, a different index
PathClass pc = objects[i].getPathClass();
int color = pc.getColor();
String name = pc.getName();
```

It worked, and it is a tempting design: neither side needs to know the other exists, and the coupling is invisible. But routing a producer/consumer relationship through a shared mutable store costs three things:

1. **The consumer cannot trust identity.** `PathClass` says what a cell *is*, not which analysis produced it or under what filters. The UMAP had to rebuild its own `CellIndex` from the hierarchy — a second full row-to-column transpose over hundreds of thousands of objects — because it had no way to know the producer's index covered the same cells in the same order.
2. **The channel is lossy.** Only the label and the colour survive the round trip. Which markers were gated, in which compartment, under which quality filter — none of that fits in a `PathClass`, so the consumer opened asking the user to re-specify what the producer already knew.
3. **The encoding leaks.** Composite phenotypes are joined with `": "`, so the consumer parses names by string surgery (`lastIndexOf(": ")`) to strip population tags. Any phenotype name containing that separator is a latent bug.

### The pattern that replaced it: an explicit immutable handoff

`PhenotypeSnapshot` is a record carrying everything the consumer needs, published by the producer at a well-defined moment:

```java
public record PhenotypeSnapshot(
        CellIndex index,          // the SAME instance, shared not copied
        MarkerStats stats,
        List<String> markerNames,
        CompartmentCapability capability,
        String[] phenotypes,      // positional against index.getObjects()
        int[] colors,
        boolean[] excluded,
        List<String> gatedMarkers,
        MarkerSelection gateSelection,
        int gateCount,
        String imageKey) { … }
```

Three properties do the work:

**Shared identity, not copied data.** The snapshot passes the `CellIndex` *by reference*. Cell *i* means the same cell on both sides by construction, so the consumer needs no rebuild — the expensive transpose happens once per image rather than once per view. The constructor enforces the invariant that makes this safe:

```java
int n = index.size();
if (phenotypes.length != n || colors.length != n || excluded.length != n) {
    throw new IllegalArgumentException(…);
}
```

A misalignment here would not crash — it would draw the wrong colours on the right points, which is far worse than a failure. Validating at construction converts a silent data corruption into a loud one.

**Identity as a cheap invalidation check — and why a pointer was not enough.** Because the index is shared rather than copied, an identity test answers a question that would otherwise need an expensive diff. The first version of this used `==`:

```java
boolean sameCells = snapshot != null && snapshot.index() == incoming.index();
```

Editing a gate re-walks the existing index; it does not rebuild it. So the same index arriving again means the *coordinates are still valid* and only the colours changed — the consumer recolours in a single pass instead of discarding a multi-minute embedding. A different index (new image, changed filter, changed feature resolution) means everything derived from it is stale.

**That pointer comparison turned out to be falsifiable, and the reason is instructive.** It is correct only while the premise holds that *nobody rebuilds an index behind the snapshot's back*. The producer never did — but the **consumer** did: the UMAP's feature picker rebuilds its own `CellIndex` from `snapshot.index().getObjects()` and installs it, without updating the snapshot field. From then on `==` compared the incoming snapshot against a stale object, answered `true`, and chose "restyle" against coordinates derived from a different index. A rebuild onto a *same-size, different-cells* index was worse still: it satisfied the record's length validation, so nothing threw and the old phenotypes were painted onto the new cells.

The fix was not a better pointer. It was to stop asking a question the pointer could not answer:

```java
public boolean describesSameCells(CellIndex other)   // same PathObjects, in the same order
public PhenotypeSnapshot rebindTo(CellIndex other, MarkerStats otherStats)  // or throw
```

`describesSameCells` stays O(1) for the two common cases — same instance, or different cell count — and only walks references when two *distinct* indices agree on size, which is exactly the derived case it exists to recognise. It cannot be falsified by a field going stale, because it inspects the objects rather than trusting a reference. `UmapSession` then enforces the invariant explicitly (`snapshot() == null || snapshot().index() == index()`) and rebinds *before* mutating, so a cell-set-changing rebuild throws rather than migrating half-way.

**The generalisable lesson:** an invariant that holds "for free" holds only as long as every party respects the premise it rests on. If the premise is not stated in the interface, a future change on the *other* side of the seam can void it without anything looking wrong. State the invariant, or verify it from the data.

**Intent, not just results.** `gatedMarkers` and `gateSelection` carry *what the user was doing*, not only what came out. That is what lets the consumer open pre-configured on eight relevant markers instead of presenting forty raw channels — information that simply had nowhere to live in the `PathClass` channel.

### The generalisable rule

When two components need to agree about a large dataset, prefer an explicit immutable handoff over coordination through shared mutable state — even when the shared store is right there and the coupling looks free. The handoff lets you pass identity by reference (eliminating defensive rebuilds), validate the contract at the boundary (turning silent corruption into an exception), use pointer equality as a cheap staleness check, and carry intent alongside data.

The cost is that the producer must now know a consumer exists. That is usually the honest accounting: the dependency was always there, it was just undeclared.

## Putting It All Together

These patterns compose into a pipeline:

```
[Image Load — one-time]
  PathObjects (row-oriented) ──► CellIndex (column-oriented)
  CellIndex + QualityMask ──► MarkerStats (sorted arrays + histograms)

[Interactive — repeated on every slider drag]
  User input ──► debounce (80ms) ──► snapshot GateTree
  Background thread:
    CellIndex columns + MarkerStats lookups + GateTree walk
    ──► parallel output arrays (labels, colors, flags)
  JavaFX thread:
    Apply results to QuPath objects ──► single repaint event
    Wrap the same arrays in a PhenotypeSnapshot ──► push to the UMAP view
      (same CellIndex reference ⇒ recolour, don't recompute)
```

The expensive row-to-column transpose and statistical precomputation happen once. The repeated gating pass operates entirely on cache-friendly arrays with O(1) lookups, running on a background thread that never blocks the UI.

## When to Apply These Patterns

These patterns are not specific to cell biology or image analysis. They apply to any application that:

1. **Scans a large collection by attribute** — column storage beats row storage for analytical filters, aggregations, and threshold-based classifications.
2. **Repeats statistical queries on stable data** — precompute sorted arrays, histograms, and summary statistics once; query in O(1) thereafter.
3. **Must stay interactive during expensive computation** — debounce + immutable snapshot + background thread keeps the UI responsive without complex locking.
4. **Produces per-entity results at scale** — parallel arrays avoid N object allocations in the hot path.
5. **Has two components that must agree about the same large dataset** — an explicit immutable handoff beats coordinating through shared mutable state: identity passes by reference, the contract is validated at the boundary, and pointer equality becomes a free staleness check.

Examples beyond this project: real-time dashboards over time-series data, interactive data exploration tools, game engines with component-based entity systems (ECS), financial risk engines scanning portfolios, log analysis with threshold-based alerting.
