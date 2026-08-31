# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [0.9.0] - 31/08/2026

**Version numbering restarts here.** FlowPath was developed under a 1.x/2.x line while
its output formats were still moving; 0.9.0 is the first release cut against a settled
one, and 1.0.0 will accompany the paper. Because 0.9.0 sorts *below* the 2.x releases,
QuPath will not offer it as an upgrade — **remove any previously installed FlowPath under
Extensions → Manage extensions and install this one fresh.** The extension catalog has
been reset to match. Saved gate trees (`.json`) load unchanged.

### Changed — outputs

- **The two per-cell CSVs are joinable.** They were not. `umap_coordinates.csv` took its
  centroids from `CellIndex.getCentroidX`, which returns whatever space the measurement
  arrived in, while `gate_pheno.csv` wrote micrometres — both under a bare `centroid_x`,
  with the unit recorded in neither file. On MIRAGE input the two agreed by luck (MIRAGE
  emits µm, so the source space *is* µm); on the AnnoMask on-ramp, where the image is
  calibrated but centroids arrive in pixels, they disagreed by the pixel size and nothing
  threw. Both files now share one identity block from `io/CellTable`, and **the pixel
  space is named** (`centroid_x_px` / `centroid_y_px`) instead of being implied.
- **`umap_coordinates.csv` gained `label`**, the segmentation key, so it can be joined
  back to MIRAGE exactly rather than by nearest centroid. It also gained the morphology
  columns and now reports intensities per *resolved column* through `MeasuredColumn`, so
  its `_raw`/`_zscore` headers are the same columns `gate_pheno.csv` reports and the
  gating actually compared on.
- **`umap_coordinates.csv` is written as UTF-8 explicitly** rather than in the platform
  default charset.
- `centroid_x` / `centroid_y` keep their names and stay micrometres, and
  `Out_of_annotation` / `Outlier` keep their capitalisation and their `True`/`False`
  spelling. All four are a cross-repo contract with `mirage/bin/join_flowpath.py`, which
  addresses them verbatim; the odd casing in particular is load-bearing, because pandas
  infers real booleans from `True`/`False` and would read `true`/`false` as non-empty
  strings — every one of which is truthy.
- **Saved gate trees record their provenance.** `flowpath.json` gains a `meta` block with
  the FlowPath version, the save timestamp, the image name, the cell count and the marker
  panel. A gate tree reloaded against the wrong slide half-resolves — gates pointing at
  channels that are not there, NaN for every cell — and the file previously gave a reader
  nothing to notice that with. The format version is deliberately **not** bumped: the
  block is pure provenance, so an older FlowPath can still load the file.

### Added

- **FlowPath no longer computes a z-score of its own; a gate compares against columns
  that are in the export.** The old Raw / Z-score radio standardised the column against
  the cells currently loaded *and filtered*, so the same slider position meant a different
  cut once a quality filter or an annotation ROI changed. A gate defined that way could
  not be reproduced from the export alone, and a threshold quoted in a methods section
  would not identify the same cells on a re-run. **On a MIRAGE export there is now nothing
  to choose and no selector is shown** — MIRAGE emits no pre-standardised columns — and
  *what* to read stays with the compartment and statistic dropdowns, populated from the
  file.

  Should a pipeline export a pre-standardised column, that is a real column and appears
  as its own labelled option, composed by shape rather than from a hard-coded list.

  **Gate trees saved under the old mode are migrated on open**: the threshold is converted
  out of standard deviations and back into the column's own units, so the gate keeps the
  cells it had. Clearing the flag without converting would have left a threshold of around
  1 compared against intensities running to hundreds — every cell negative, no error, and
  a tree that still looked right.

### Fixed

- **An unrecognised compartment in a saved file silently became whole-cell.**
  `parseCompartment` was a bare `valueOf` inside `catch (Exception ignored)`, so a gate
  pinned to a nuclear column could reload pointing at the whole cell — a different
  population, no error, and a number that still looks plausible. The same defect
  `parseStatistic` was cured of. Both spellings are now accepted, case-insensitively.
