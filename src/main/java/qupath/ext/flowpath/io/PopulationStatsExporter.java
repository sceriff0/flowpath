package qupath.ext.flowpath.io;

import qupath.ext.flowpath.model.PopulationStats;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Exports a {@link PopulationStats} table to CSV: one row per {@link PopulationStats.Row},
 * one file per gating pass.
 * <p>
 * <b>Why {@link #writeHeader} and {@link #writeRows} are separate from {@link #export}.</b>
 * The batch-gating plan runs one gate tree over many images and wants a single combined
 * CSV: one header, then every image's rows appended after it, with an {@code image} column
 * telling them apart. That caller needs exactly these two pieces composed in a loop; a
 * monolithic {@code export(File, PopulationStats)} would force it to keep a second copy of
 * the field order and the {@link CellTable#fmt} formatting, which is how two files meant to
 * share a schema drift apart. {@link #export} is simply the single-image composition of
 * the same two calls.
 * <p>
 * Reuses {@link CellTable#escape(String)} and {@link CellTable#fmt(double)} rather than
 * {@code String.format} directly: this JVM's default locale is {@code en_IT}, whose decimal
 * separator is a comma, which would split a percentage or a density into two CSV columns.
 */
public final class PopulationStatsExporter {

    private PopulationStatsExporter() {
        // static utility class
    }

    /** Export one gating pass's population table to {@code file}, single-image. */
    public static void export(File file, PopulationStats stats) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writeHeader(writer, false);
            writeRows(writer, stats, null);
        }
    }

    /**
     * Write the header line.
     *
     * @param withImage emit a leading {@code image} column — for a combined, multi-image
     *                  export where {@code image} is what tells one image's rows apart
     *                  from the next. Pass the same value here that every
     *                  {@link #writeRows} call after it agrees with: {@code imageName}
     *                  non-null there only when this is {@code true}.
     */
    public static void writeHeader(Writer w, boolean withImage) throws IOException {
        if (withImage) w.write("image,");
        w.write("scope,region,path,branch,gate_channel,depth,root_index,count,clean_count,"
                + "parent_count,clean_parent_count,denominator_count,percent_of_parent,"
                + "percent_of_total,percent_of_denominator,area_mm2,density_per_mm2\n");
    }

    /**
     * Write every row of {@code stats}, in {@link PopulationStats#rows()} order.
     * <p>
     * {@code root_index} is exported as its own column deliberately: two un-renamed root
     * gates on the same channel emit rows with byte-identical {@link
     * PopulationStats.Row#path()} values ({@code GateNode} names branches
     * {@code channel + "+"}/{@code channel + "-"} purely from the channel), so without this
     * column a reader could not tell such rows apart at all.
     *
     * @param imageName written into a leading {@code image} column when non-null, matching
     *                  a {@link #writeHeader} call with {@code withImage=true}; {@code null}
     *                  for a single-image export with no such column
     */
    public static void writeRows(Writer w, PopulationStats stats, String imageName) throws IOException {
        for (PopulationStats.Row row : stats.rows()) {
            if (imageName != null) {
                w.write(CellTable.escape(imageName));
                w.write(',');
            }
            w.write(row.scope().name());
            w.write(',');
            w.write(CellTable.escape(row.regionName() != null ? row.regionName() : ""));
            w.write(',');
            w.write(CellTable.escape(row.path()));
            w.write(',');
            w.write(CellTable.escape(row.branchName()));
            w.write(',');
            w.write(CellTable.escape(row.gateChannel()));
            w.write(',');
            w.write(String.valueOf(row.depth()));
            w.write(',');
            w.write(String.valueOf(row.rootIndex()));
            w.write(',');
            w.write(String.valueOf(row.count()));
            w.write(',');
            w.write(String.valueOf(row.cleanCount()));
            w.write(',');
            w.write(String.valueOf(row.parentCount()));
            w.write(',');
            w.write(String.valueOf(row.cleanParentCount()));
            w.write(',');
            w.write(String.valueOf(row.denominatorCount()));
            w.write(',');
            w.write(CellTable.fmt(row.percentOfParent()));
            w.write(',');
            w.write(CellTable.fmt(row.percentOfTotal()));
            w.write(',');
            w.write(CellTable.fmt(row.percentOfDenominator()));
            w.write(',');
            w.write(CellTable.fmt(row.areaMm2()));
            w.write(',');
            w.write(CellTable.fmt(row.densityPerMm2()));
            w.write('\n');
        }
    }
}
