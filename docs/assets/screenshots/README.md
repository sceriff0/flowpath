# Screenshot manifest

This folder holds the images used throughout the FlowPath docs. Every page
currently points at **`placeholder.png`** so pages render cleanly before real
images exist. To add a real screenshot:

1. Capture the shot described below.
2. Save it here with the **exact filename** in the table (PNG, ideally ~1280×720
   or wider, retina if possible).
3. In the corresponding page, change the image path from
   `assets/screenshots/placeholder.png` to `assets/screenshots/<filename>`.

> This README is excluded from the published site (see `exclude_docs` in
> `mkdocs.yml`).

| Filename | Page | What it should show |
|---|---|---|
| `hero.png` | `index.md` | A hero montage: a multiplexed slide open in QuPath with a GatingTree panel and a qUMAP embedding side by side. |
| `catalog-add.png` | `installation.md`, `catalog.md` | QuPath → Extensions → Manage extensions → Manage extension catalogs → **Add**, with the FlowPath catalog URL pasted in. |
| `catalog-list.png` | `installation.md`, `catalog.md` | The Manage extensions list showing GatingTree, AnnoMask, qUMAP available to install with the `+` button. |
| `gatingtree-tree.png` | `gatingtree.md`, `walkthrough.md` | The GatingTree panel with a multi-level hierarchy, e.g. `CD45+ → CD3+ → CD8+ = "T cytotoxic"`. |
| `gatingtree-histogram.png` | `gatingtree.md` | A 1D marker histogram with a draggable threshold line. |
| `gatingtree-scatter.png` | `gatingtree.md` | A 2D scatter / quadrant gate with crosshair, cells colored by quadrant. |
| `gatingtree-recolor.png` | `gatingtree.md`, `walkthrough.md` | The image viewer with cells recolored live by their assigned phenotype. |
| `annomask-dialog.png` | `annomask.md`, `walkthrough.md` | The AnnoMask dialog showing the two input modes (channel-in-image / load from file) and the intensity-sampling option. |
| `annomask-detections.png` | `annomask.md` | Before/after: a labeled mask and the resulting QuPath detections overlaid on the image. |
| `qumap-embedding.png` | `qumap.md`, `walkthrough.md` | A UMAP embedding colored by phenotype with the sortable legend (names + counts). |
| `qumap-polygon.png` | `qumap.md` | Polygon selection in UMAP space with out-of-polygon cells greyed out (UNFOCUSED). |
| `qumap-overlay.png` | `qumap.md` | Side-by-side UMAP: one colored by z-score (blue-white-red), one by raw intensity (viridis). |
