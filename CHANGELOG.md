# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

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
