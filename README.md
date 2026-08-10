# FlowPath

[![QuPath](https://img.shields.io/badge/QuPath-%E2%89%A50.7.0-blue.svg)](https://qupath.github.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://jdk.java.net/25/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-flowpath.readthedocs.io-success.svg)](https://flowpath.readthedocs.io/)

FlowJo-style **single-cell analysis** for [QuPath](https://qupath.github.io/) on
multiplexed imaging data (CODEX, MIBI, mIF).

Build hierarchical marker gates (e.g. `CD45+ → CD3+ → CD8+ = "T cytotoxic"`), drag
thresholds, draw 2D regions, and watch cells recolour in real time — then open a
**UMAP of those same phenotypes** without leaving the extension.

Designed to work with the [MIRAGE](https://mirage-pipeline.readthedocs.io/)
pipeline, and with any QuPath detections carrying per-marker measurements.

## Install

In QuPath, add the FlowPath catalog and install **FlowPath**:

```
https://raw.githubusercontent.com/sceriff0/qupath-extension-flowpath/main/catalog.json
```

(Extensions → Manage extensions → Manage extension catalogs → Add.) Launch with
**Extensions → FlowPath** (`Ctrl+G`); the UMAP opens from there with **Open UMAP**
(`Ctrl+U`). Full install options — JAR drop, build from source — are in the
[docs](https://flowpath.readthedocs.io/installation/).

> [!IMPORTANT]
> **Upgrading from v1?** FlowPath used to ship as two extensions,
> *FlowPath - GatingTree* and *FlowPath - qUMAP*. Remove both under
> **Extensions → Manage extensions** before installing FlowPath — QuPath keys
> extensions by name and will not treat this as an upgrade of either. Saved gate
> trees (`.json`) load unchanged.

## How the two halves fit together

Gating is the way in. The UMAP opens from it and inherits the phenotyping:

- the **same cell index** — no rebuild, so it opens instantly on a slide already
  loaded;
- **point colours** from the gate tree's own branch colours, so the tree, the
  tissue overlay and the embedding cannot disagree;
- a **legend** of your populations with real counts, where clicking one hides it;
- a **feature selection** pre-ticked to the markers you actually gated on, in the
  compartment and statistic you gated them in.

Editing a gate does not rebuild the index, so an open UMAP **recolours live** as
you gate rather than needing a recompute.

## Build from source

Requires **JDK 25**.

```bash
git clone https://github.com/sceriff0/qupath-extension-flowpath.git
cd qupath-extension-flowpath
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
> https://github.com/sceriff0/qupath-extension-flowpath

See the [citation page](https://flowpath.readthedocs.io/citation/) for QuPath,
UMAP/SMILE and MIRAGE references.

## License

MIT. See [LICENSE](LICENSE).
