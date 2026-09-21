# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

A post-release review round of the gating half: ten tasks, each reviewed against the diff
behind it. The computed z-score is gone for good, every change to the tree, a filter or the
image now ends in one resync, detection reads, statistics recomputes and CSV export leave
the FX thread, and the gate editor is split into one editor per gate type. UMAP and the
Analysis window remain held back, as in 0.9.3.

> **Known defect, unfixed: the Analysis window's JavaFX tests hang.** Two test classes —
> `AnalysisWindowFxTest` and `AnalysisPaneFxTest` — wedge the shared JavaFX Application
> Thread non-deterministically, once in a way that cascaded into 172 failures across 33
> unrelated classes. Rather than fix it in this round, both classes are **disabled**, so a
> real defect in the Analysis window is being carried forward rather than resolved. The root
> cause is unidentified. What this leaves unguarded is named in each class's `@Disabled`
> reason; most sharply, the Analysis window's in-memory close/reopen state is now untested,
> so its two persistence lifetimes are no longer independently covered. The window is
> feature-flagged off (`FlowPathPane.ANALYSIS_ENABLED == false`), so nothing a user can reach
> today depends on it — but both classes must be re-enabled and the hang fixed before the
> Analysis window ships.

### Added

- **Drag and drop to reorder gates in the tree.** A gate can now be dragged onto any branch
  row to become a child of that branch, carrying its whole subtree with it, and dropped on
  the empty space below the tree to be promoted back to a top-level gate. Rows that cannot
  take the drop — a gate row (only branches hold children), the branch the gate already
  hangs off, and any branch inside the gate's own subtree — are marked as non-droppable
  while you drag over them and refuse the drop, leaving the tree completely untouched: no
  undo step, no re-count. A completed move is a single undo step, and the gating pass that
  follows re-counts every branch under the gate's new parent; the editor stays open on the
  moved gate itself, rather than going blank or following the tree's selection elsewhere.

### Fixed

- **Undo left filters, masks and statistics behind.** Undo, redo, loading a tree, switching
  images, toggling the annotation filter and editing annotations each ran their own list of
  steps, and the lists had drifted: undo restored the gate tree but not the quality filter's
  mask, the ROI mask or the column statistics, so the restored gates were counted against
  the filter they were undone away from. Every one of them now goes through a single resync
  (ROI mask, quality mask, statistics from the *incoming* tree's filters, legacy conversion,
  gating pass). The statistics are recomputed only when the cells or the combined filter
  mask actually changed, so undoing a threshold nudge does not re-sort every column of a
  large slide on the UI thread.
- **Switching images did not gate the new cells.** Opening another image installed its
  cells but requested no pass, so counts stayed stale until the first edit. The new image
  is now gated as soon as its cells land.
- **The first undo after a gate edit did nothing.** The editor writes into a gate before
  reporting the change, so the undo step captured the already-edited tree. An edit is now
  undone to the tree as it stood before that edit.
- **A long drag took several undos.** The 500 ms coalescing window was measured from the
  first tick, so a slow drag split into several steps. The window now slides with every
  tick, and a quality-filter drag no longer merges into a gate edit made just before it.
- **Clicking into a text field and out again cost you your redo.** The threshold field and
  the branch-name fields reported an edit on every Enter or focus loss, even with nothing
  changed, which recorded an empty undo step and cleared the redo stack; the threshold and
  quadrant fields also rounded a dragged threshold to the digits they display. Only a real
  change is recorded now, and a typed `Infinity` or `NaN` threshold is rejected.
- **Some error dialogs were blank.** A failed CSV export, detection read, save or load whose
  error carried no message showed an empty body (or "null"). The error's type, and its
  cause when there is one, are named instead.
- **Quality-filter and annotation-filter changes could not be undone.** Both are now undo
  steps; a slider drag is one, and Reset is one of its own even straight after a drag.
