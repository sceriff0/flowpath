package qupath.ext.flowpath.testing;

import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;

import java.nio.file.Path;
import java.util.List;

/**
 * MIRAGE's real two-cell export with {@code QC:} and {@code MORPH:} keys, produced by its
 * {@code bin/export_geojson.py} (pixel size 0.5 µm). Cell 1 passes everything; cell 2 lost its
 * nucleus in the [CD3, CD8] round (retention 0.04) and was not matched by registration QC.
 */
public final class MirageSample {

    private MirageSample() {}

    public static List<PathObject> cells() throws Exception {
        Path file = Path.of(MirageSample.class.getResource("/qupath/ext/flowpath/mirage-qc-sample.geojson").toURI());
        return PathIO.readObjects(file);
    }
}