- **UMAP diagnostics never reached QuPath's log viewer.** Six `System.err.println` calls
  (and one bare `printStackTrace`) in the UMAP engine wrote to a stdout nobody launching
  QuPath from the Finder ever sees. All now go through SLF4J, like the rest of the
  extension.
- Two swallowed `catch (Exception ignored)` blocks on measurement-resolution paths — the
  places where a silent failure becomes an all-NaN column — now log at debug.

<details>
<summary><b>Releases before the 0.9.0 renumbering</b></summary>

The entries below were released as 1.x and 2.x. They are kept for the record; version
numbers in them refer to that earlier line and do not compare with 0.9.0 onwards.

## [2.2.0] - 12/08/2026

The UMAP half works on a Mac. It did not before, and it did not say so.

### Fixed
- **Run UMAP did nothing at all on Apple Silicon, for essentially every real
  gated population.** Any training set of 10 000 cells or fewer with a connected
  neighbour graph sent SMILE to a spectral layout initialisation, which needs an
  ARPACK native library that has no `macosx-arm64` build. Every such run threw
  `NoClassDefFoundError`. FlowPath now owns that decision and steers onto SMILE's
  pure-Java PCA branch instead, by detaching one node from the graph and imputing
  its position from its true neighbours. **Embedding coordinates for datasets of
  10 000 cells or fewer therefore differ from any this extension produced
  before** — they are PCA-initialised now, with one cell's position imputed.
  Larger datasets were already taking the PCA branch and are unchanged.
- **A failed run left the panel spinning forever.** Seven exit paths through the
  compute service delivered no callback at all, and the run body caught
  `OutOfMemoryError` and `Exception` — so a plain `Error`, which is exactly what
  the missing ARPACK native throws, matched neither, escaped into a `Future`
  nobody read, and the UI sat in COMPUTING with no way back. Every run now ends
  in exactly one of four outcomes: succeeded, failed, cancelled or superseded.
  A failure names itself on the panel instead of being a spinner that never stops.
- **Unticking a marker in *Features…* did not exclude it.** The include flag
  changed one label in the picker and nothing else; the embedding ran over the
  whole panel regardless. It now decides which columns the computation reads.
  **Anyone who unticked markers and ran a UMAP will get a different embedding
  from this release on** — the earlier one was over more markers than the picker
  claimed.
- **Run UMAP invited a click it could not honour.** Fewer than two ticked markers
  is not an embedding, so the button is now disabled — in the toolbar and on the
  empty state, from the same derivation — and the overlay says how many are
  ticked rather than "Ready to embed".
- **Tagging a population read only the last segment of the class path.** Removing
  a tag never restored anything, because it looked for a class that never
  matched; re-tagging a tagged cell produced `"Rim: Core"` where it should have
  produced `"T cell: Core"`, taking the tag as the base class. **Projects saved
  with an earlier version may contain such malformed derived classes, and this
  release does not repair them** — re-tag the affected populations. The same
  truncation collapsed `"T cell: Rim"` and `"B cell: Rim"` into a single legend
  row in the standalone window.
- **Stratified subsampling merged distinct phenotypes into one stratum**, for the
  same reason: it grouped by the leaf class name, so two phenotypes wearing one
  tag were sampled as though they were one population.
- **The empty state promised a pre-selection that had not happened.** It read
  "Features are pre-selected from your gates" over a picker still sitting at
  everything-ticked, because seeding declines below two gated markers — and one
  gate on one marker is the ordinary first gate anyone draws. It now says which
  of the two you are looking at.
- Each axis of a 2D gate resolves its compartment and statistic against **its
  own** channel. An axis with no channel keeps the model's `MEDIAN` default,
  where the editor used to stamp `MEAN` over it; that stamp was inert for
  classification and is re-written only if such a gate is opened and saved.

