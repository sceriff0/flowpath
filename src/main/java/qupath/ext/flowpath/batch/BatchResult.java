package qupath.ext.flowpath.batch;

import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.ingest.IngestReport;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.List;

/**
 * What happened to one slide. A failure is a value, not an exception: a project holds slides
 * with no detections or an unreadable server, and aborting on the first would waste the rest.
 * {@code resolved} is the tree this slide was gated with — what the manifest reports.
 */
public record BatchResult(String slideId, String imageName, int cellCount, List<String> markers,
                          PopulationStats stats, TreeResolver.ResolvedTree resolved, IngestReport report,
                          WriteBack writeBack, String writeBackError, String failure) {

    public enum WriteBack { NOT_REQUESTED, SAVED, SKIPPED_OPEN_SLIDE, FAILED }

    public boolean succeeded() {
        return failure == null;
    }

    public static BatchResult failed(String slideId, String imageName, String failure) {
        return new BatchResult(slideId, imageName, 0, List.of(), null, null, null,
                WriteBack.NOT_REQUESTED, null, failure);
    }

    BatchResult withWriteBack(WriteBack wb, String error) {
        return new BatchResult(slideId, imageName, cellCount, markers, stats, resolved, report, wb, error, failure);
    }
}
