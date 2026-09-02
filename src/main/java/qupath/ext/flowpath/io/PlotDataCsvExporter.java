package qupath.ext.flowpath.io;

import qupath.ext.flowpath.analysis.ui.PlotDatum;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports one Analysis plot's own numbers — {@code PlotCanvas.plotData()} — as a CSV, for
 * {@code AnalysisPane}'s "Plot data as CSV…" menu item.
 * <p>
 * {@code plotName} is repeated on every row rather than written once, which is what lets
 * several plots' exports be concatenated into one file behind a single header line — the same
 * reason {@link PopulationStatsExporter}'s {@code image} column exists for a multi-image
 * export.
 * <p>
 * Reuses {@link CellTable#escape(String)} and {@link CellTable#fmt(double)}, not
 * {@code String.format} directly, for the reason every other exporter in this package does:
 * this JVM's default locale is {@code en_IT}, whose decimal separator is a comma, and would
 * split a value into two CSV columns.
 */
public final class PlotDataCsvExporter {

    private PlotDataCsvExporter() {
        // static utility class
    }

    /** Export {@code data} to {@code file}, single-plot. */
    public static void export(File file, String plotName, List<PlotDatum> data) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            write(w, plotName, data);
        }
    }

    /** Write the header line and one row per datum, {@code plotName} repeated on each. */
    public static void write(Writer w, String plotName, List<PlotDatum> data) throws IOException {
        w.write("plot,category,series,value\n");
        for (PlotDatum datum : data) {
            w.write(CellTable.escape(plotName));
            w.write(',');
            w.write(CellTable.escape(datum.category()));
            w.write(',');
            w.write(CellTable.escape(datum.series()));
            w.write(',');
            w.write(CellTable.fmt(datum.value()));
            w.write('\n');
        }
    }
}