### Added
- **Every successful run reports what it had to degrade**, because a degraded
  embedding looks exactly like a clean one. The first finding goes on the status
  line, the whole report in the tooltip behind it: cells the projection could not
  place (left at exactly `(0,0)`, where they read as a real cluster rather than
  as missing data), markers no training cell carried, markers with no variance,
  the imputed cell and the neighbourhoods reweighted around it, and the subsample
  size. A run with nothing to report says so and stays green.
- **The inputs lock for the duration of a run**, so what a result was computed
  from is what the panel was showing when Run was pressed; and Run is withheld
  while a feature change is still rebuilding the cell index, rather than starting
  a computation over columns that are about to be replaced.

### Removed (internal API)
No serialization format changed and no saved file is affected; these are
compile-level removals for anyone scripting against the extension's classes.
- `CellIndex.toMatrix()` — advertised in the v2.0.0 *Added* list. The matrix is
  now `EmbeddingFeatures.Selected#toMatrix`, which builds it from the ticked
  columns rather than from the whole panel; that difference is the include-flag
  fix above.
- `UmapComputeService.setOnComplete` / `setOnError` — replaced by
  `setOnOutcome`, the single callback the four-outcome type made possible.
- `UiStateController.setState` / `currentState` / `currentStateProperty` — the
  panel is pushed to from the session and no longer has a state to be set.
- `UmapSession.gateMask` / `describe`.

## [2.1.0] - 11/08/2026

Architectural deepening across the gating engine, measurement resolution and the
MIRAGE ingest seam. Three defects fixed; no format or contract changed.

### Fixed
- **A freshly created or just-cleared 2D gate drew every cell in the *Inside*
  colour while the engine classified every cell as *Outside*.** The scatter plot
  only installed an overlay once a shape was drawable (>=3 vertices, non-zero
  width/radius), and with no overlay its hit test answered "no overlay = all
  inside". So the plot said everything was selected while the gate selected
  nothing. Display and classification are now the same code, and
  `DisplayClassificationAgreementTest` pins the agreement across all five gate
  types including boundary cases.
- **The v2.0.1 sample-size fix was only half applied.** `CellIndex.KEY_SAMPLE_SIZE`
  was still 20 while `CompartmentCapability.DEFAULT_SAMPLE_SIZE` was 100, so a
  marker whose keys first appeared at cell 50 was offered by the capability scan
  and then failed to resolve. A second copy of the same drift lived in the UMAP's
  own marker discovery. Both are gone; the sizes are now one constant.
- **The gating -> UMAP snapshot identity check was falsifiable.** The UMAP's
  feature picker rebuilds its own `CellIndex` without updating the snapshot, so
  `snapshot.index() == incoming.index()` compared against a stale object and chose
  recolour against a different index. A rebuild onto a same-size, different-cells
  index also slipped past the record's length validation and painted old
  phenotypes onto new cells. Identity is now answered from the data
  (`describesSameCells`) and reconciled via `rebindTo`, which throws rather than
  migrate half-way.
- The centroid column could hold micrometres in some rows and pixels in others,
  because the ROI fallback was applied per axis. Fallback is now joint, and the
  resulting coordinate space is recorded.

### Added
- **`ScaleVerdict`** cross-checks the `Centroid X/Y um` measurement against
  `ROI x PixelCalibration`. MIRAGE's `params.pixel_size` is a static config value
  that is never auto-detected, so a wrong setting scales every micrometre value
  uniformly and MIRAGE cannot see it. FlowPath is the only place holding the
  pyramid calibration, the pixel ROI and the micrometre measurement at once.
  Reported in the status bar on disagreement.
- **`IngestReport`** — dropped channels, unresolved keys, cells missing a key the
  sample resolved, duplicate/null marker names, non-cell objects and the scale
  verdict. An empty histogram is no longer the only symptom of an unresolved axis.
  It also separates MIRAGE's two distinct silences: a measurement *omitted*
  upstream (failed join) versus a literal `0.0` (genuinely empty compartment).