- **A cell reaching a gate on a channel the image lacks was not flagged.** The walk
  stopped there silently, so nothing in the CSV said why the cell went no deeper. Such a
  cell is now flagged `Unmeasured` (it keeps its ancestors' phenotype and descends no
  further), exactly as for a NaN value.
- **Polygon edges and vertices were half Outside.** Ray-casting put the left and bottom
  edges Inside and the right and top edges (and three of four vertices) Outside, while
  rectangles and ellipses included their whole boundary. Edges and vertices now count
  Inside for all three shapes, including a self-intersecting polygon's crossing segments.
- **A marker present only past the first 100 cells went unresolved.** Measurement keys
  were sampled from the head of the detection list, three times over with different
  depths. One sample now reads the first 100 cells plus a fixed stride across the whole
  slide (at most 1000), and the panel, the index and the compartment scan share it. The
  "literal 0.0" note now counts over that sample, so its numbers are larger on big slides.
- **Cells positioned from their ROI centroid were not reported.** The ingest report now
  counts them. It is a finding when the export carries centroid µm columns and more than
  half the cells lack them; otherwise (including plain QuPath detections with no centroid
  columns at all) a note, worded for the coordinate space actually used.
- **Opening a gate could fold a silent write into your next undo step.** When a gate's
  stored statistic is absent from the export, the editor pins it to one the export carries.
  That write is now reported as its own change and re-gated at once, without an undo step.
- **Quadrant sliders opened with a `[-5, 5]` travel** on a gate whose stored statistic was
  absent from the export, until the next data change. They are now ranged after the axis
  signal is pinned, and re-ranged when only the statistics change (an annotation edit, a
  filter drag).
- **The region Values row hid MIRAGE's z column** on first opening a gate saved on a
  statistic the export lacks. It is now derived from the pinned signal.
- **Controls of a previously shown gate kept writing to it.** Switching gates left the old
  quadrant slider, threshold slider or channel picker wired, and they wrote into their old
  gate or into whichever gate was shown next. A disposed editor now writes to nothing.
- **Text vanished on one of QuPath's themes.** Labels, headers, hints and numeric fields
  used hard-coded colours (white text, dark panels). They now follow QuPath's light and
  dark themes through a stylesheet; the gate bars, compartment badges, branch pills and the
  UMAP button keep their fixed swatches on purpose.
- **A first-load failure left the editor showing the previous image's gate and channels.**
  Once the busy state cleared, the editor re-enabled over an index that stayed null. It is
  now cleared, exactly as for an image with no detections.
- **An annotation-only edit flickered the status bar even with the ROI filter off**, though
  it could not change the detection set or any mask FlowPath keeps. Such an edit is now
  recognised up front and skipped entirely — no detection-list snapshot, no busy-state flip.
- **The tree view's right-click menu stayed usable while a new image loaded.** The toolbar's
  "+ Add Root Gate" button was already disabled during a first load; "Add Root Gate..." and
  "Add child to..." in the context menu now are too.
- **A quality-filter bound could miss a value by one ulp.** QuPath's measurement lists are
  float-backed; a bound typed as an exact-looking number like 0.7 is a double that rounds to
  a different bit pattern than the float a stored "0.7" measurement actually widens to, so an
  inclusive bound could reject a value it was meant to match (or accept one past it).
  Comparisons now happen at float precision, matching the precision the data actually has.
- **An undone, redone or just-loaded gate tree read 0/0% until the next background pass
  landed** — up to several seconds on a large slide, where it used to be one UI-thread frame.
  The outgoing tree's counts are now carried onto the incoming one first, wherever their
  structures still pair — same channels, not merely the same shape, so loading an unrelated
  tree that happens to have the same number of roots and branches shows zeros rather than
  another gate's counts — so what shows meanwhile is stale-but-plausible rather than blank.
- **A branch rename committed right after converting a region gate's shape (rectangle to
  ellipse, say) could be judged against the wrong baseline.** The branch-name row is now
  rebuilt for the replacement gate at once, rather than waiting for the editor's next
  scheduled rebuild.
