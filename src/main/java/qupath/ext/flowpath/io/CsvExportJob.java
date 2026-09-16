package qupath.ext.flowpath.io;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RegionMask;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * What a CSV export needs, captured once, and the two steps ({@link GatingEngine#assignAll}
 * then {@link PhenotypeCsvExporter#export}) that turn it into a file.
 * <p>
 * <b>Why a snapshot.</b> {@code FlowPathPane.exportCsv} used to read {@code session.tree()}
 * and gate it in the same call, on the FX thread. Moving the gating pass and the write to a
 * background thread — so exporting a million-cell slide does not freeze QuPath — opens a
 * window between the moment the user asks to export and the moment the file is written, and
 * the gate tree is edited in place: a threshold nudged in that window would otherwise leak
 * into a file the user believed matched what they saw when they clicked. {@link Snapshot#of}
 * deep-copies the tree so the export always reflects gates exactly as they were at that
 * moment, and clones the ROI mask for the same reason — a boolean array, unlike
 * {@link CellIndex} or {@link MarkerStats}, is never republished as a new instance.
 * <p>
 * Toolkit-free: {@link #run} touches no JavaFX class, so the snapshot-isolation guarantee is
 * a plain JUnit test.
 */
public final class CsvExportJob {

    /**
     * Everything one export needs, as of the moment it was requested.
     * <p>
     * {@code index}, {@code stats} and {@code regions} are held by reference: every producer
     * of these three ({@code GatingSession.adopt}, {@code MarkerStats.compute},
     * {@code RegionMask.compute}) hands over a fresh instance rather than mutating an old one
     * in place, so capturing the reference at snapshot time is exactly as safe as a deep copy
     * would be. {@code tree} and {@code roiMask} are the two exceptions — a gate tree is
     * edited node-by-node while the pane holds it, and a mask array could in principle be
     * reused — so those two are the ones this record actually copies.
     */
    public record Snapshot(File file, GateTree tree, CellIndex index, MarkerStats stats,
                            boolean[] roiMask, RegionMask regions) {

        public Snapshot {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(stats, "stats");
            roiMask = roiMask == null ? null : roiMask.clone();
        }

        /**
         * Capture {@code tree}, {@code index}, {@code stats}, {@code roiMask} and
         * {@code regions} as they are right now: {@code tree} deep-copied, {@code roiMask}
         * cloned, so a later edit to the live tree or a later resync cannot reach this
         * snapshot.
         */
        public static Snapshot of(File file, GateTree tree, CellIndex index, MarkerStats stats,
                                   boolean[] roiMask, RegionMask regions) {
            return new Snapshot(file, tree.deepCopy(), index, stats, roiMask, regions);
        }
    }

    private CsvExportJob() {
        // static utility class
    }

    /**
     * Gate {@code snapshot}'s cells and write the file. Blocking; call off the FX thread.
     * Produces exactly what {@code FlowPathPane.exportCsv} produced before this was
     * extracted: {@link GatingEngine#assignAll(GateTree, CellIndex, MarkerStats, boolean[])}
     * then {@link PhenotypeCsvExporter#export(File, CellIndex, GatingEngine.AssignmentResult,
     * GateTree, MarkerStats, RegionMask)}.
     */
    public static void run(Snapshot snapshot) throws IOException {
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(
                snapshot.tree(), snapshot.index(), snapshot.stats(), snapshot.roiMask());
        PhenotypeCsvExporter.export(snapshot.file(), snapshot.index(), result, snapshot.tree(),
                snapshot.stats(), snapshot.regions());
    }
}
