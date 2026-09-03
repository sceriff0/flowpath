# Troubleshooting & FAQ

Common questions first, then fixes for specific issues. If nothing here covers
your problem, open an issue on the
[FlowPath repo](https://github.com/sceriff0/flowpath/issues).

## Common questions

??? question "Do I need MIRAGE to use FlowPath?"
    No. FlowPath works with **any** source of QuPath detections that carry
    per-marker measurements — Cellpose or StarDist masks via AnnoMask, or any
    GeoJSON with marker measurements. MIRAGE is the reference upstream because its
    [measurement keys](usage.md#the-data-model) line up perfectly, but it isn't
    required.

??? question "What's the difference between the two ways to import cells?"
    On-ramp A imports MIRAGE's `cells.geojson` directly (already quantified).
    On-ramp B brings a label mask in via AnnoMask, which re-derives identical
    intensities in-app. Both end with detections that carry measurements. See
    [Usage → Step 1](usage.md#step-1-get-cells-into-qupath).

??? question "Which QuPath version do I need?"
    **QuPath 0.7.0 or later.** See the
    [current version](installation.md#current-version).

??? question "Is FlowPath part of MIRAGE?"
    No. FlowPath is an **independent, MIT-licensed QuPath extension** by
    [`sceriff0`](https://github.com/sceriff0). MIRAGE is a separate Nextflow
    pipeline with its [own documentation](https://mirage-pipeline.readthedocs.io/).

??? question "Where did GatingTree and qUMAP go?"
    They were fused into a single **FlowPath** extension. Gating is now the way
    in; the UMAP will open from it — already carrying your phenotypes, colours and
    gated markers — once it ships, which is not in this version (see
    [UMAP](#umap)). If you still have the old two installed, remove them: see
    [Upgrading](installation.md#upgrading-from-gatingtree-qumap).

??? question "Why is the Open UMAP button disabled?"
    UMAP exploration is **coming in a future release**. The button is disabled and
    labelled *UMAP (coming soon)* in this version, and ++ctrl+u++ does nothing.
    Gating and the CSV exports are unaffected. (The **Analysis** button is
    disabled in this version too — see [Analysis](#analysis).) The UMAP sections
    in these docs describe how it will work once it ships.

??? question "Will the gating tree and the UMAP share the same cells?"
    Yes — literally the same in-memory index, not two copies that happen to
    agree. That is why the UMAP will open instantly, and why a gate edit will
    recolour the embedding rather than invalidating it.

??? question "What does the Analysis window give me that the gate tree doesn't?"
    The tree shows one number per branch, for the whole slide. The Analysis window
    reports every population at **three nested scopes** (whole slide, all
    annotations, per annotation), against **either denominator** (parent, total, or
    any branch you pick), with **area-normalised density** and four comparison
    plots — and exports the lot as `population_stats.csv`. It is
    **coming in a future release** (see [Analysis](#analysis)); until then the
    tree's own per-branch numbers are what you have. See
    [Usage → Step 3](usage.md#step-3-read-the-numbers-in-the-analysis-window).

??? question "Can I reproduce or share a gating strategy?"
    Yes — FlowPath saves the full gate hierarchy to `flowpath.json`. Load it on
    another image to reproduce the gates (given the same markers are present).
    Files saved by older GatingTree versions load unchanged.

??? question "How do I update the extension?"
    Through the same catalog. When a new release is published, QuPath's extension
    manager offers the update.

## Installation { #installation }

??? failure "I upgraded and now there are three FlowPath menu items"
    The old **FlowPath - GatingTree** and **FlowPath - qUMAP** are still
    installed. QuPath identifies extensions by name, so it does not treat FlowPath
    as an upgrade of either. Remove both under **Extensions → Manage extensions**
    and restart. See
    [Upgrading](installation.md#upgrading-from-gatingtree-qumap).

??? failure "The catalog adds, but no extension appears"
    Almost always a **QuPath version below 0.7.0**. FlowPath pins a minimum of
    `v0.7.0`, so older QuPath won't show it. Check **Help → About** for your
    version and upgrade if needed.

??? failure "Adding the catalog URL fails or shows nothing"
    Check the URL for typos — it must be exactly the **raw** GitHub URL:
    ```
    https://raw.githubusercontent.com/sceriff0/flowpath/main/catalog.json
    ```
    (note `raw.githubusercontent.com`, not the repository web page). A
    network/proxy block on `githubusercontent.com` will also cause this.

??? failure "Install starts but the extension never loads"
    The release JAR may have failed to download. Try the
    [manual JAR install](installation.md#alternative-drop-in-a-jar) from the
    Releases page, and restart QuPath fully.

??? failure "Extension menu entry is missing after install"
    Restart QuPath completely — extensions register at startup. If still missing,
    confirm the JAR landed in QuPath's extensions directory (Extensions → Manage
    extensions shows the path).

??? failure "`NoClassDefFoundError` from the UMAP engine"
    You installed a thin JAR. FlowPath bundles the SMILE library for its UMAP
    engine, so the correct artefact is **`FlowPath-<version>-all.jar`**. Installing
    through the catalog always picks the right one; this only bites manual
    installs. See
    [drop in a JAR](installation.md#alternative-drop-in-a-jar). Nothing in this
    version reaches that engine — the UMAP is
    [coming in a future release](#umap) — but take the `-all.jar` anyway, so the
    install you have is the one that will run it.

## Gating { #gating }

??? failure "Gates show no cells / histograms are empty"
    Your detections probably have **no measurements**. Import a `cells.geojson`
    that carries intensities, or use AnnoMask with **intensity sampling enabled**.
    See [the data model](usage.md#the-data-model).

??? failure "I can't pick a compartment (Nucleus / Cytoplasm / Cell)"
    The data has no [per-compartment keys](usage.md#the-data-model) — FlowPath
    falls back to whole-cell / Mean. Re-export from MIRAGE with per-compartment
    quantification if you need them.

??? failure "Too many tiny/odd objects are being gated"
    Set the **quality filters** (area, eccentricity, solidity, perimeter, total
    intensity) before gating to drop segmentation artefacts.

## Analysis { #analysis }

!!! warning "The Analysis window is coming in a future release"
    It is not available in this version — the **Analysis** button is disabled and
    labelled *Analysis (coming soon)*. Gating, the filters and `gate_pheno.csv`
    are unaffected. The rest of this section describes how it will behave once it
    ships.

??? failure "The Analysis button refuses to open the window"
    Three separate reasons, and the notification says which. **"Load an image with
    cell detections first"** — there is no cell index yet. **"Waiting for the first
    gating pass to finish"** — the index is there but no pass has completed; give it
    a moment. **"Add at least one gate to see population statistics"** — a tree with
    no *enabled* root gate produces no rows at any scope, so the window would open
    onto a blank table with nothing to explain it.

??? failure "The table shows a message instead of rows"
    *"No gating pass to report on yet — gate some cells to see population
    statistics."* means exactly that: the window is open but has not been handed a
    completed pass. The window never shows an unexplained blank table — if there is
    no data, there is a message saying why.

??? failure "Count doesn't match what the gate tree shows"
    Read **Clean**, not **Count**. **Count** is the raw total including cells the
    quality filter excluded from the view; **Clean** is what survived exclusion, and
    is the number the tree shows — that agreement is structural, not a coincidence
    of the two being computed the same way.

    What the gap between them means depends on the **Scope**. At *Whole slide* it
    folds in both quality filtering and, when the annotation ROI filter is on, cells
    outside the annotations. At the two per-region scopes an ROI-excluded cell
    belongs to no region and is not counted at all, so there the gap is quality
    filtering alone. See [Usage → the table](usage.md#the-table).

??? failure "Density is blank"
    FlowPath has no usable area for that region. Density divides by **effective**
    area — the annotation geometry with `Ignore*` regions subtracted and overlaps
    resolved first-match-wins — and an unknown or zero effective area is reported as
    blank rather than as `0`, because a zero denominator produces an infinite density
    that reads like a real measurement. Density is also blank at *Whole slide*, which
    has no annotated area to divide by.

??? failure "% of Denominator is blank"
    Either no denominator is chosen (the picker reads *(none)*), or the branch you
    chose holds no cells. Both are deliberately blank: neither is a question with a
    numeric answer, and rendering the second as `0.0` would state a share of nothing
    as though it had been measured. To tell them apart, read the `denominator_count`
    column in the exported CSV.

??? failure "Two rows have the same population name"
    Expected, and the **Root** column is the fix. `GateNode` names its branches from
    the channel alone, so two root gates on the same channel that you have not
    renamed produce byte-identical population paths. The **Root** column (one-based)
    and the pickers' `(root N)` suffix are the only things that separate them; in the
    CSV it is the `root_index` column, which is zero-based.

    The same thing happens one axis down with regions: `RegionMask` falls back to an
    annotation's *classification* for its name, so two annotations both classified
    `Tumor` both appear as `Tumor`. `region_index` in the CSV separates those.

??? failure "*Per annotation* isn't in the Scope picker"
    The slide has no annotations to report per-region, so the only scope that exists
    is *Whole slide*. Draw annotations (or select the ones you want) and the two
    annotation scopes appear on the next gating pass.

??? failure "I disabled a gate and the window stopped updating"
    Deliberate. With no enabled root gate there are no rows at any scope, so FlowPath
    **skips** the push rather than overwriting the window with an unexplained blank
    table. Re-enable a gate and the next pass repopulates it.

??? question "Does the exported CSV match what I'm looking at?"
    It is a superset. The export carries **every scope and every region**, not just
    the rows the table is showing — a report that silently dropped two of its three
    scopes on the way to disk would be the more surprising behaviour. The one thing
    it does follow is the **Denominator** picker, since that changes the numbers
    themselves.

## UMAP — coming in a future release { #umap }

!!! warning "These entries describe a feature that has not shipped"
    UMAP exploration is not available in this version — the **Open UMAP** button
    is disabled and labelled *UMAP (coming soon)*, and ++ctrl+u++ does nothing.
    Nothing below can happen to you yet; it is kept here because it will apply
    once the feature ships.

??? failure "Open UMAP says it's waiting for the first gating pass"
    Once the feature ships, the button needs a completed gating run to have
    something to hand over. Load an image with detections and give the first pass
    a moment; if the status bar still reads 0 cells, the detections carry no
    measurements (see [Gating](#gating)).

??? failure "Out-of-memory while computing"
    FlowPath estimates memory first and warns you. Switch subsampling to **Auto**
    or **Fixed** under *Embedding → Advanced*, or increase QuPath's max memory
    (Edit → Preferences) and restart. Large slides (>100K cells) should use
    subsampling + kNN projection — see the
    [performance table](usage.md#umap).

??? failure "Run UMAP is greyed out"
    Two reasons, and the panel says which. **Fewer than two markers are ticked** —
    a UMAP is computed over the ticked columns and needs at least two of them, so
    open **Features…** and tick more; the empty state reads *"Not enough markers to
    embed"* and counts them for you. Or **a feature change is still being applied**
    — ticking a marker rebuilds the cell index, and Run unlocks when the new
    columns are ready (*"Rebuilding the cell index…"*). The button is also disabled
    for the duration of a run, along with the rest of the inputs, so a preset
    cannot be changed out from under a computation in flight.

??? failure "The UMAP warns about something after computing"
    That is the run reporting what it had to degrade — the picture would otherwise
    look exactly like a clean one. The status line carries the first finding;
    **hover it for the full report**, which also names the subsample size. The
    common ones:

    - *cells sitting at exactly (0,0)* — held-out cells the kNN projection found no
      usable neighbour for. They form a dense blob that reads as a real cluster.
      Turn subsampling down or off under *Embedding → Advanced* if the count is
      material.
    - *markers no training cell carried* — imputed with the mean of nothing, so
      they are columns of zeros contributing nothing. Untick them, or check the
      compartment/statistic they resolve to under **Features…**.
    - *constant markers* — real data, but no variance, so no distance.
    - *one cell imputed, N neighbourhoods reweighted* — provenance, not a defect.
      FlowPath detaches one node so the layout starts from PCA; see
      [layout initialisation](usage.md#what-a-run-reports-about-itself).

??? failure "The embedding isn't coloured by phenotype"
    Check the **Colour** section is set to **Phenotype** rather than **Marker**.
    If Phenotype shows one flat grey, no gate has claimed any cells yet — build at
    least one named gate in the gating window and the UMAP recolours immediately.

??? failure "I changed a gate and the UMAP didn't update"
    The UMAP only tracks gating while its window is open; it is never opened
    automatically. If it *is* open and still stale, the cell set changed rather
    than just the thresholds (a new image, or a toggled annotation filter), which
    invalidates the embedding — press **Run UMAP** again.

??? failure "One population covers everything else"
    Click that population in the legend to hide it. Hidden points are not drawn at
    all, so whatever they were burying becomes visible immediately; click
    *show all* in the legend header to bring them back.

??? failure "Computation is very slow"
    Use the **Fast** quality preset, enable subsampling for large datasets, and
    trim the feature list under **Features…** — opening from a gate tree already
    narrows it to your gated markers (given at least two of them), but an ungated
    40-plex will embed all forty. See the [performance table](usage.md#umap).

    Trimming the list only started doing this in the final 2.x release. In earlier
    versions the include flag was a picker preference: unticking a marker changed one
    label and the embedding still ran over the whole panel.

## AnnoMask { #annomask }

[AnnoMask](https://github.com/sceriff0/qupath-extension-annomask) is a separate
extension; these are the issues that come up while using it as a FlowPath
on-ramp.

??? failure "Detections are misaligned with the image"
    A loaded mask is assumed to **align to the current image's pixel grid**. Make
    sure the mask resolution and orientation match the open OME-TIFF.

??? failure "Cell count differs from what I expected"
    AnnoMask produces **one detection per unique integer label**, merging multiple
    connected components of the same label — matching MIRAGE's count. Re-label the
    mask if you want components split.

??? failure "Measurements are empty after import"
    Enable **intensity sampling** in the AnnoMask dialog and make sure the
    OME-TIFF (with the channels) is the open image.
