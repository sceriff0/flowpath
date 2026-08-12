# Usage

The whole workflow is three steps: **get cells into QuPath**, **gate them into
phenotypes**, then **explore those phenotypes in a UMAP**. This page walks through
that end to end, then lists the options.

It assumes you've [installed FlowPath](installation.md) and have a multiplexed
OME-TIFF open in QuPath 0.7.0 — for example one produced by
[MIRAGE](https://mirage-pipeline.readthedocs.io/).

```mermaid
flowchart LR
    A[OME-TIFF] --> Q[Open in QuPath]
    Q --> I{Get cells in}
    I -->|GeoJSON| G1[Import cells.geojson]
    I -->|Mask| AM[AnnoMask]
    G1 --> GT[FlowPath · Gating]
    AM --> GT
    GT -->|Open UMAP| UM[FlowPath · UMAP]
    GT --> CSV1[gate_pheno.csv]
    UM --> CSV2[umap_coordinates.csv]
```

## The data model

Everything in FlowPath operates on one shared object: **QuPath detections that
carry per-marker measurements**. Both views read those measurements by key, and
they understand two conventions MIRAGE and AnnoMask write:

| Convention | Example key | Meaning |
|---|---|---|
| **Bare marker** | `CD45`, `DAPI` | Whole-cell mean intensity for that channel (the AnnoMask convention). |
| **Per-compartment** | `CD3: Nucleus: Mean` | `"<MARKER>: <Compartment>: <Statistic>"`, for Nucleus / Cytoplasm / Cell and Mean / Median / Sum. |

Because both sides speak the same key language, MIRAGE's output is plug-and-play:
no renaming, no remapping. If a dataset has **no** per-compartment keys (older
exports, or whole-cell-only masks), FlowPath falls back to whole-cell / Mean
automatically — nothing breaks.

!!! note "One index, two views"
    Gating and the UMAP share a single in-memory cell index. That is why the UMAP
    can open instantly on a slide the gating window has already loaded, and why
    editing a gate recolours the embedding without recomputing it.

## Step 1 — Get cells into QuPath

Open your pyramidal OME-TIFF. If it came from MIRAGE, DAPI is on channel 0 and the
rest are your markers. Then pick **one** of two equivalent on-ramps:

=== "On-ramp A — import GeoJSON"

    `File → Object data → Import objects` and choose MIRAGE's `cells.geojson`.
    Detections arrive with all marker measurements already attached — nothing
    else to do. Use this when you ran MIRAGE through its GeoJSON export.

=== "On-ramp B — import a mask with AnnoMask"

    Install [AnnoMask](https://github.com/sceriff0/qupath-extension-annomask)
    (a separate extension), then `Extensions → FlowPath - AnnoMask`
    (++ctrl+shift+m++). Point it at a labelled mask (`*_cell_mask.tif`), enable
    **intensity sampling**, and run. AnnoMask creates one detection per label and
    samples per-channel intensity using the **same bincount pass MIRAGE uses** —
    so the measurements are identical. Use this when all you have on disk is a
    labelled mask (MIRAGE, Cellpose, StarDist, or custom).

Either way you now have **detections carrying per-marker measurements**, ready to
gate.

## Step 2 — Gate and phenotype

Open `Extensions → FlowPath` (++ctrl+g++). Build a hierarchy of marker gates —
e.g. `CD45+ → CD3+ → CD8+ = "T cytotoxic"` — and cells recolour live as you move
thresholds.

1. Set **quality filters** to drop segmentation artefacts (min/max for area,
   eccentricity, solidity, perimeter, total intensity).
2. Add a **root gate** and pick a type — threshold (1D), quadrant (2D), polygon,
   rectangle, or ellipse.
3. For a threshold gate: pick a channel and drag the line on the histogram. For a
   2D gate: pick X/Y channels and draw the region on the scatter plot.
4. Add **child gates** to branches to sub-gate, and **name** leaf nodes
   ("T cytotoxic", "Tumor", "Stroma").
5. Export when happy — or press **Open UMAP** to keep exploring.

<figure class="screenshot" markdown>
![Gate hierarchy with recoloured cells](assets/screenshots/placeholder.png){ .glightbox }
<figcaption>A multi-level gate tree; cells in the viewer recolour by phenotype in real time. <em>(placeholder)</em></figcaption>
</figure>

Your cells now carry **PathClasses** for the phenotypes you defined, and you can
export `flowpath.json` (the full gate hierarchy, reloadable and shareable) and
`gate_pheno.csv` (one row per cell, phenotype + per-marker ± status).

## Step 3 — Explore in the UMAP

Press **Open UMAP** in the gating toolbar (++ctrl+u++). The window opens already
knowing your phenotyping — there is nothing to reconnect or re-select.

**What it inherits from your gates:**

- the same cells, already filtered by your quality and annotation settings;
- **point colours** taken straight from the gate tree's branch colours;
- a **legend** listing your populations with real counts and shares;
- a **feature selection** pre-ticked to the markers you actually gated on, in the
  compartment and statistic you gated them in — not all forty channels on the
  slide. This needs **at least two gated markers**: a UMAP cannot be computed over
  one, so a single first gate leaves the picker at everything-ticked rather than
  pre-selecting a set that could not be run. The empty state says which of the two
  you are looking at.

Then:

1. Check the **Cells** panel — it tells you how many cells, how many phenotypes,
   and how many markers are selected. Adjust with **Features…** if you want.
2. Pick a quality preset under **Embedding** (Fast / Balanced / Best) and press
   **Run UMAP**. Progress appears inline, under the button, with a Cancel beside
   it; the gating window stays usable throughout. **Run UMAP is greyed out** until
   at least two markers are ticked, and while a feature change is still being
   applied. The inputs lock for the duration of a run, so a preset or a marker
   cannot be changed out from under the computation in flight.
3. Read the status line when it finishes. A run that had to degrade something says
   so there — see [what the warnings mean](#what-a-run-reports-about-itself) below.
4. Under **Colour**, switch between **Phenotype** (the default) and **Marker** to
   see one marker's expression across the embedding.
5. In the legend, **click a population to hide it** — the fastest way to dig a
   rare population out from under a dominant one — or **hover to highlight** it
   in place.
6. Under **Select**, **draw a polygon** around a cluster, name it, and press
   **Tag Selection** to store it as a derived PathClass.
7. **Export Data** → `umap_coordinates.csv`.

<figure class="screenshot" markdown>
![UMAP embedding coloured by phenotype](assets/screenshots/placeholder.png){ .glightbox }
<figcaption>A UMAP embedding coloured by the phenotypes assigned in the gating tree. <em>(placeholder)</em></figcaption>
</figure>

!!! tip "Keep both windows open"
    Leave the UMAP open while you carry on gating. Every gate edit re-pushes the
    phenotyping and the embedding recolours immediately — no recompute. Coherent
    populations landing as distinct islands is a fast visual check on your gates,
    and watching a threshold split an island in real time is the quickest way to
    find the right one.

## What you end up with

| File | From | Contents |
|---|---|---|
| `flowpath.json` | Gating | Gate hierarchy, thresholds, colours, QC settings |
| `gate_pheno.csv` | Gating | Per-cell phenotype + per-marker ± status |
| `umap_coordinates.csv` | UMAP | Per-cell UMAP X/Y + phenotype |
| PathClasses | both | Named populations stored on the QuPath objects |

```csv title="gate_pheno.csv"
cell_id,phenotype,CD45,CD3,CD8,PANCK
0,T cytotoxic,+,+,+,-
1,Tumor,-,-,-,+
```

```csv title="umap_coordinates.csv"
UMAP_X,UMAP_Y,Phenotype
-3.241519,1.872034,CD4+
2.109384,-0.543218,CD8+
```

## Options reference

### Gating

- **Gate types** — threshold (1D), quadrant (2D dual-threshold), polygon,
  rectangle, ellipse.
- **Per-gate compartment & statistic** — choose Nucleus / Cytoplasm / Cell and
  Mean / Median / Sum per gate (when per-compartment keys exist).
- **Raw / Z-score toggle** — switch value modes per gate (z-score uses MIRAGE's
  own z-scores).
- **Quality filters** — pre-gating QC with min + max for area, eccentricity,
  solidity, total intensity, perimeter.
- **Outlier exclusion** — per-gate percentile clipping, with the scatter axis
  zooming to the clipped range.
- **Undo / Redo** — snapshot-based (++ctrl+z++ / ++ctrl+shift+z++); drag-and-drop
  to reorder gates between branches.

### UMAP

- **Quality presets** — Fast / Balanced / Best, or Custom to expose neighbours
  (`k`), epochs, subsampling mode and cell cap. Computed via the
  [SMILE](https://haifengl.github.io/) library.
- **Feature selection** — pre-seeded from your gates once you have gated **two or
  more** markers; every other marker on the slide stays available in the picker,
  just unticked. Unticking a marker **excludes it from the embedding** — the
  computation reads only the ticked columns — so it is the lever for trimming a
  40-plex down to the markers a question is actually about. **Run UMAP requires at
  least two ticked markers** and is disabled below that, and again for the moment
  it takes to apply a change.
- **Subsampling** — Auto / Off / Fixed, with stratified sampling that preserves
  phenotype proportions; large slides project the rest via weighted kNN, so every
  cell still gets coordinates.
- **Colour by** — phenotype (from the gate tree) or a single marker's expression,
  as z-score (blue-white-red) or raw intensity (viridis).
- **Interactive legend** — click a population to hide it, hover to highlight it,
  and read each one's share of the total.
- **Population tagging** — name + colour a polygon selection and store it as a
  derived PathClass; multiple tags coexist with coloured ring overlays.
- **Viewer link** — two-way selection between the embedding and the QuPath image
  viewer (selection only; it never changes classifications).
- **OOM protection** — memory is estimated before computing, with a warning if a
  dataset may run out.
- **Locked inputs during a run** — the presets, the feature picker and the
  subsampling controls are disabled while a computation is in flight, so what the
  result was computed from is what the panel was showing when you pressed Run.

#### What a run reports about itself { #what-a-run-reports-about-itself }

A UMAP that had to degrade something still produces a picture, and the picture
looks exactly like a clean one. So every successful run now says what it cost:
the **first finding on the status line**, the **whole report in the tooltip**
behind it. A run with nothing to report says so and stays green.

What can appear there:

- **Cells the projection could not place.** Held-out cells with no usable sampled
  neighbour are left at exactly `(0, 0)`, where they read as a real, tight cluster
  rather than as missing data. If you see a suspiciously dense blob at the origin,
  this is the line that tells you it is not one.
- **Markers no training cell carried.** Imputed with the mean of nothing, so each
  is a column of zeros contributing nothing to any distance — the embedding was
  effectively over fewer markers than you ticked.
- **Constant markers.** Known data, unlike the above, but a feature with no
  variance moves no distance.
- **The imputed cell, and the rows reweighted around it.** One node is detached
  from the neighbour graph so the layout starts from PCA rather than a spectral
  initialisation (see below); its position is imputed from its true neighbours,
  and the cells that listed it have their distance vectors rewritten. This is
  policy on every run under the spectral limit, so it is recorded as provenance
  rather than as a warning.
- **The subsample size**, when subsampling was applied — how much of the picture
  was optimised rather than projected.

!!! note "Layout initialisation"
    FlowPath always initialises the layout from **PCA**. The spectral alternative
    needs an ARPACK native library that has no `macosx-arm64` build, so a run that
    reached it failed outright. Because FlowPath now chooses, embeddings are
    reproducible across platforms — but coordinates for datasets of 10 000 cells
    or fewer differ from any this extension produced before v2.2.0.

| Cell count | Strategy | Expected time |
|---|---|---|
| < 10K | Direct computation | 2–5 s |
| 10K–50K | Direct with progress | 10–30 s |
| 50K–100K | Auto-subsampling recommended | 5–15 s |
| > 100K | Subsampling + kNN projection | 10–30 s |
