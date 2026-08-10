---
hide:
  - navigation
  - toc
---

<div class="flowpath-hero" markdown>

<img class="flowpath-wordmark" src="assets/logo.png" alt="FlowPath logo">

**FlowJo-style workflows for QuPath**

A [QuPath](https://qupath.github.io/) 0.7.0 extension that turns multiplexed
imaging into living, clickable biology — gate cells into named phenotypes, then
explore those phenotypes in a UMAP you can lasso. FlowPath picks up where the
[MIRAGE](https://mirage-pipeline.readthedocs.io/) pipeline leaves off.

<div class="flowpath-badges" markdown>
[:material-rocket-launch: Install](installation.md){ .md-button .md-button--primary }
[:material-walk: How to use](usage.md){ .md-button }
[:fontawesome-brands-github: GitHub](https://github.com/sceriff0/qupath-extension-flowpath-gatingtree){ .md-button }
</div>

</div>

[![QuPath](https://img.shields.io/badge/QuPath-%E2%89%A50.7.0-2563eb.svg)](https://qupath.github.io/)
[![Java](https://img.shields.io/badge/Java-25-f97316.svg)](https://jdk.java.net/25/)
[![License: MIT](https://img.shields.io/badge/license-MIT-8b5cf6.svg)](https://opensource.org/licenses/MIT)

## What FlowPath is

FlowPath brings the muscle-memory of **flow cytometry** — gating populations,
reducing dimensions, lassoing clusters — *inside* QuPath, on **multiplexed tissue
imaging** (CODEX, MIBI, mIF). You segment and quantify cells once, then phenotype
and explore them interactively without leaving the viewer.

It works on one shared object — **QuPath detections carrying per-marker
measurements** — and gives you two views onto it:

| View | Role | One line |
|---|---|---|
| **Gating** | *phenotype* | Gate detections into named populations with a live hierarchical tree. |
| **UMAP** | *explore* | Embed your gated markers in 2D, coloured by the phenotypes you just built, and lasso clusters. |

Gating is the way in. The UMAP opens from it and inherits the phenotyping —
the same cells, the same colours, the same markers:

```mermaid
flowchart LR
    M[MIRAGE pipeline<br/>OME-TIFF · cells.geojson · masks] --> Q[QuPath 0.7.0]
    subgraph FP[FlowPath]
      GT[Gating<br/>interactive phenotyping]
      UM[UMAP<br/>phenotype-coloured embedding]
      GT -- "Open UMAP<br/>(phenotypes, colours, gated markers)" --> UM
    end
    Q --> GT
    CAT[(catalog.json)] -. installs .-> FP
```

!!! tip "Upgrading from GatingTree + qUMAP"
    FlowPath used to ship as two extensions. They are now one. Remove
    *FlowPath - GatingTree* and *FlowPath - qUMAP* under **Extensions → Manage
    extensions** before installing FlowPath — QuPath identifies extensions by
    name and will not treat FlowPath as an upgrade of either. Saved gate trees
    load unchanged. See [Installation](installation.md).

## Where MIRAGE fits

[MIRAGE](https://mirage-pipeline.readthedocs.io/) is a Nextflow pipeline that produces
FlowPath's inputs: a pyramidal **OME-TIFF**, a QuPath-native **`cells.geojson`**,
and labeled **segmentation masks**. It deliberately stops at *quantified cells* —
phenotyping and exploration are what FlowPath adds.

You don't need MIRAGE, though. FlowPath works with **any** source of detections
plus measurements — Cellpose or StarDist masks imported via
[AnnoMask](https://github.com/sceriff0/qupath-extension-annomask), or any GeoJSON
with per-marker measurements. MIRAGE is the reference upstream because the measurement
keys line up exactly (see [Usage → the data model](usage.md#the-data-model)).

## Get started

<div class="grid cards" markdown>

-   :material-download:{ .lg .middle } **Install FlowPath**

    ---

    Add one catalog URL in QuPath and install the extension in a couple of
    clicks.

    [:octicons-arrow-right-24: Installation](installation.md)

-   :material-walk:{ .lg .middle } **Run the workflow**

    ---

    From cells in QuPath to gated phenotypes to a UMAP, end to end — plus the
    per-tool options.

    [:octicons-arrow-right-24: Usage](usage.md)

</div>

!!! note "FlowPath and MIRAGE are separate projects"
    FlowPath is an independent, MIT-licensed QuPath extension by
    [`sceriff0`](https://github.com/sceriff0).
    [MIRAGE](https://mirage-pipeline.readthedocs.io/) is the upstream Nextflow pipeline,
    with its own documentation. If you publish with them, please
    [cite both](citation.md).
