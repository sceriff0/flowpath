# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

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