- **`label` CSV column** when the measurement is present. `mirage/bin/join_flowpath.py`
  prefers an exact join on `label` and warns when falling back to a fuzzy centroid
  join. Note MIRAGE does not yet emit `label` into the GeoJSON, so closing this
  end-to-end needs the producer-side change too.
- `centroid_x_px` / `centroid_y_px` (additive; `centroid_x`/`centroid_y` keep their
  names and now reliably carry micrometres).

### Changed
- `MeasuredColumn` replaces the four-step resolve/ensure/read protocol. Skipping
  the registration step used to return a z-score of exactly 0.0 for every cell
  rather than failing - the cause of five separate v2.0.1 fixes. It is no longer
  expressible.
- `ResolvedGate.branchOf` is the single gate predicate. Five implementations
  became one; `GatingEngine` lost 217 lines. Measured marginally *faster*
  (13.0-13.2 ms vs 13.5-13.8 ms over 200k cells x 5 gates).
- `UmapSession` extracted from `UmapPane` (2063 -> 1716 lines) and is drivable
  without a JavaFX toolkit.
- `UndoHistory` extracted from `FlowPathPane`, with an injectable clock so the
  500ms coalescing window is testable.
- One shared test fixture (`testing/Cells`) replaces 12 private cell-builders;
  one `FxTestSupport` replaces two byte-identical copies.

### Unchanged (verified)
- CSV headers and values, and the JSON gate-tree format. `phenotype`,
  `centroid_x` and `centroid_y` are a cross-repo contract with
  `mirage/bin/join_flowpath.py`, which hard-fails without them.
- `GatingEngine.computeRoiMask` still compares pixels to pixels.
- Gating semantics for MIRAGE's literal-`0.0` empty compartment. That distinction
  is now *reported* but not acted on - changing it would change published numbers.

## [2.0.1] - 11/08/2026

Per-compartment gating works on a default MIRAGE export. It did not before.

### Fixed
- **Nuclear and cytoplasmic gating read NaN on a default-quantification export.**
  MIRAGE emits `Median` for every compartment it quantifies and adds `Mean`/`Sum`
  only with `--expanded_quantification`. FlowPath had that documented backwards
  and pinned a gate axis to `Mean` whenever a channel carried a single statistic
  — exactly the default case. The axis resolved to `"<marker>: <Compartment>:
  Mean"`, a key that is not in the file, so every cell read NaN: an empty
  histogram and a gate that classified nothing. Whole-cell appeared to work only
  because `(Whole-cell, Mean)` is the one selection with a bare-key fallback.
  Exports made with `--expanded_quantification` were unaffected.
- **The `W·med` badge appeared on a new gate and vanished.** Same cause: the gate
  model defaults to Median and the editor immediately overrode it. New gates now
  resolve their compartment and statistic against the image before reaching the
  tree, so the badge reflects what will actually be measured.
- **The UMAP feature seed had the same defect**, and its guard against a NaN
  column fell back to the very statistic it had just rejected. Both halves now
  call `CompartmentCapability.resolveCompartment` / `resolveStatistic`.
- Converting a threshold gate to a 2D gate stamped a whole-cell `Mean` Y axis
  onto the new gate, reading past the source's axis count. Only axes the source
  actually has are copied now.
- Compartment availability was sampled over 100 cells for gating but 20 for the
  UMAP, so a marker whose keys appeared later was offered in the gate editor and
  silently downgraded in the UMAP.

### Performance
- **Loading a per-compartment export is ~30x faster.** `CellIndex.build` took
  14.6 s for 50 000 cells x 169 measurements and now takes 0.5 s. The morphology
  lookup names (`area`, `Centroid X`) never match the exported names (`Area µm²`,
  `Centroid X µm`) exactly, so every cell fell through to two case-folding scans
  of its entire measurement map, seven times over. The keys are resolved once
  from a sample instead.