- **Drawing a rectangle or ellipse over a gate of another type left an extra, dead undo
  step** (the first Ctrl+Z did nothing; a polygon left a stray empty-polygon step instead).
  The replacement is now recorded as one step that the change report that follows folds
  into, rather than a second step on top of it.
- **A rectangle or ellipse gate degenerate on only one axis could still be remapped** when
  its compartment or statistic changed, handing it a spurious extent on that axis under a
  non-linear percentile map. Both axes are now checked for degeneracy before remapping,
  matching the check the scatter plot itself already used.
- **A failed CSV export could leave the Export button and Ctrl+E disabled until QuPath was
  restarted**, if the failure was an `Error` rather than an `Exception` (an
  `OutOfMemoryError` walking and serializing a million-cell population is the plausible
  case) — it escaped the background job uncaught, so the panel never learned the export had
  finished. Both are now caught, matching how detection reads already handled the pair.
- **Restructuring the gate tree while a pass was running could show one gate's counts on
  another gate's branches.** A gating pass walks a copy of the tree and carries the counts
  back onto the live one afterwards. Editing the tree meanwhile — dragging a gate onto
  another branch above all, since that is a slow gesture performed while passes run — left
  the two structures out of step, and the counts were carried back before anything checked
  that they still matched, so the tree view showed plausible but wrong per-branch numbers
  until the next pass landed. The check now happens first and nothing is written unless it
  passes.
- **Changing a filter while detections were being re-read could still freeze QuPath.**
  Neither the annotation-filter checkbox nor the quality-filter panel is disabled while
  FlowPath re-reads detections, so editing a cell in QuPath and then toggling the filter or
  nudging a slider left the read landing against filters it no longer described — and the
  landing recomputed the statistics on the UI thread after all, re-sorting every marker
  column: exactly the freeze that moving the read into the background was meant to end.
  That recompute now happens in the background too, like every other one.
- Hardened the error-dialog message builder against a pathological multi-level exception
  cause cycle (A caused by B caused by C caused by A) that would have recursed until the
  stack overflowed; it now walks iteratively and stops on a cycle instead.

### Changed

- **Legacy z-score gate trees are converted as soon as they meet cells.** Conversion used
  to happen only when the editor opened a gate, so a gate never opened — or a tree exported
  straight away — was gated on z-values as if raw. On image open, load and undo every gate
  is now converted into its column's own units, and a notification names any that could not
  be: a column with no spread keeps its numbers; a channel this image lacks keeps the gate
  in z-score units (flag kept) until the tree meets an image that has it. A tree saved
  before any image was opened keeps the flag too. Gates compare only against columns the
  export carries.
- **Detections are read off the FX thread**, and read again automatically (debounced, gate
  tree kept, re-gated) when detections are added, removed or re-measured while FlowPath is
  open. An annotation edit under the ROI filter re-derives masks and statistics in the
  background; an image switch drops the old cells at once, so a slow read can never land
  over a newer image.
- **Toggling the annotation filter, undoing across a filter change and loading a tree no
  longer freeze QuPath.** Each of those changes the set of cells the statistics describe, so
  every marker column is sorted again — seconds on a million-cell slide, and it used to run
  on the UI thread. They now recompute on the same background thread as the detection read
  and land when they are done; the tree and the panel show the change immediately, and the
  status bar reads "Recomputing statistics…" until the numbers beside it are the new ones.
  Undo and redo are never queued or refused meanwhile: each Ctrl+Z is taken at once and
  simply supersedes the recompute still in flight, so only the newest result is ever applied.
  The gate editor and CSV export wait for it — an export started mid-recompute would have
  written a file gating one tree against another tree's statistics. A recompute that fails
  says so and leaves the previous statistics in place.
- **CSV export runs in the background** from a snapshot of the tree, so editing can continue
  while it writes. The export button is disabled, and Ctrl+E ignored, until it finishes.
