package qupath.ext.flowpath.ingest;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;

import java.util.List;

/**
 * Everything one read of the hierarchy produces, from one sample of it.
 * <p>
 * The four values are consistent <em>by construction</em>: the panel, the capability and
 * the index all derive from a single measurement-key sample taken once inside
 * {@link DetectionIngest#read}. Assembling them from separate calls is what let the gate
 * editor offer a compartment the index had resolved to {@code null}.
 *
 * @param index        the columnar cell index, built at {@code selection}'s resolution
 * @param capability   which compartments and statistics this export actually carries
 * @param markerNames  the marker panel, in panel order when it came from image channels
 *                     and alphabetical when it was derived from the measurements
 * @param selection    the per-marker (compartment, statistic) the index was built at
 * @param report       what the adapter found and what it could not resolve
 */
public record IngestResult(CellIndex index,
                           CompartmentCapability capability,
                           List<String> markerNames,
                           MarkerSelection selection,
                           IngestReport report) {

    public IngestResult {
        markerNames = List.copyOf(markerNames);
    }
}