- **Switching compartment is no longer slow.** An unresolvable key takes
  `CellIndex`'s per-cell fallback, ~100x the resolved path (1225 ms vs 13 ms per
  column at 50 000 cells) — and the bug above guaranteed that path on every
  switch. Resolved columns now resolve their key once and do one lookup per cell.

### Changed
- Region gates (polygon, rectangle, ellipse) show their compartment badge in the
  gate tree, as quadrant and threshold gates already did. A nuclear region gate
  was previously indistinguishable from a whole-cell one.

## [2.0.0] - 10/08/2026

FlowPath and qUMAP are now one extension. Gating is the way in, and the UMAP
opens from it already knowing what the gates mean.

### Breaking
- **Two extensions became one.** The extension is now named `FlowPath` and
  installs a single menu item (`Ctrl+G`). QuPath keys extensions by name, so it
  will not treat this as an upgrade of *FlowPath - GatingTree* or
  *FlowPath - qUMAP* — remove both under Extensions → Manage extensions before
  installing, or you get three menu items and two disagreeing cell indices.
  Saved gate trees (`.json`) load unchanged.
- **The release artefact is now the fat JAR** (`FlowPath-<version>-all.jar`). The
  UMAP engine needs SMILE bundled; a thin JAR installs and then fails with
  `NoClassDefFoundError` on the first Run UMAP.
- The catalog lists a single `FlowPath` extension. AnnoMask and Decidware are no
  longer listed there; install them from their own repos.

### Added
- **Open UMAP** in the gating toolbar (`Ctrl+U`), which hands the UMAP view a
  `PhenotypeSnapshot`: the same `CellIndex` instance the gating walked, per-cell
  phenotype labels and colours, the exclusion mask, and the markers actually used
  as gate axes with their compartment and statistic.
- The UMAP now opens **pre-configured on the gated panel** rather than on every
  channel the slide carries, and defaults to colouring by phenotype rather than
  by an arbitrary marker.
- **Live recolouring.** A gate edit re-pushes the phenotyping to an open UMAP.
  Because editing a gate does not rebuild the index, the embedding survives and
  only its colours change — no recompute per threshold nudge.
- **Interactive legend:** click a population to hide it (hidden points are not
  drawn, so what they were burying becomes visible), hover to highlight it, and
  read each one's share of the total. A *show all* link appears while anything is
  hidden.
- `CellIndex.build(detections, markers, MarkerSelection)`, `toMatrix()` and
  `getMarkerValuesRaw()`, carried over from qUMAP.

### Changed
- **The UMAP window was rebuilt around the workflow.** Two dense toolbar rows of
  eighteen always-visible controls became a left rail ordered
  Cells → Embedding → Colour → Select, with advanced UMAP parameters folded away
  until asked for.
- Colouring by phenotype vs. by marker is now a two-segment switch rather than a
  marker dropdown whose `-- none --` entry silently meant "phenotype mode".
- **Progress moved inline.** The floating progress dialog — which opened over the
  plot and duplicated a Cancel the rail already showed — is gone; a progress bar
  and the current phase now sit directly under Run UMAP.
- An empty plot area now states what will happen, on how many cells, and offers
  the action, instead of printing "No UMAP data".
- `CellIndex` and `MarkerStats` had diverged between the two codebases and are
  reconciled into one. The fused `CellIndex` keeps FlowPath's morphology columns,
  lazy compartment columns and marker-name map, and gains qUMAP's
  `MarkerSelection` support, sampled-key resolution and ROI centroid fallback.
  `Compartment`, `Statistic`, `MeasurementKeys` and `CompartmentCapability` —
  previously byte-identical copies in both repos — now exist once.
- Marker-overlay z-scores use the population standard deviation (ddof=0),
  matching what `FeatureScaler` already used for the embedding and what gating has
  always used for thresholds. qUMAP's display path previously used the sample
  standard deviation, so the two disagreed by a factor of `sqrt(n/(n-1))` —
  invisible at realistic cell counts, but now consistent.
