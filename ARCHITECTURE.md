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

## Cohort Gating

Every pattern above assumes one slide: one `CellIndex`, one `MarkerStats`, one gate tree walked
once per drag. Cohort gating puts a project of slides behind that same tree — one threshold,
carried to every slide, corrected for how that slide's staining differs from the reference slide
the numbers are written in. It is layered *on top of* the patterns above rather than replacing
them: each slide still gets its own column-oriented `CellIndex` (sampled, for the slides other
than the open one) and its own debounced, snapshot-isolated gating pass; what is new is the step
between "the tree" and "the numbers a pass actually uses."

### One resolution point

**Applied thresholds are computed only in `engine/TreeResolver`** (`resolve(tree, slideId,
alignments)` and `correctionFor(tree, gate, axis, slideId, lookup)`). `resolve` returns a
`GateTree.deepCopy()` with each slide's applied numbers substituted in place — same structure,
same channels — so `BranchTally.rebindTo` / `GateTree.pairBranches` pair the resolved copy with
the live tree exactly as they pair any other deep copy, and `ResolvedGate.branchOf` runs on it
unchanged: the engine never learns alignment exists. Every path that gates a slide calls
`TreeResolver.resolve` instead of a bare `deepCopy()` — `LivePreviewService` (the open slide),
`io/CsvExportJob.Snapshot.of` (an export), `batch/BatchRunner.gateDetailed` (every slide of a
run — `Settings`'s own `deepCopy()` only freezes the tree once against concurrent edits, before
the per-slide resolve), `cohort/ReviewScorer`, `cohort/CohortCurves`, `cohort/MarkerRules`,
`cohort/EvidenceCrop`, `cohort/ReviewAnswers` and `FlowPathPane.computeAncestorMask`. A `Skip`
setting marks the resolved copy `skippedOnSlide`, which `ResolvedGate` compiles unusable, so its
cells read `UNMEASURED` — never negative, the same rule a missing channel already followed. The
gate editor's own display seam (`ui/editor/EditorAlignment`) asks `TreeResolver.correctionFor`
for the same answer `resolve` would compute, so the line drawn in the editor and the line the
engine gates against cannot disagree about which axes are corrected.

### Alignment is per slide × column

A monotone map from reference units to one slide's units is a property of *how that slide was
stained and scanned* — never of a gate. Two gates on CD8 share one alignment; a child gate's
small parent population never destabilises it. `cohort/CohortSampler` draws a fixed-seed sample
per slide (`SEED`, xor'd with the slide id, so the same setting always gives the same sample);
`cohort/AlignmentModel` turns the sample into `model/cohort/Landmarks` per (slide, column) —
L1 the lowest-intensity *prominent* density peak (never the mode, which on a tumour-rich slide is
the positive peak), L2 the highest peak at least two density bandwidths above L1 — and composes
them into a `model/cohort/Alignment`. The per-column cofactor for the asinh transform is the
**reference slide's** median |x| over its clean sample for that column (`Landmarks.cofactor`,
through `CohortStats.median`) — a function of the reference alone, so it does not depend on the
order samples arrived in, a build with no cache reproduces a cached one exactly, and the GUI and a
headless run agree. With no reference sample there is no cofactor and no landmark: nothing to
align to. The cache (`io/AlignmentCacheFile`, `<project>/flowpath/alignment-cache.json`) is keyed
by each slide's `SlideSample.cacheKey()` — the sample fingerprint plus the digest of the filter
and ROI inputs its clean mask came from — and the recorded sample size; a cached landmark is
reused only under that key and the cofactor in force now. The live view
(`ui/CohortCoordinator`), the Cohort window and the batch run (`batch/FlowPathBatch`,
`batch/CohortEvidence`) all read the same model through `engine/AlignmentLookup` — the batch run
reuses it exactly, recomputing only when the cache is missing or its fingerprint has moved,
deterministically, from the same seed.

### The clean mask is the scored tree's

