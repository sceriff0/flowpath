# Installation

FlowPath installs through a QuPath **extension catalog** — think of it as
FlowPath's app store. Add one URL and QuPath will offer to install, and later
update, the extension for you.

!!! info "Requirements"
    - **[QuPath](https://qupath.github.io/) 0.7.0 or later** — FlowPath targets
      QuPath 0.7.0 and runs on Windows, macOS, or Linux.
    - Java is bundled with QuPath at runtime. Building from source additionally
      needs **JDK 25**.

## Upgrading from GatingTree + qUMAP

!!! warning "Remove the old two extensions first"
    FlowPath used to ship as **FlowPath - GatingTree** and **FlowPath - qUMAP**.
    They are now a single extension called **FlowPath**.

    QuPath identifies extensions by name, so it will *not* recognise FlowPath as
    an upgrade of either. Leaving them installed gives you three menu items and
    two stale copies of the code — including a second, independent cell index
    that will disagree with the new one.

    1. **Extensions → Manage extensions**
    2. Remove *FlowPath - GatingTree* and *FlowPath - qUMAP*
    3. Install *FlowPath* (below) and restart QuPath

    **Your data is safe.** Saved gate trees (`.json`) load unchanged, and
    phenotype classifications already written onto cells live in the QuPath
    project, not in the extension.

## Recommended: add the catalog

In QuPath:

**Extensions → Manage extensions → Manage extension catalogs → Add catalog →**

```
https://raw.githubusercontent.com/sceriff0/flowpath/main/catalog.json
```

Then, back in **Manage extensions**, install **FlowPath** with the `+` button.
Restart QuPath when prompted.

<figure class="screenshot" markdown>
![Adding the FlowPath catalog URL in QuPath](assets/screenshots/placeholder.png){ .glightbox }
<figcaption>QuPath → Manage extension catalogs → Add, with the FlowPath catalog URL pasted in. <em>(placeholder)</em></figcaption>
</figure>

!!! tip "What the catalog is"
    The catalog is just `catalog.json` served from GitHub. It tells QuPath which
    extensions exist, where to download each release JAR, and the minimum QuPath
    version each needs — it never touches your images or cells. New releases are
    added to it as they ship, so QuPath always offers the newest version
    compatible with your QuPath.

## Verify the install

After restarting QuPath, open the **Extensions** menu. You should see a single
entry:

| Menu entry | Shortcut | Opens |
|---|---|---|
| Extensions → FlowPath | ++ctrl+g++ | The gating window |

One menu item is all there is: the Analysis window and everything else open from
buttons in the gating window's toolbar. See the
[walkthrough in Usage](usage.md).

!!! warning "UMAP is coming in a future release"
    UMAP exploration is not available in this version — the **Open UMAP** button
    is disabled and labelled *UMAP (coming soon)*, and ++ctrl+u++ does nothing.
    There will still be no separate UMAP menu item when it ships: it opens from
    the gating window so it can inherit your phenotyping.

!!! warning "Catalog adds, but no extension appears?"
    Almost always a QuPath version below 0.7.0, or a typo in the catalog URL (it
    must be the **raw** `raw.githubusercontent.com` URL). See
    [Troubleshooting](troubleshooting.md#installation).

## Current version

The latest release published in
[`catalog.json`](https://github.com/sceriff0/flowpath/blob/main/catalog.json)
— the source of truth. QuPath's extension manager reads it live.

| Extension | Latest | Minimum QuPath |
|---|---|---|
| FlowPath | **v0.9.0** | v0.7.0 |

!!! warning "Versioning restarted at 0.9.0"
    FlowPath was developed under a 1.x / 2.x line while its output formats were still
    moving. 0.9.0 is the first release cut against a settled one, and 1.0.0 will
    accompany the paper. Because 0.9.0 sorts *below* those earlier releases, **QuPath
    will not offer it as an upgrade** — remove any previously installed FlowPath under
    *Extensions → Manage extensions* and install this one fresh. Saved gate trees
    (`.json`) load unchanged.

The changelog and full release history live on the
[repo's Releases page](https://github.com/sceriff0/flowpath/releases).

## Alternative: drop in a JAR

Prefer to do it by hand? Download the release JAR from
[GitHub Releases](https://github.com/sceriff0/flowpath/releases)
and drop it into QuPath's **extensions directory** (Extensions → Manage
extensions shows the path), then restart QuPath.

!!! danger "Take the `-all.jar`"
    FlowPath bundles the [SMILE](https://haifengl.github.io/) library for its UMAP
    engine, so the release ships a **fat JAR** named `FlowPath-<version>-all.jar`.
    Any thinner JAR on the releases page installs fine and then fails with
    `NoClassDefFoundError` as soon as anything reaches that engine — which is what
    the UMAP will do once it ships. Always take the `-all.jar`.

## Alternative: build from source

FlowPath uses the standard `qupath-extension-settings` Gradle plugin and needs
**JDK 25** plus QuPath 0.7.0 artefacts.

```bash
git clone https://github.com/sceriff0/flowpath.git
cd flowpath
./gradlew build shadowJar
```

`build` alone produces only the thin JAR — `shadowJar` is what bundles SMILE.
Drag `build/libs/FlowPath-<version>-all.jar` onto QuPath.

## Related extensions

These are separate QuPath extensions by the same author, installed from their own
repos rather than this catalog:

| Extension | What it does |
|---|---|
| [AnnoMask](https://github.com/sceriff0/qupath-extension-annomask) | Import labelled segmentation masks (MIRAGE, Cellpose, StarDist…) as QuPath detections. |
| [Decidware](https://github.com/sceriff0/qupath-extension-decidware) | Decision-support tooling for QuPath. |