- `CellIndex` array accessors keep the no-copy contract. qUMAP's cloned; cloning
  on the gating hot path would allocate a full copy of the dataset (over a
  gigabyte on a multi-million-cell slide) purely to read it. The three qUMAP tests
  that asserted defensive copies now pin the no-copy contract instead.

### Fixed
- The UMAP no longer rebuilds its own cell index from the hierarchy while the
  gating pane has already curated one. Editing a feature previously re-queried the
  hierarchy without the annotation filter, silently widening the analysis back to
  the whole slide; it now reuses exactly the cells the snapshot indexed.
- Switching images with a snapshot active empties the UMAP and waits for the
  gating pane to re-index, rather than replacing the curated cell set with every
  detection on the new image.

## [1.10.1] - 23/07/2026

### Fixed
- Converting a 2D gate by drawing a different shape no longer loses the gate's
  comparison space. The z-score flag and both axes' compartment/statistic are now
  carried onto the replacement, so a boundary is evaluated in the space it was
  drawn in. Previously the overlay still rendered correctly over the points while
  every cell was misclassified.
- The z-score flag now has a single declaration on `GateNode` and defaults to
  z-score for every gate type. Region gates previously defaulted to raw while
  threshold and quadrant gates defaulted to z-score. Saved gate trees always wrote
  the flag explicitly and load unchanged.
- Fallback marker discovery no longer drops markers whose names begin with "x" or
  "y" (YAP1, XBP1, Xist). Single-letter coordinate columns are matched exactly
  rather than by prefix.
- Closing the FlowPath window detaches its hierarchy listener, which previously
  leaked the panel and its cell index on every reopen.
- Closing an image clears the preview service's state, so a later gate edit no
  longer re-runs gating onto the previous image's detections.
- Removing a gate while a branch-name field held focus no longer throws.
- `GateTree.findDuplicateLeafNames` no longer reports a name repeated inside one
  root as a cross-root collision.
- A gate with no channel round-trips through save/load instead of failing with an
  unchecked exception.
- Changing the channel on a quadrant or region gate rebuilds the compartment and
  statistic selectors, instead of keeping a selection the new channel may not have.

### Changed
- `CellIndex.getMarkerIndex` uses a lookup map rather than a linear scan; it is
  called once per cell per gate in the gating walk.

## [0.4.0] - 22/03/2026

### Added
- Unit tests for QualityFilter, GateNode, GateTree, ColorUtils, and FlowPathSerializer
- CI workflow for automated build validation on push/PR
- CHANGELOG.md

### Changed
- Fixed `qupathExtension.name` to human-readable "FlowPath"
- Fixed `automaticModule` to match package namespace (`qupath.ext.flowpath`)

## [0.3.4] - 22/03/2026

### Fixed
- Empty gate names now default gracefully instead of throwing errors
- Area measurement lookup searches for more key variants (e.g., `area µm²`)
- Improved CSV handling for edge cases

### Changed
- Improved code maintainability and safety

## [0.2.0] - 22/03/2026

### Added
- Quality filter pane (area, eccentricity, solidity, total intensity)
- Per-gate outlier exclusion via percentile clipping
- Graceful executor shutdown on QuPath close (fixes ConcurrentModificationException)

### Fixed
- Force-update PathClass colors on each preview (bypass QuPath global cache)
- Validate channels against image measurements before gating
- Compilation fix: defer lambda wiring, remove deprecated MeasurementList API

### Changed
- UI polish and transparency improvements

## [0.1.0] - 22/03/2025

### Added
- Initial release
- Hierarchical gate tree with positive/negative branching
- Live histogram with draggable threshold
- Real-time cell preview (~100ms update)
- Raw / Z-score threshold modes
- Save/load gate trees as JSON
- Export phenotype assignments as CSV
- QuPath Extension Manager catalog support
- GitHub Actions release workflow

</details>
