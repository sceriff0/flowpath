# FlowPath

[![QuPath](https://img.shields.io/badge/QuPath-%E2%89%A50.7.0-blue.svg)](https://qupath.github.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://jdk.java.net/25/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-flowpath.readthedocs.io-success.svg)](https://flowpath.readthedocs.io/)

FlowJo-style **single-cell analysis** for [QuPath](https://qupath.github.io/) on
multiplexed imaging data (CODEX, MIBI, mIF).

Build hierarchical marker gates (e.g. `CD45+ → CD3+ → CD8+ = "T cytotoxic"`), drag
thresholds, draw 2D regions, and watch cells recolour in real time — then read the
per-population counts and percentages in the **Analysis** window and export them
as CSV.

**In this release:** hierarchical gating with live preview, the Analysis window
(population statistics and plots), and per-cell / per-population CSV export.
**Coming in a future release:** a **UMAP of those same phenotypes**, opened
without leaving the extension.

Designed to work with the [MIRAGE](https://mirage-pipeline.readthedocs.io/)
pipeline, and with any QuPath detections carrying per-marker measurements.

## Install

In QuPath, add the FlowPath catalog and install **FlowPath**:

```
https://raw.githubusercontent.com/sceriff0/flowpath/main/catalog.json
```

(Extensions → Manage extensions → Manage extension catalogs → Add.) Launch with
**Extensions → FlowPath** (`Ctrl+G`) — the one menu item everything opens from.
Full install options — JAR drop, build from source — are in the
[docs](https://flowpath.readthedocs.io/installation/).

> [!IMPORTANT]
> **Upgrading from an earlier FlowPath?** Remove the old one first, under
> **Extensions → Manage extensions**. Versioning restarted at **0.9.0** — it sorts below
> the earlier 1.x / 2.x line, so QuPath will not offer it as an upgrade — and anything
> older than that shipped under two different names, *FlowPath - GatingTree* and
> *FlowPath - qUMAP*, which QuPath keys separately again. Saved gate trees (`.json`) load
> unchanged in every case.

## How the two halves fit together

> [!NOTE]
> **Coming in a future release.** UMAP exploration is not available in this
> version — the **Open UMAP** button is disabled and labelled *UMAP (coming
> soon)*, and `Ctrl+U` does nothing. The section below describes how it will
> work once it ships.

Gating is the way in. The UMAP will open from it and inherit the phenotyping:

- the **same cell index** — no rebuild, so it opens instantly on a slide already
  loaded;
- **point colours** from the gate tree's own branch colours, so the tree, the
  tissue overlay and the embedding cannot disagree;
- a **legend** of your populations with real counts, where clicking one hides it;
- a **feature selection** pre-ticked to the markers you actually gated on, in the
  compartment and statistic you gated them in — once you have gated at least two
  of them. Below that the picker opens with everything ticked, because an
  embedding needs two markers and a selection of one could not be run.

Editing a gate does not rebuild the index, so an open UMAP will **recolour live**
as you gate rather than needing a recompute.

## Build from source

Requires **JDK 25**.

```bash
git clone https://github.com/sceriff0/flowpath.git
cd flowpath
./gradlew build shadowJar
```

`build/libs/FlowPath-<version>-all.jar` → drag onto QuPath. `build` alone produces
only the thin JAR; `shadowJar` is what bundles [SMILE](https://haifengl.github.io/),
which the UMAP engine needs at runtime.

## 📖 Documentation

Features, the gating workflow, output formats, and troubleshooting are all at
**<https://flowpath.readthedocs.io/>**.

## Citation

> FlowPath: Interactive tree-based cell phenotyping and UMAP exploration for
> QuPath. (2026).
> https://github.com/sceriff0/flowpath

See the [citation page](https://flowpath.readthedocs.io/citation/) for QuPath,
UMAP/SMILE and MIRAGE references.

## License

MIT. See [LICENSE](LICENSE).
