package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.ui.PlotDatum;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlotDataCsvExporterTest {

    @Test
    void writesOneRowPerDatumWithThePlotNameRepeated() throws Exception {
        StringWriter w = new StringWriter();
        PlotDataCsvExporter.write(w, "Composition", List.of(
                new PlotDatum("CD45+/CD8+", "count", 1204),
                new PlotDatum("CD45+/CD8-", "count", 88)));
        String[] lines = w.toString().trim().split("\n");
        assertEquals("plot,category,series,value", lines[0]);
        assertEquals(3, lines.length);
        assertTrue(lines[1].startsWith("Composition,"), lines[1]);
        assertTrue(lines[1].contains("CD45+/CD8+"), lines[1]);
    }

    @Test
    void escapesCategoriesThatContainCommasOrQuotes() throws Exception {
        StringWriter w = new StringWriter();
        // Branch names are user-editable; "Tumour, margin" is a legal one.
        PlotDataCsvExporter.write(w, "By Region", List.of(
                new PlotDatum("Tumour, margin", "CD8+", 5),
                new PlotDatum("He said \"core\"", "CD8+", 6)));
        String csv = w.toString();
        assertTrue(csv.contains("\"Tumour, margin\""), csv);
        assertTrue(csv.contains("\"\""), "an embedded quote is doubled: " + csv);
        assertEquals(3, csv.trim().split("\n").length, "no stray line breaks: " + csv);
    }

    @Test
    void decimalsUseUsLocale() throws Exception {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            StringWriter w = new StringWriter();
            PlotDataCsvExporter.write(w, "P", List.of(new PlotDatum("a", "s", 1.5)));
            assertTrue(w.toString().contains("1.5"), w.toString());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