- **`_sign` is blank, not `-`, when no gate on the column could judge the cell** (for
  example a quadrant X column when only the Y value is missing). It is computed by the
  same predicate the gating uses.
- A saved gate without `thresholdIsZScore` now loads as raw; FlowPath has always written
  the field, so only hand-edited files are affected. Raw gates no longer write it.
- The gate editor is one editor per gate type, with its axis arithmetic in a toolkit-free
  `AxisMath`; region shapes clear and remap themselves. No behaviour change beyond the
  fixes above.

### Removed

- **The `_zscore` column of `gate_pheno.csv`.** It reported the retired computed z-score,
  not a value any gate compared against. `_raw` and `_sign` remain.
- Dead code: `onAddToPositive`/`onAddToNegative`, the deprecated
  `QualityFilter.passes(double...)` overload and its unused legacy getters,
  `Statistic.isStandardised()`.

## [0.9.3] - 16/09/2026

Gating correctness and quality-filter fixes, found by a UI bug report and an edge-case
audit of classification, CSV export and gate-tree JSON. 57 edge-case tests added
(`ClassificationEdgeCaseTest`, `ExportEdgeCaseTest`, `SerializerEdgeCaseTest`), plus
`MorphologyDiscoveryTest` and `QuadrantSliderSpanTest`. UMAP and the Analysis window
remain held back, as in 0.9.2.

### Fixed

- **A saved quality filter reloaded with limits it never had.** The legacy
  `minArea`/`maxArea`/... keys were written from getters that report an open bound as
  0, 1.0 or `Double.MAX_VALUE`, and read back through setters that store those as real
  limits; the `"ranges"` object omitted open fields, so nothing corrected them. An
  unconstrained filter came back as five closed ranges — silently excluding every cell
  with a negative total intensity — and save/load/save was not stable. Legacy keys are
  now written only for bounds that are set, and a file carrying `"ranges"` is read from
  it alone. Files without `"ranges"` load exactly as before.
- **A cleared rectangle gate selected cells.** "Clear Shape" writes (0,0,0,0), and
  `RectangleGate.contains` had no zero-extent guard, so a cell at exactly (0,0) was
  Inside. In z-score mode a zero-spread column standardises every cell to 0.0, so the
  cleared gate selected the whole population. A rectangle with no extent now encloses
  nothing, matching `EllipseGate`'s zero-radius guard.
- **One infinite value turned a whole column negative.** `MarkerStats` excluded NaN but
  not ±Infinity, so a single infinite cell made the mean infinite and the standard
  deviation NaN; every finite z-score became NaN and fell into the negative branch.
  Statistics are now computed over finite values only; an infinite cell still
  classifies by its sign.
- **A marker the image does not name appeared as a quality-filter row.** Morphology
  discovery treated "not a marker of this index" as "a shape measurement", so when the
  image's channel list won the panel, an extra quantified marker (`wrongPANCK` in the
  sample `cells.geojson`) became a filterable morphology field. One rule,
  `MorphologyField.isMorphologyName`, now decides for both marker discovery and the
  quality filter, so a column is one or the other. It also recognises columns carrying a
  spatial unit (µm, px) and QuPath's own shape names (circularity, caliper, Feret, ...).
- **Quality Filter rows showed sliders but no names.** Names were hard-coded white on
  the pane's light background and squeezed beside two sliders. Each name now has its own
  row, uses the theme's text colour, and carries a tooltip with the exported key and the
  observed range.
- **Quadrant threshold sliders moved too fast.** Their travel was the raw data minimum
  to maximum, outliers included, while the scatter plot shows the clip-percentile
  window, so the visible population fit in a few pixels of slider. Slider travel and
  scatter axes now come from one helper, widened only to contain the current threshold;
  the sliders re-range when clip percentiles change, fill the row, and gain a text field
  for exact entry.