Landmarks, review flags, marker rules, the All slides curves and the evidence crops all read a
sample's **clean** cells, and "clean" is the quality filter and ROI of the tree being *scored* —
not of the tree the slide happened to be sampled under. `cohort/SlideSample` keeps what that
needs (detached copies of the slide's annotations, shape and class) and `scopedTo(tree)`
re-derives its mask and statistics whenever the tree's filter inputs differ from its `scope`
(`engine/CleanMask.inputsDigest`); `CohortSession.score` scopes every sample first and
`adopt` keeps the scoped samples, so the curves and crops read the same cells the review did.
The mask itself is built by `engine/CleanMask.of` — quality filter, then ROI, an ROI with no area
filtering nothing — the one composition `ui/GatingSession.derive` (the open slide) and
`batch/BatchRunner` (a run's slide) use too; `ui/CleanMaskAgreementTest` pins the live session and
a sample to the same mask. A filter change therefore re-derives the landmarks, in the background,
and misses the alignment cache; nothing else does.

### A review is of a number

`model/SlideSetting.Reviewed` holds the *applied* values it approved, not a flag. `cohort
.ReviewScorer.answered(gate, slideId, applied)` is the one place that decides whether a
`Reviewed` still matches what `TreeResolver` would apply today; when the reference threshold (or
an alignment) moves and the applied value changes, `answered` returns false and the item comes
back — there is no separate invalidation step to keep in sync. `cohort.MarkerRules` (spec §6
"Marker rules", flag type 5) is scored under the same rule so a slide already reviewed for a
different reason does not also reopen for a rule violation nobody re-checked. Slide settings
live on `GateNode`, keyed by `ProjectImageEntry.getID()` — never on a `Branch` or a `rootIndex` —
because the setting must survive drag-and-drop, `deepCopy()`, undo/redo and the serializer for
free, the same reasoning `PopulationRef`/`DenominatorRef` already establish for anything a user
selects (see "Anything the user selects is keyed on a value" in the project's invariants).

### Rules reuse the readout

`cohort/MarkerRules` judges every cell with `GateReadout.branchIgnoringClip` on the slide's
**resolved** tree (`TreeResolver.resolve`) — the same readout `PhenotypeCsvExporter`'s `_sign`
column uses — rather than re-implementing "which branch is this cell in". A cell either gate
reads `UNMEASURED` is left out of the rule entirely, never counted as a violation (the same
"unmeasured is not negative" rule the gating half already enforces). Rules only point at a likely
cause; nothing tunes a threshold to minimise a violation rate, because some of what a rule flags
— CD4+CD8+ T cells, touching-cell doublets — is real biology, not a miscalibrated gate.

### Overlays paint, never write

Review visuals are ordinary `PathOverlay`s (`ui/BoundaryOverlay`, on
`QuPathViewer.getCustomOverlayLayers()`): translucent veil plus outlined boundary cells, with no
`PathClass` write and no hierarchy event, so opening a review item cannot trigger
`IngestCoordinator` or dirty a `.qpdata` that was never asked for. Every actual phenotype write —
the live preview's settled pass and the batch run's write-back alike — goes through
`engine/PhenotypeClassWriter`, the one place a gating result becomes `PathClass`es, so a cell
classified by the batch on one slide reads exactly as the live preview would have classified it.
`umap/session/UmapSession.applyTag` / `removeTag` — the pre-existing UMAP polygon-selection tags,
predating cohort gating — are the one sanctioned exception: they write a derived tag onto an
already-computed phenotype's `PathClass`, not a gating result, and stay outside this rule.

### Project identity

Slide settings and `referenceSlideId` are keyed by `ProjectImageEntry.getID()`, and QuPath's
entry ids are a per-project counter — every project has an image `"1"`. A gate tree carried into
a different project could silently address a different image by the same id. `GateTree` records
`slideNames` (id → image name) alongside the settings; `cohort/CohortIdentity.matches` is the one
check of those names against a project's own image list, shared by the live view and the batch
run (`BatchRunner.refusal`, `FlowPathBatch`). A tree whose recorded name for some id disagrees
with that project's image is "foreign": `CohortIdentity.resolutionSlideId` resolves it to `null`
everywhere — every gate on its reference numbers, no slide setting honoured — and a batch run
refuses a foreign tree outright rather than apply one project's per-slide corrections to another
project's slides.

### No silent reference

A tree has **no reference until the user confirms one**. Nothing chooses it on their behalf: the
old open-slide default (`GatingSession.applyDefaultReference`, and the three-argument
`replaceTree(loaded, openSlideId, names)` that gave a loaded tree the open slide) is deleted — do
not reintroduce it. A default made the reference whichever slide happened to be open first, and
every corrected number in the cohort was then expressed on that slide without anyone having
chosen it. The reference is set in exactly two places, both user actions in the Cohort window
(`FlowPathPane.chooseReference`, spec 2026-09-30 §4.1):

- **Confirming** — ☆ on a row, or the banner's **Use X** — on a tree with no reference goes
  through `GatingSession.confirmReference(slideId, projectNames)`: one undo step, recorded before
  the change, with the project's image names recorded beside it (see "Project identity"). It
  refuses a tree that already has a reference. A tree with no gates takes the chosen slide
  directly; a tree that already has gates first asks *which slide these gates were drawn on*
  (pre-filled with the open slide) and makes **that** slide the reference, because the numbers
  on the tree are its numbers. Choosing the suggested slide afterwards is an ordinary change.
- **Changing** an existing reference asks once, then runs `CohortSession.rebaseReference` through
  `GatingSession.recordSlideEdit` — every gate's numbers re-expressed on the new slide, one undo
  step.

Both end in the one resync path and a background rescore. A tree with no reference
(`TreeResolver` then gates every slide on the tree's own numbers) is reported by
`CohortSession.state()` as `NO_REFERENCE`, never as an all-clear, and its status line never says
"Ready to run". The suggestion (`CohortSession.suggestedReferenceId`) is only ever offered, never
applied, and is never the current reference or an excluded slide.

### The suggestion is the joint medoid of eligible slides

`cohort/ReferenceRanking` ranks the sampled, non-excluded slides over the gated columns. A slide
is **eligible** only if, on every gated column, `Landmarks.find` with `Landmarks.cofactor` of its
own clean values finds an L1 — the same finder and cofactor `AlignmentModel.build` would use were
it the reference, so the ranking cannot call a slide a good reference that the engine would then
fail to align to (the old failure: `Alignment.between` silently answering identity for a column
whose reference lacked L1) — and has at least the cohort's modal landmark count (fdaNorm's rule).
Among eligible slides the suggestion is the one with the smallest summed L1 distance between
normalised log(1 + x) histograms, summed over the gated columns; fewer than three eligible
slides gives no suggestion. The ranking is computed in the same background `score` as the
alignments and review, and arrives in the same `Scored` record.

### Excluded slides

A slide can be set aside from the cohort (row menu **Exclude / Include**). The flag lives on the
project's image entry as metadata (`flowpath.cohort.excluded`), read and written only through
`cohort/CohortExclusions` — **not** in `flowpath.json`: exclusion is a fact about a slide, not about
a gate tree, and a tree field would force a format version. It is not an undo step; toggling
again reverses it. An excluded slide is not sampled (`FlowPathPane.refreshCohort` filters it out
of the sources after handing the set to `CohortSession.setExcluded`), so it has no alignment, plays no part in ranking, modal counts or review, and
`CohortSession`'s lookup answers null for it — identity through the one resolution point. A batch
run still gates it, uncorrected, and records `cohort_excluded` in `qc_summary.csv`. Its
`SlideSetting`s stay on the tree and reappear if it is included again. Excluding the current
reference is refused ("Pick another reference first"). The exclusion set is deliberately **not**
part of the sampling key: excluding a slide drops only its sample and review
(`CohortSession.setExcluded`) and rescores, keeping every other slide's sample, while including
one again forces a single re-sample (its sample was never taken). A failed metadata save
restores the entry's previous value before the error is reported (`CohortExclusions.write`), so a
refused toggle never takes effect in memory.

### Data flow of the Cohort window

```
exclusions, samples, tree ─► CohortSession.score (flowpath-background)
                               ├─ AlignmentModel.build   (excluded → not sampled → no alignment)
                               ├─ ReviewScorer
                               └─ ReferenceRanking       (same Scored record)
                             adopt (FX) ─► FlowPathPane.renderCohort
                               └─ CohortGridModel.derive ─► CohortGridPane.render / CohortCard.render
```

Exclusion toggles, reference confirmations and sample-size changes all go through the existing
background `score` request; no new landing path is added (see "`resync`'s synchronous fallback
is a safety net" in the project's invariants). The window is re-rendered after every adopt and
every gating pass while it is open, as the Analysis window is pushed. The grid keeps its own
selected cell by value (`ReviewItem.Key`), because a cell may have no review item (✓, ↷, ✎, ⊘)
and `CohortSession.selected()` answers only for items; a cell that is an item is selected in the
session too, so Enter and S answer it.

### The batch run never writes a slide it did not check live

`batch/BatchRunner.writeBack` asks its `isOpen` predicate — fed a volatile field the FX thread
keeps current — **three times**: before the slide is read (`openAtRead`, so a save that lands
between the read and the write is not silently discarded), again right before the phenotype
classes are applied, and again right before `ProjectImageEntry.saveImageData`. Any of the three
answering "open" turns the slide's `WriteBack` outcome into `SKIPPED_OPEN_SLIDE` rather than a
write: QuPath owns that file while it is open, and the live preview has already classified it
under the same resolved tree, so nothing is lost — the user saves it from QuPath as usual. Every
slide, the open one included, is read from its data file, so the open slide's phenotype CSV and
population rows describe its last save, not edits made in the viewer since; `BatchRunner.summary`
says so whenever a slide was skipped for being open.

### Layers

- `model/GateValues` — a gate's axis numbers as a value, independent of gate type; what
  `TreeResolver` reads, maps and writes back
- `model/SlideSetting` — `Manual` / `Skip` / `Reviewed`, sealed, on `GateNode`
- `model/GateWalk` — enabled-root, path-qualified iteration shared by the manifest exporter,
  the marker rules, `qc_summary.csv` and the review scorer. A label repeated among enabled
  siblings carries its ordinal (`CD3+/CD8#2`), and so does a repeated branch name among those
  siblings' branches (`CD3+/CD8+#2/CD4`), so every `(rootIndex, gatePath)` names one gate;
  `CohortSession.liveGate` refuses a key two gates still answer to
- `model/cohort/Density`, `Landmarks`, `Alignment` — peak-finding in asinh space and the
  monotone map (and inverse) it produces; toolkit-free, no `PathObject`
- `engine/TreeResolver`, `engine/AlignmentLookup` — the one resolution point (above), and the
  narrow `(slideId, column) → Alignment` interface it and every cohort reader depend on instead
  of `AlignmentModel` directly
- `engine/PhenotypeClassWriter` — the one place a gating result becomes `PathClass`es (above);
  its javadoc records why the batch recolouring the shared `PathClass` instances from a background
  thread is benign
- `engine/CleanMask` — the one quality-then-ROI composition of a slide's clean cells, and the
  digest of its inputs (above)
- `cohort/CohortSampler`, `SlideSource`, `SlideSample`, `CohortPrefs` — the fixed-seed per-slide
  sample (scoped to the scored tree's filters, above), its source of detections (project or
  headless), and the sampled-cells-per-slide preference (default 20 000;
  `CohortPrefs.DEFAULT_SAMPLED_CELLS`)
- `cohort/AlignmentModel` — `(slide, column) → Alignment`, the reference-slide cofactor per
  column, the landmark cache and the cohort-median/MAD unusual-staining check
- `cohort/ReviewScorer`, `ReviewItem`, `ReviewAnswers`, `BoundaryHotspot` — the four review flags
  (no landmark, unusual staining, on a peak, can't judge), the three answers and their undo
  handling, and the sample-tile hotspot both the viewer centring and the evidence crop use
- `cohort/CohortCurves`, `CohortCurvesCache` — every sample's aligned values for the shown gate
  (All slides), memoised on gate identity + axis triples + correction + ancestor fingerprint
- `cohort/CohortSession`, `CohortState` — what the panel may offer, derived, the `ViewState`
  pattern; toolkit-free
- `cohort/CohortIdentity` — the one foreign-tree check (above)
- `cohort/MarkerRules`, `ReviewGroup` — flag type 5 (above), and grouping review items by gate
  for Shift+Enter
- `cohort/EvidenceCrop`, `CellShapes`, `ui/EvidenceCropCoordinator` — the 200 µm tissue crop, the
  boundary-cell shapes drawn on it, and the dedicated `flowpath-crops` executor (image reads must
  never queue behind gating on `flowpath-background`; prefetch 3, LRU 64). The crop is shown in
  the Cohort window's detail for the selected **review item** only; a grid cell with no item
  clears it
- `batch/BatchRunner`, `BatchSlide`, `BatchResult` — one resolved tree per slide, failures as
  values, the live open-slide check (above)
- `batch/GatingManifestExporter` — `gating_manifest.csv`; lives in `batch`, not `io`, because
  `batch` already depends on `io` (`CellTable`, `PhenotypeCsvExporter`) and the reverse import
  would cycle
- `batch/FlowPathBatch` — the headless entry point the GUI's "Run on all slides" also calls;
  resumable (`RunState`, `.flowpath-run.json`, per-slide fingerprints that ignore other slides'
  settings) and the source of the run's provenance bundle (`CohortEvidence`, `flowpath.json`,
  `qc_summary.csv`, `run_info.txt`). `RunState.fingerprint`'s detection half
  (`CohortSampler.detectionFingerprint`) hashes every cell's centroid but only
  `model/MeasurementKeySample`'s bounded sample of values, so a re-quantification confined to
  unsampled cells' values is not detected and the slide resumes unchanged
- `io/AlignmentCacheFile` — `<project>/flowpath/alignment-cache.json`; derived, safe to delete,
  never in undo; each landmark is stored with the cofactor it was found with, so one found under
  an earlier reference is never reused as if found under the current one
- `ui/CohortCoordinator`, `BatchRunCoordinator` — one background worker each, one slide per task
  for the batch run, so other work (a gating pass, an export) interleaves rather than queuing
  behind a 50-slide run
- `ui/ProjectSlides` — `ProjectImageEntry` → the headless `SlideSource`/`BatchSlide` seams, and
  `refs()`, the `(id, name)` pairs `CohortSession` turns into the `projectNames` map
  `CohortIdentity` checks against
- `cohort/ReferenceRanking` — the suggestion (above): eligibility through the alignment's own
  landmark finder and cofactor, then the joint medoid; pure
- `cohort/CohortExclusions` — excluded slides as project-entry metadata, not `flowpath.json`; the
  QuPath adapter (`of(project)`) is the only code that touches the entry's flag
- `ui/cohort/CohortGridModel` — the slides × gates grid, its banner and the selected cell's
  detail, derived from `CohortSession` and the tree; toolkit-free and table-tested
- `ui/cohort/CohortGridPane`, `CohortWindow`, `ui/CohortCard` — the Cohort window's body (renders
  a `CohortGridModel`, reports actions, decides nothing), its single floating stage (the pane
  outlives the stage, as the Analysis pane does), and the side panel's status card with
  **Open cohort… / Choose reference…** and **Run on all slides…**
- `ui/BoundaryOverlay` — the paints-never-writes overlay (above)
- `ui/editor/EditorAlignment` — the editor's display seam onto `TreeResolver.correctionFor`

`io/FlowPathSerializer` reads **version 4**: `slideSettings`, `lineageMarker` and
`correctStaining` per gate, `referenceSlideId` and `slideNames` on the tree. It *writes* version 4
only when a tree carries cohort state an older reader would drop with a consequence (a reference
slide, slide names, a slide setting or a lineage tick — `versionFor`), and version 3 otherwise, so
FlowPath 0.9.4 still opens every tree saved by someone who never used a cohort. `correctStaining`
is written on every gate at either version and read whenever present; only a gate with no key at
all — a file written before v4 — loads with correction off, so opening an old tree never changes
a number. `ui/BusyState` gained two more workers, `(loading, deriving, exporting, sampling,
batchRunning)`: sampling and a batch run each read from a copy of the tree taken when they
started, so neither blocks editing the live one. Sampling does block *starting* a run
(`batchBlocked`): mid-sampling the alignments lack the slides not sampled yet. `batchAllowed`
(not blocked, and an enabled gate) is the one predicate the Run button and the status line's
"Ready to run" both read; the run's confirmation names the slides whose sampling failed, which
run uncorrected.

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