- **A carriage return in a name split a CSV row.** `CellTable.escape` quoted `,`, `"`
  and `\n` but not `\r`, which pandas and Python's `csv` treat as a row end.
- **Malformed numbers escaped `FlowPathSerializer.load` as `NumberFormatException`**
  instead of the documented `IOException`.

## [0.9.2] - 02/09/2026

The Analysis window's overhaul: the population table, its exports and its four plots.
Fifteen implementation tasks, reviewed one at a time against the diff behind them.

> **What this release actually exposes: hierarchical gating, and nothing else.**
> Both the UMAP half and the Analysis window are complete in the source and are
> **held back** from this release behind `FlowPathPane.UMAP_ENABLED` and
> `FlowPathPane.ANALYSIS_ENABLED`. Their toolbar buttons are disabled, carry no
> action handler, and are labelled *(coming soon)*. Everything described below is
> therefore a record of what changed in the codebase, not of what a user of 0.9.2
> can reach. `UmapFeatureFlagTest` and `AnalysisFeatureFlagTest` pin the shipped
> values of both flags, so neither can be flipped by accident.

### Fixed

- **The chosen denominator reset on the next gate nudge.** `Branch` is deliberately
  identity-compared — `BranchTally` relies on that, because two branches can share a
  name — but `FlowPathPane` deep-copies the whole gate tree on every push
  (`GateNode.deepCopy()` mints fresh `Branch` objects each time), so a denominator
  remembered as a `Branch` pointer was comparing against branches that no longer
  existed the moment a threshold moved. **No existing test caught it, because every
  test that exercised the Analysis window performed a single `accept()`** — the bug
  only shows up on a *second* pass. The picker now stores a `DenominatorRef`
  (root index + gating path) and re-resolves it against the live tree on every
  refresh, the same value-not-identity fix `PopulationRef` already used for plot
  selection.
- **The Marker Positivity plot's "Ungated" segment was nearly invisible.** It drew at
  `rgb(80,80,90)` on a `rgb(30,30,30)` background — a contrast ratio low enough that
  the one segment the plot exists to make visible (a marker nobody gated on, distinct
  from a marker that came back negative) was the least visible thing on it. The new
  theme-aware `PlotTheme` chooses every colour by WCAG 1.4.11 contrast (>= 3:1
  against its background) rather than by eye, in both a light and a dark palette.
- **The denominator picker could not tell two same-channel roots apart.** Because
  `GateNode` names a branch from its channel alone, two un-renamed root gates on one
  channel produced identically-labelled denominator choices with no way to pick the
  right one. `DenominatorRef` carries the root index the same way `PopulationRef`
  already does, and the picker appends `(root N)` once more than one root is on
  offer.
- **An empty population table gave no reason, in the two cases a valid gating pass
  can still show nothing.** `AnalysisState.hasData()` is true the moment a pass has
  been accepted with an enabled root gate — it says nothing about whether the
  *current* scope or filter leaves any rows to show. A scope with zero populations
  (e.g. *Per annotation* on a region nothing landed in) or a filter matching nothing
  both used to leave a bare grid; each now explains itself in place ("No populations
  at this scope." / "No populations match \"…\".") rather than looking identical to
  a window that failed to load.

### Added

- **Log-scale and percentile-clip axis toggles, per plot, independently
  combinable.** Each of the four plot tabs owns its own `ScaleOptions`, so turning
  on "Log scale" for Composition has nowhere to leak into Marker Positivity, and a
  user can run log and clip together or separately on the same plot. A clipped bar
  is never silently truncated: it draws to the axis ceiling and is marked with its
  own axis-break glyph, and a "— top values clipped" note appears beside the
  toggle whenever the last paint actually cut a bar off (not merely whenever
  clipping is turned on — a percentile that lands on zero or on the data's own
  maximum changes nothing, and the note does not light up for it).
- **Plot export**, from a new "Export ▾" menu whose plot items act on whichever
  plot tab is currently selected: **Copy plot to clipboard**, **Plot as image…**
  (one save dialog offering both SVG and PNG at 2x, rather than two separate menu
  items — the chosen file's own extension picks the writer), **Plot data as
  CSV…** (the same numbers the plot itself drew, never a second reduction of the
  underlying rows), and **Population table as CSV…** (unchanged in shape, still
  every scope and region rather than only the rows on screen). The SVG export
  runs the exact same `draw(PlotSurface, PlotTheme)` routine the screen uses,
  against an `SvgSurface` backend instead of a `CanvasSurface`, so what gets
  saved is provably what was on screen.
- **Clean percentages.** `% Parent (clean)` and `% Total (clean)` sit beside the
  existing `% Parent`/`% Total` columns, both in the table and in
  `population_stats.csv`: the clean count over the clean parent/total, so
  quality-filtered, outlier-clipped and out-of-annotation cells drop out of both
  the numerator and the denominator, not only the numerator.
- **Region area as its own table column**, `Area (mm²)`, alongside `Density` — it
  was already in the CSV; it is now also in the table, so the two never need to
  be cross-referenced by hand.
- **Hover values and click-to-select on every plot.** Hovering a bar shows a
  tooltip of its underlying numbers. On Composition, By Region and By Scope,
  clicking one also selects that population in the table and its gate in the
  tree; Marker Positivity's bars are pooled across every gate node that used a
  marker, so no single population exists to select there, and a click selects
  nothing.
- **Two-way selection between the population table and the gate tree.** Selecting
  a table row highlights its gate; selecting a gate in the tree selects the
  matching table row. Both directions resolve through `GateTree.findBranch` /
  `GateTree.locate`, exact inverses sharing one enabled-roots-only rule, so
  neither direction can find a branch the other would disagree exists.
- **The table is sortable, filterable and copyable.** Every numeric column —
  including the percentage, density and area columns, which used to be
  `String`-typed and explicitly unsortable — now sorts as a number, with blanks
  (NaN) always last regardless of sort direction, rather than the lexicographic
  ordering a `String` column gave, which put "100.0" above "20.0"; a filter box
  narrows the table by population path or region name; and a row selection
  copies as tab-separated text with a header row (right-click, or `Ctrl+C`).
- **The summary line and the image's name.** A line above the table reads e.g.
  "`slide_04.ome.tiff · 214,332 cells · 3 regions · 189,201 in scope · 31
  populations`", switching to "`N of M populations`" while the filter narrows
  what is shown; the window's own title bar carries the image name too, so a
  screenshot or an alt-tab identifies which slide it is.
- **The window remembers itself.** Within one running session, closing and
  reopening the Analysis window keeps its tab, scope, filter text and table
  selection exactly as left — the pane instance survives a close, only its
  `Stage` is torn down. Across a restart, `AnalysisWindowPrefs` persists window
  geometry, the active tab, the chosen scope, and **all four plot tabs' scale
  settings** — not only whichever tab happened to be on screen when the window
  closed, since a user can have log scale on for two different tabs at once and
  partial restore would silently discard the other one.

### Changed

- **Every Analysis plot draws through one routine, two backends.** `draw(PlotSurface,
  PlotTheme)` is now the single implementation of each canvas's rendering; both
  `repaint()` (to the live `Canvas`) and `toSvg()` (to `SvgSurface`, for export) are
  `final` and funnel through one private `render`, differing only in which surface
  they hand to `draw`. This is what makes SVG export a second backend rather than a
  second renderer walking the population data again — and it is also what makes the
  plots unit-testable without a JavaFX toolkit for the first time, since `draw`
  itself never touches a `Canvas`.
- **One `PlotTheme` replaces every hardcoded colour in the Analysis canvases.** The
  gating and UMAP canvases are deliberately unchanged this release — this only
  touches the four Analysis plots.
- **The Root and Population pickers moved onto the plot tab they actually drive.**
  Root sits under Composition, Population under both By Region and By Scope, rather
  than in a shared control row above the table where changing either one visibly did
  nothing to whatever tab happened to be open.

## [0.9.1] - 02/09/2026

### Added — Analysis window

- **A floating Analysis window** reports what the tissue is made of, live, while you gate.
  Population table plus composition, per-region, scope-comparison and marker-positivity
  plots, all hand-drawn to match the existing canvases. Opened with the new **Analysis**
  button beside **Open UMAP**; it refuses to open before the first gating pass finishes or
  before any gate exists, the same way **Open UMAP** already refuses when there is nothing
  to show yet.
- **Three nested scopes** — per region, union of regions, whole slide — stated rather than
  inferred, since `RegionMask` already assigns each cell its region.
- **Any branch can be the denominator**, so a population can be read as a share of the
  immune compartment or of the tumour rather than only of its parent and the slide.
- **Raw and clean counts side by side.** The clean count drops outlier-clipped,
  quality-filtered and unmeasured cells, so the data-quality cost is visible rather than a
  choice buried in an exporter.
- **Counts are tallied inside the gating walk** (`model/BranchTally`), not recomputed. The
  one-gate-predicate invariant forbids a second implementation of "which branch is this cell
  in", and counting outside the walk would have been one. The live-preview gating pass now
  carries the same per-region breakdown the Analysis window reads, rather than a second
  walk computing it separately.
- **`io/PopulationStatsExporter`** writes the population table to CSV, with `writeHeader`
  and `writeRows` split apart so a batch-gating run can write one header followed by many
  images' rows into a single combined file. `root_index` is exported as its own column:
  two un-renamed root gates on the same channel emit byte-identical `path` values, so it is
  the only column that can tell such rows apart.
- **Region area, in mm², from the annotation's own ROI.** `RegionMask` now exposes
  `regionRois()` alongside `regionNames()`, so the gating pane can convert `ROI.getArea()`
  (pixels²) through the image's pixel calibration without a second annotation scan. An
  uncalibrated image, or the implicit "whole image minus exclusions" region (no single ROI
  describes that shape), reports the area as unknown (`NaN`) rather than a number in the
  wrong unit or a fabricated one.

### Fixed — correctness

- **A cell with no measurement is no longer reported as negative.** MIRAGE's
  `export_geojson.py` omits a NaN measurement from the GeoJSON entirely, so a marker
  absent on some cells is ordinary input and the column reads NaN there. Because
  `NaN >= threshold` is false and nothing upstream checked, such a cell was assigned the
  negative branch and *counted* in it — a marker missing on 5% of cells inflated that
  marker's negative population by 5%. The same export already wrote blanks in that cell's
  `_raw`, `_zscore` and `_sign` columns, so one CSV row asserted both "never measured" and
  "measured, and negative", against a `computeSign` javadoc claiming the two columns
  cannot drift apart. A gate now gives no opinion on a cell it has no data for: the cell
  keeps its ancestors' phenotype, is counted in neither branch, does not descend into the
  subtree, and is flagged in the new **`Unmeasured`** CSV column and
  `AssignmentResult.getUnmeasured()`. `ResolvedGate` distinguishes `UNMEASURED` from
  `CLIPPED`, which were the same `-1` — a clipped cell has a real value and still gets a
  branch.
- **Multi-root branch counts no longer depend on the order roots were added.**
  `excluded[]` answered two questions at once: "grey this cell out?" (a union over the
  whole tree) and "should this branch count it?" (which must be scoped to the root doing
  the counting). One array serving both meant a cell clipped by root A stopped counting in
  root B, while a cell clipped by root B had already been counted by root A. Reordering
  two roots moved a gate's split from 4/2 to 6/4 on identical data. Each root is now
  walked from the same base exclusion (quality filter + ROI); the union is restored
  afterwards, so QuPath's visual filtering is unchanged.
- **`computeAncestorMask` no longer contradicts the engine on a disabled ancestor.** It
  treated a disabled gate as transparent and reported all cells reaching the target, while
  `walkNode` returns before descending, so the engine classified none of them — the
  child's plot drew a full population against a phenotype column that never mentioned it.
  A disabled gate is a hard stop for its subtree on both paths. The test that pinned the
  old mask behaviour asserted an intent the engine cannot deliver (a disabled gate has no
  chosen branch to descend *into*) and now pins the agreement instead.
- **`combineMasks` rejects masks of different lengths** instead of silently truncating one
  way and throwing an unexplained `ArrayIndexOutOfBoundsException` the other. Every mask is
  positional against `CellIndex.getObjects()`, so a length mismatch means two different
  populations.

### Added — annotation regions

- **The annotation filter uses the selected annotations**, falling back to all of them when
  nothing is selected, so "gate on just this region" is a click rather than a deletion.
- **Cells are attributed to the region they came from,** not merely in-or-out. The new
  `model/RegionMask` replaces the bare `boolean[]` — a type that could not express *which*
  region, making core-vs-margin comparison unrepresentable rather than just unimplemented.
  A **`region`** column is written to `gate_pheno.csv` when the filter is on, and the
  status bar reports the regions in use.
- **Exclusion regions.** An annotation carrying a QuPath *ignored* classification
  (`Ignore*`, or any class whose name ends in `*`, per `PathClassTools.isIgnoredClass`) is
  subtracted — for necrosis, tissue folds and artefacts. Exclusions with no include region
  alongside them mean "the whole image, minus these". Overlapping include regions resolve
  to the first match, so per-region counts partition the population.
- **Line and point annotations are skipped and counted** rather than contributing an
  all-false mask. Previously, turning the filter on when the only annotation was a stray
  point excluded *every* cell, with empty histograms as the sole symptom.

### Changed — performance

- **The annotation mask is ~36x faster.** `ROI.contains` on a polygon is a full
  point-in-polygon test through JTS, and the mask recomputes on every annotation edit, so
  it sits in the interactive path. Each annotation's envelope is now hoisted into
  primitive arrays and rejects distant cells in four comparisons. Measured on 200k cells
  against four 200-vertex polygons: **286ms to 8ms**, with the envelopes rejecting 97% of
  cells. An envelope is a superset of its geometry, so the mask is bit-identical.

### Fixed — docs

- `CLAUDE.md` described the annotation filter as "UUID-based". No UUID appears anywhere in
  `src/main`; the filter unions annotations by geometry.

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

- **The quality filter offers what the export carries, and nothing else.** It was five
  hard-coded pairs of sliders, wrong in both directions: a whole-cell-only mask carries no
  solidity but the slider was drawn anyway over a column of NaN, while a MIRAGE export's
  `Major Axis Length µm` and `Minor Axis Length µm` sat in the file unread — no way to
  filter on elongation, and nothing to say the columns were there. `CellIndex.morphology()`
  now discovers them, ranges are keyed by a unit-free slug so a constraint on a field
  FlowPath has never heard of is expressible, and each slider spans its own column's
  observed range rather than a guessed constant. **Both CSVs emit the same discovered
  block**, so a column in the file reaches the output instead of being read into memory and
  dropped; a column the export lacks is absent rather than blank.
- **The compartment vocabulary is open.** `Nucleus` / `Cytoplasm` / `Cell` are no longer
  the only compartments FlowPath can see — a pipeline adding a fourth gets it offered
  rather than turned into a phantom marker named after the whole measurement key. The
  parse anchor is kept safe by requiring evidence: an unknown token counts as a compartment
  only when **two or more distinct markers use it**. That is what separates a real
  compartment, which a pipeline emits for every marker in the panel, from QuPath's own
  `ROI: 0.50 µm per pixel: CD3` shape, whose marker slot is a constant and so never reaches
  two.

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
