package qupath.ext.flowpath.ui.cohort;

import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortIdentity;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.CohortState;
import qupath.ext.flowpath.cohort.ColumnDiagnostics;
import qupath.ext.flowpath.cohort.ReferenceRanking;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.cohort.ReviewScorer;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.model.cohort.UniformShift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Cohort window shows, derived — never set (CLAUDE.md "UI state is derived"). Pure: no
 * JavaFX. Rows are the reference slide first, then the project's slides in project order; columns
 * the enabled gates in tree order, keyed by value {@code (rootIndex, gatePath)}, never by a
 * {@code GateNode} or a {@code ReviewItem}'s gate (the review was scored on a deep copy of the
 * tree). Every cell carries its own text and tooltip (design spec §4, U6), so the pane decides
 * nothing about what a mark looks like.
 */
public record CohortGridModel(Banner banner, List<Column> columns, List<Row> rows, Detail detail,
                              List<String> missingChannels, List<LegendEntry> legend) {

    /** A cell's mark, in the design spec §4 order (which is also the legend's order after the flags). */
    public enum CellMark {
        LOOK("needs a look"),
        OK("automatically corrected by this factor"),
        MANUAL_PEAK("corrected from a hand-picked peak"),
        REFERENCE("the reference row"),
        REVIEWED("you confirmed it"),
        ADJUSTED("this slide has its own threshold"),
        SKIPPED("skipped; its cells are unmeasured"),
        NOT_MEASURED("the marker is not on this slide"),
        NOT_CORRECTED("not corrected"),
        NONE("");

        private final String meaning;

        CellMark(String meaning) { this.meaning = meaning; }

        /** What the mark means, as the spec §4 table says it; the legend's label and the tooltip's first line. */
        public String meaning() { return meaning; }
    }

    public enum RowStatus { READY, SAMPLING, FAILED, EXCLUDED }

    public record Column(int rootIndex, String gatePath, String header) {}

    /**
     * One slide × gate cell. {@code flags} are the review item's, most serious first, and empty
     * unless the mark is {@link CellMark#LOOK}.
     */
    public record Cell(CellMark mark, String text, String tooltip, List<ReviewItem.Flag> flags) {
        public Cell { flags = List.copyOf(flags); }
    }

    /** One glyph on screen and what it means. */
    public record LegendEntry(String glyph, String label) {}

    /**
     * One slide. {@code selectedColumn} is the index into {@link #columns()} of the selected cell
     * when it is on this row, else -1: the selection is carried by the model (a value), so the
     * grid highlights the cell the detail describes after N / P or an answer moved it. {@code open}
     * says the slide is the one open in the viewer.
     */
    public record Row(String slideId, String name, boolean reference, boolean open, RowStatus status, String statusText,
                      int cellCount, List<Cell> cells, int lookCount, boolean canExclude, boolean canBeReference,
                      int selectedColumn) {
        public Row { cells = List.copyOf(cells); }
    }

    public record Banner(String headline, String suggestedId, String suggestedName, List<String> notes) {
        public Banner { notes = List.copyOf(notes); }
    }

    /**
     * What the detail pane's histogram draws, for the selected cell's axis-0 column. Everything is
     * on {@code scale}'s log axis; a value that does not exist is NaN, and an array is null when
     * that slide has no histogram for the column.
     */
    public record HistogramView(double gridMin, double gridMax, long[] reference, long[] slide,
                                double referenceL1, double slideL1, double pickedSlidePeak, double pickedReferencePeak,
                                double referenceThreshold, double appliedThreshold, LogScale scale) {}

    /** The selected cell, every line labelled so the numbers read without the grid beside them. */
    public record Detail(ReviewItem.Key key, String title, CellMark mark, List<String> reasons,
                         String valuesLine, String correctionLine, String usageLine, HistogramView histogram,
                         boolean canPickPeak, boolean hasPickedPeak, boolean hasPickedReferencePeak,
                         boolean region) {
        public Detail { reasons = List.copyOf(reasons); }

        /** With no reference pick stored for the column. */
        public Detail(ReviewItem.Key key, String title, CellMark mark, List<String> reasons, String valuesLine,
                      String correctionLine, String usageLine, HistogramView histogram, boolean canPickPeak,
                      boolean hasPickedPeak, boolean region) {
            this(key, title, mark, reasons, valuesLine, correctionLine, usageLine, histogram, canPickPeak,
                    hasPickedPeak, false, region);
        }
    }

    public static final String SCOPE_NOTE = "correction assumes positive/negative markers, not graded intensity";
    public static final String REALIGNING = " · re-aligning…";
    /** The reference row's correction line (ruling R7): the reference is what every other slide is corrected onto. */
    public static final String REFERENCE_CORRECTION = "reference slide — its thresholds are the ones you draw";

    public CohortGridModel {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        missingChannels = missingChannels == null ? List.of() : List.copyOf(missingChannels);
        legend = legend == null ? List.of() : List.copyOf(legend);
    }

    /** A model with no footer notes and no legend. */
    public CohortGridModel(Banner banner, List<Column> columns, List<Row> rows, Detail detail) {
        this(banner, columns, rows, detail, List.of(), List.of());
    }

    /** A model with no legend. */
    public CohortGridModel(Banner banner, List<Column> columns, List<Row> rows, Detail detail,
                           List<String> missingChannels) {
        this(banner, columns, rows, detail, missingChannels, List.of());
    }

    /**
     * @param openSlideId the slide open in the viewer, or null; its row is {@link Row#open}
     * @param rescoring   a rescore is in flight: the headline says the cells are about to change
     */
    public static CohortGridModel derive(CohortSession session, GateTree tree, ReviewItem.Key selected,
                                         boolean onlyLooks, String openSlideId, boolean rescoring) {
        List<GateWalk.Entry> entries = GateWalk.enabled(tree);
        List<Column> columns = columns(entries);
        String reference = shownReference(session, tree);
        ReviewScorer.Result review = currentReview(session, tree);
        Map<String, ReviewItem> items = new HashMap<>();
        for (ReviewItem i : review.items()) items.put(keyString(i.key()), i);

        List<Row> rows = new ArrayList<>();
        Row referenceRow = null;
        for (CohortSession.SlideSquare sq : session.slideStrip()) {
            RowStatus status = switch (sq.status()) {
                case EXCLUDED -> RowStatus.EXCLUDED;
                case FAILED -> RowStatus.FAILED;
                case SAMPLING -> RowStatus.SAMPLING;
                case READY, NEEDS_LOOK -> RowStatus.READY;
            };
            String statusText = switch (status) {
                case EXCLUDED -> "excluded";
                case FAILED -> "failed: " + sq.failure();
                case SAMPLING -> "sampling…";
                case READY -> "";
            };
            List<Cell> cells = new ArrayList<>();
            int looks = 0;
            int selectedColumn = -1;
            for (int c = 0; c < entries.size(); c++) {
                GateWalk.Entry e = entries.get(c);
                Cell cell = status != RowStatus.READY ? EMPTY : cell(session, e, sq.slideId(), reference, items);
                if (cell.mark() == CellMark.LOOK) looks++;
                cells.add(cell);
                if (selected != null && selected.slideId().equals(sq.slideId())
                        && selected.rootIndex() == e.rootIndex() && selected.gatePath().equals(e.gatePath())) {
                    selectedColumn = c;
                }
            }
            boolean isRef = sq.slideId().equals(reference);
            Row row = new Row(sq.slideId(), sq.name(), isRef, sq.slideId().equals(openSlideId), status, statusText,
                    sq.cells(), cells, looks, !isRef, canBeReference(status, isRef, tree.getReferenceSlideId()),
                    selectedColumn);
            if (onlyLooks && looks == 0) continue;
            if (isRef) referenceRow = row;
            else rows.add(row);
        }
        if (referenceRow != null) rows.add(0, referenceRow);
        Banner banner = banner(session, tree);
        if (rescoring) banner = new Banner(banner.headline() + REALIGNING, banner.suggestedId(),
                banner.suggestedName(), banner.notes());
        return new CohortGridModel(banner, columns, rows, detail(session, tree, entries, selected, items),
                missingChannels(review), legend(rows));
    }

    /**
     * The reference row the grid marks: the tree's reference, or none for a tree from another
     * project — entry ids restart in every project, so its id names an unrelated slide here, which
     * must not be starred, sorted first or described as the reference (final review M2).
     */
    private static String shownReference(CohortSession session, GateTree tree) {
        return CohortIdentity.matches(tree, session.projectNames()) ? tree.getReferenceSlideId() : null;
    }

    /**
     * ☆ is offered on a ready row, and — while the tree has no reference — on a row still being
     * sampled too (spec §8): confirming a reference needs no sample. A rebase does (it re-expresses
     * every threshold through the current reference's alignments), so it waits for READY.
     */
    static boolean canBeReference(RowStatus status, boolean isReference, String reference) {
        if (isReference) return false;
        return status == RowStatus.READY || (reference == null && status == RowStatus.SAMPLING);
    }

    /**
     * How many review items the card counts: the review the grid itself trusts
     * ({@link #currentReview}, so a review scored for another reference counts none).
     */
    public static int toLookAt(CohortSession session, GateTree tree) {
        return currentReview(session, tree).items().size();
    }

    /**
     * The side card's line (spec §3.1). No reference and a suggestion: the prompt. A settled
     * cohort with a reference: {@code Cohort · 4 slides · ★ slide_A · 4 to look at}. Otherwise —
     * sampling, a batch running, correction off, no reference and no suggestion — the session's
     * status line, which says why.
     */
    public static String cardLine(CohortSession session, GateTree tree, boolean runAllowed) {
        CohortState state = session.state();
        String suggested = session.suggestedReferenceId();
        if (tree.getReferenceSlideId() == null && suggested != null) {
            return "Pick a reference slide — suggested: " + session.slideName(suggested);
        }
        if (state.available() && state.referenceName() != null && !state.sampling() && !state.batchRunning()
                && !state.correctionDisabled()) {
            int included = (int) session.projectSlides().stream()
                    .filter(r -> !session.excluded().contains(r.id())).count();
            return String.format(Locale.US, "Cohort · %d slides · ★ %s · %d to look at", included,
                    state.referenceName(), toLookAt(session, tree));
        }
        return session.statusLine(runAllowed);
    }

    /**
     * The footer's "channels missing on some slides" notes, one per slide × message, from the
     * review the grid trusts; empty when there are none.
     */
    private static List<String> missingChannels(ReviewScorer.Result review) {
        List<String> out = new ArrayList<>();
        for (ReviewItem.Info info : review.infos()) {
            String line = info.slideName() + " — " + info.message();
            if (!out.contains(line)) out.add(line);
        }
        return out;
    }

    /**
     * The review, only while it was scored for the live tree's reference — the condition
     * {@code CohortSession} itself applies before answering alignments. After a reference change
     * and before the rescore lands, its flags describe a correction that is no longer applied.
     */
    private static ReviewScorer.Result currentReview(CohortSession session, GateTree tree) {
        String ref = tree.getReferenceSlideId();
        var model = session.model();
        boolean current = ref != null && model != null && ref.equals(model.referenceSlideId());
        return current ? session.review() : new ReviewScorer.Result(List.of(), List.of());
    }

    private static List<Column> columns(List<GateWalk.Entry> entries) {
        Map<String, Integer> perPath = new HashMap<>();
        for (GateWalk.Entry e : entries) perPath.merge(e.gatePath(), 1, Integer::sum);
        List<Column> out = new ArrayList<>();
        for (GateWalk.Entry e : entries) {
            String header = perPath.get(e.gatePath()) > 1
                    ? "#" + (e.rootIndex() + 1) + " " + e.gatePath() : e.gatePath();
            out.add(new Column(e.rootIndex(), e.gatePath(), header));
        }
        return out;
    }

    private static String keyString(ReviewItem.Key k) {
        return k.slideId() + "\u0000" + k.rootIndex() + "\u0000" + k.gatePath();
    }

    private static final Cell EMPTY = new Cell(CellMark.NONE, "", "", List.of());

    /**
     * The mark of one slide x gate cell. Slide settings win, then a review flag, then "not
     * measured", then a confirmation; only then is the cell about its correction: none without a
     * reference, the reference row itself, Correct staining off or an axis left uncorrected, a
     * hand-picked peak on any axis, else automatic.
     */
    static CellMark mark(CohortSession session, GateWalk.Entry e, String slideId, String reference,
                         Map<String, ReviewItem> items) {
        GateNode gate = e.gate();
        SlideSetting setting = gate.slideSetting(slideId);
        if (setting instanceof SlideSetting.Skip) return CellMark.SKIPPED;
        if (setting instanceof SlideSetting.Manual) return CellMark.ADJUSTED;
        if (items.containsKey(keyString(new ReviewItem.Key(slideId, e.rootIndex(), e.gatePath())))) {
            return CellMark.LOOK;
        }
        // By value, never by ReviewItem's gate: the review was scored on a deep copy, so its
        // GateNodes are not the live tree's. The slide's own index says whether it carries the channel.
        var sample = session.sample(slideId);
        if (sample != null) {
            if (!measured(gate, sample.index())) return CellMark.NOT_MEASURED;
        }
        if (setting instanceof SlideSetting.Reviewed) return CellMark.REVIEWED;
        if (reference == null) return CellMark.NOT_CORRECTED;
        if (slideId.equals(reference)) return CellMark.REFERENCE;
        if (!gate.isCorrectStaining()) return CellMark.NOT_CORRECTED;
        boolean landmark = false;
        for (Alignment a : axisAlignments(session, gate, slideId)) {
            if (a == null || a.kind() == Alignment.Kind.IDENTITY) return CellMark.NOT_CORRECTED;
            if (a.kind() == Alignment.Kind.LANDMARK) landmark = true;
        }
        return landmark ? CellMark.MANUAL_PEAK : CellMark.OK;
    }

    /** Each axis's alignment on {@code slideId} through the session's guarded lookup; null where unknown. */
    private static List<Alignment> axisAlignments(CohortSession session, GateNode gate, String slideId) {
        List<String> channels = gate.getChannels();
        List<Alignment> out = new ArrayList<>();
        for (int k = 0; k < GateAxis.axisCount(gate) && k < channels.size(); k++) {
            String key = columnKey(gate, k);
            out.add(key == null ? null : session.lookup().alignment(slideId, key));
        }
        return out;
    }

    /** The column key of {@code gate}'s axis {@code k}, as the alignment model keys it; null without a channel. */
    private static String columnKey(GateNode gate, int k) {
        List<String> channels = gate.getChannels();
        String ch = k < channels.size() ? channels.get(k) : null;
        if (ch == null || ch.isEmpty()) return null;
        return new AlignmentModel.ColumnRef(ch, gate.compartmentAt(k), gate.statisticAt(k)).key();
    }

    private static Cell cell(CohortSession session, GateWalk.Entry e, String slideId, String reference,
                             Map<String, ReviewItem> items) {
        CellMark mark = mark(session, e, slideId, reference, items);
        ReviewItem item = mark == CellMark.LOOK
                ? items.get(keyString(new ReviewItem.Key(slideId, e.rootIndex(), e.gatePath()))) : null;
        List<ReviewItem.Flag> flags = item == null ? List.of() : item.flags();
        // A factor is shown only where it is applied: a LOOK or confirmed cell is still corrected.
        List<Alignment> alignments = switch (mark) {
            case OK, MANUAL_PEAK, LOOK, REVIEWED -> slideId.equals(reference) || !e.gate().isCorrectStaining()
                    ? List.of() : axisAlignments(session, e.gate(), slideId);
            default -> List.of();
        };
        double factor = alignments.isEmpty() || alignments.get(0) == null ? Double.NaN : alignments.get(0).factor();
        return new Cell(mark, cellText(mark, flags, factor), tooltip(mark, item, e.gate(), alignments), flags);
    }

    /**
     * A cell's text (spec §4): a LOOK cell shows its most serious flag's glyph and {@code +n} for
     * the rest; a corrected cell its factor (axis 0's on a 2D gate).
     */
    static String cellText(CellMark mark, List<ReviewItem.Flag> flags, double factor) {
        return switch (mark) {
            case LOOK -> flags.isEmpty() ? "" : flags.get(0).glyph() + (flags.size() > 1 ? "+" + (flags.size() - 1) : "");
            case OK -> factorText(factor);
            case MANUAL_PEAK -> MANUAL_GLYPH + factorText(factor);
            default -> glyphOf(mark);
        };
    }

    private static final String FACTOR_GLYPH = "×";
    private static final String MANUAL_GLYPH = "◆";

    private static String factorText(double factor) {
        return String.format(Locale.US, FACTOR_GLYPH + "%.2f", factor);
    }

    /** The glyph that stands for {@code mark} in a cell and in the legend. */
    private static String glyphOf(CellMark mark) {
        return switch (mark) {
            case LOOK, NONE -> "";
            case OK -> FACTOR_GLYPH;
            case MANUAL_PEAK -> MANUAL_GLYPH;
            case REFERENCE -> "★";
            case REVIEWED -> "☑";
            case ADJUSTED -> "✎";
            case SKIPPED -> "⊘";
            case NOT_MEASURED -> "n/a";
            case NOT_CORRECTED -> "raw";
        };
    }

    /**
     * The mark's meaning, then each flag as {@code glyph label — reason (source)}, then each
     * corrected axis's factor and how it was found.
     */
    private static String tooltip(CellMark mark, ReviewItem item, GateNode gate, List<Alignment> alignments) {
        if (mark == CellMark.NONE) return "";
        List<String> lines = new ArrayList<>();
        lines.add(capitalised(mark.meaning()));
        if (item != null) {
            for (ReviewItem.Flag f : item.flags()) {
                List<String> why = item.reasonsFor(f);
                lines.add(f.glyph() + " " + f.label() + (why.isEmpty() ? "" : " — " + String.join("; ", why))
                        + " (" + f.source() + ")");
            }
        }
        boolean twoAxes = alignments.size() > 1;
        for (int k = 0; k < alignments.size(); k++) {
            Alignment a = alignments.get(k);
            if (a == null || a.kind() == Alignment.Kind.IDENTITY) continue;
            String how = a.kind() == Alignment.Kind.LANDMARK ? "from picked peak" : "automatic";
            lines.add("Factor " + factorText(a.factor()) + " (" + how + ")"
                    + (twoAxes ? " on " + gate.getChannels().get(k) : ""));
        }
        return String.join("\n", lines);
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * One entry per glyph on screen (U6): the most serious flag of each LOOK cell in severity
     * order, then each other mark in spec §4 order. A glyph no visible cell shows is not listed.
     */
    private static List<LegendEntry> legend(List<Row> rows) {
        java.util.EnumSet<ReviewItem.Flag> flags = java.util.EnumSet.noneOf(ReviewItem.Flag.class);
        java.util.EnumSet<CellMark> marks = java.util.EnumSet.noneOf(CellMark.class);
        for (Row r : rows) {
            for (Cell c : r.cells()) {
                if (c.mark() == CellMark.LOOK) {
                    if (!c.flags().isEmpty()) flags.add(c.flags().get(0));
                } else if (c.mark() != CellMark.NONE) {
                    marks.add(c.mark());
                }
            }
        }
        List<LegendEntry> out = new ArrayList<>();
        for (ReviewItem.Flag f : flags) out.add(new LegendEntry(f.glyph(), f.label()));
        for (CellMark m : marks) out.add(new LegendEntry(glyphOf(m), m.meaning()));
        return out;
    }

    /** ReviewScorer's rule: every axis needs a non-null channel the slide's index carries. */
    private static boolean measured(GateNode gate, qupath.ext.flowpath.model.CellIndex index) {
        List<String> channels = gate.getChannels();
        for (int k = 0; k < GateAxis.axisCount(gate); k++) {
            String ch = k < channels.size() ? channels.get(k) : null;
            if (ch == null || index.getMarkerIndex(ch) < 0) return false;
        }
        return true;
    }

    private static Banner banner(CohortSession session, GateTree tree) {
        CohortState state = session.state();
        ReferenceRanking.Result ranking = session.ranking();
        List<String> notes = new ArrayList<>();
        String suggestedId = session.suggestedReferenceId();
        String suggestedName = suggestedId == null ? null : session.slideName(suggestedId);
        String reference = tree.getReferenceSlideId();
        String headline;
        if (state.correctionDisabled() && state.message() != null) {
            headline = state.message();
        } else if (reference == null) {
            headline = "No reference slide — thresholds are not corrected between slides.";
            if (suggestedId != null) notes.add("Suggested: " + suggestedName + " — " + ranking.reason());
            else if (state.sampling()) notes.add("Ranking once sampling finishes");
            else if (ranking.eligibleCount() < ReferenceRanking.MIN_ELIGIBLE) {
                notes.add("With fewer than three eligible slides there is no suggestion; "
                        + "pick the slide you know best");
            }
            for (String c : ranking.uncorrectableColumns()) {
                notes.add(c + ": no slide has a clear negative peak — " + c + " is not corrected");
            }
        } else {
            String name = session.slideName(reference);
            int pos = ranking.position(reference);
            long of = ranking.eligibleCount();
            headline = "★ " + name + (pos == 1 ? String.format(Locale.US, " · most central of %d", of)
                    : pos > 1 ? String.format(Locale.US, " · ranks %d of %d", pos, of) : "");
            notes.addAll(ranking.notesFor(reference, session::slideName));
            if (suggestedId != null) notes.add("Suggested: " + suggestedName + " — " + ranking.reason());
        }
        notes.add(SCOPE_NOTE);
        return new Banner(headline, suggestedId, suggestedName, notes);
    }

    private static Detail detail(CohortSession session, GateTree tree, List<GateWalk.Entry> entries,
                                 ReviewItem.Key selected, Map<String, ReviewItem> items) {
        if (selected == null) return null;
        GateWalk.Entry entry = null;
        for (GateWalk.Entry e : entries) {
            if (e.rootIndex() == selected.rootIndex() && e.gatePath().equals(selected.gatePath())) entry = e;
        }
        if (entry == null) return null;
        GateNode gate = entry.gate();
        String slideId = selected.slideId();
        String reference = shownReference(session, tree);
        ReviewItem item = items.get(keyString(selected));
        CellMark mark = mark(session, entry, slideId, reference, items);
        TreeResolver.Applied applied = TreeResolver.resolve(tree, slideId, session.lookup()).applied(gate);
        String title = session.slideName(slideId) + " · " + entry.gatePath();
        List<String> reasons = new ArrayList<>();
        if (item != null) reasons.addAll(item.reasons());
        if (mark == CellMark.NOT_MEASURED) {
            // By value (slide + the gate's channels), never by Info.gate() identity.
            for (ReviewItem.Info info : currentReview(session, tree).infos()) {
                if (info.slideId().equals(slideId) && info.gate().getChannels().equals(gate.getChannels())) {
                    // Two roots on one channel yield the same message; say it once.
                    if (!reasons.contains(info.message())) reasons.add(info.message());
                }
            }
        }
        String values = applied == null ? ""
                : gate instanceof Region2DGate ? "region"
                : "reference " + format(applied.reference()) + " → this slide " + format(applied.applied());

        AlignmentModel model = currentModel(session, tree);
        String column = columnKey(gate, 0);
        ColumnDiagnostics d = model == null || column == null ? null : model.diagnostics(slideId, column);
        Alignment correction = TreeResolver.correctionFor(tree, gate, 0, slideId, session.lookup());
        boolean canPick = gate.isCorrectStaining() && reference != null && !slideId.equals(reference)
                && model != null && column != null && model.grid(column) != null;
        String unused = d == null || !gate.isCorrectStaining() ? null
                : ReviewScorer.pickUnusedReason(d.pickProblem(), model.scale(), gate.getChannels().get(0));
        String correctionLine = reference != null && slideId.equals(reference) ? REFERENCE_CORRECTION
                : correctionLine(correction) + (unused == null ? "" : " \u2014 " + unused);
        // The stored raw picks decide the clear buttons, never their log values: a pick that reads
        // NaN on this scale still moves the alignment (or stops it), and must be clearable.
        return new Detail(selected, title, mark, reasons, values, correctionLine,
                model == null ? "" : usageLine(d, model.scale()),
                histogram(session, model, column, slideId, reference, gate, applied),
                canPick, storedPick(session, slideId, column), storedPick(session, reference, column),
                gate instanceof Region2DGate);
    }

    /** {@code ×0.82 · automatic (UniFORM)}, {@code ×0.82 · from your picked peak (UniFORM landmark mode)} or {@code not corrected}. */
    static String correctionLine(Alignment a) {
        if (a == null || a.kind() == Alignment.Kind.IDENTITY) return "not corrected";
        return factorText(a.factor()) + (a.kind() == Alignment.Kind.LANDMARK
                ? " · from your picked peak (UniFORM landmark mode)" : " · automatic (UniFORM)");
    }

    /**
     * {@code 9,458 of 9,870 cells used · 412 below 1 not used to estimate the shift; corrected like
     * the rest}: the clean cells inside the scale's domain of all clean cells with a value, and those
     * outside it (below 1 on ln, below 0 on ln(x + 1)); {@code ""} when nothing was aligned.
     */
    static String usageLine(ColumnDiagnostics d, LogScale scale) {
        if (d == null) return "";
        int seen = d.usable() + d.outsideDomain();
        String line = String.format(Locale.US, "%,d of %,d cells used", d.usable(), seen);
        if (d.outsideDomain() > 0) {
            line += String.format(Locale.US, " · %,d below %s not used to estimate the shift; corrected like the rest",
                    d.outsideDomain(), scale == LogScale.LN1P ? "0" : "1");
        }
        return line;
    }

    /** Axis 0's column on the shared grid: both histograms, landmarks, picks and thresholds, on the log axis. */
    private static HistogramView histogram(CohortSession session, AlignmentModel model, String column, String slideId,
                                           String reference, GateNode gate, TreeResolver.Applied applied) {
        if (model == null || column == null) return null;
        UniformShift.Grid grid = model.grid(column);
        if (grid == null) return null;
        LogScale scale = model.scale();
        Landmarks refLm = model.referenceLandmarks(column);
        Landmarks lm = model.landmarks(slideId, column);
        boolean cut = applied != null && !(gate instanceof Region2DGate);
        return new HistogramView(grid.min(), grid.max(),
                reference == null ? null : model.histogram(reference, column), model.histogram(slideId, column),
                refLm == null ? Double.NaN : refLm.l1(), lm == null ? Double.NaN : lm.l1(),
                pickedLog(session, slideId, column, scale), pickedLog(session, reference, column, scale),
                cut ? scale.toLog(applied.reference().axis(0)[0]) : Double.NaN,
                cut ? scale.toLog(applied.applied().axis(0)[0]) : Double.NaN, scale);
    }

    /** Whether {@code slideId} has a hand-picked peak stored for {@code column}, whatever the scale reads it as. */
    private static boolean storedPick(CohortSession session, String slideId, String column) {
        if (slideId == null || column == null) return false;
        Map<String, Double> m = session.peaks().get(slideId);
        return m != null && m.get(column) != null;
    }

    private static double pickedLog(CohortSession session, String slideId, String column, LogScale scale) {
        if (slideId == null) return Double.NaN;
        Map<String, Double> m = session.peaks().get(slideId);
        Double raw = m == null ? null : m.get(column);
        return raw == null ? Double.NaN : scale.toLog(raw);
    }

    /** The alignment model, only while it was built for the live tree's reference (as {@link #currentReview}). */
    private static AlignmentModel currentModel(CohortSession session, GateTree tree) {
        String ref = tree.getReferenceSlideId();
        AlignmentModel model = session.model();
        return ref != null && model != null && ref.equals(model.referenceSlideId()) ? model : null;
    }

    /** Each axis's values joined with ", ", the axes joined with " / ". */
    private static String format(GateValues v) {
        List<String> axes = new ArrayList<>();
        for (int k = 0; k < v.axisCount(); k++) {
            List<String> values = new ArrayList<>();
            for (double x : v.axis(k)) values.add(number(x));
            axes.add(String.join(", ", values));
        }
        return String.join(" / ", axes);
    }

    /**
     * Four significant digits, never scientific notation, whole numbers from 1000 up: a cut on a
     * [0, 1] or pre-standardised column (0.00412) keeps its shift visible, as does one at 1235.
     */
    static String number(double x) {
        if (!Double.isFinite(x)) return String.valueOf(x);
        if (x == 0) return "0";
        java.math.BigDecimal b = new java.math.BigDecimal(x);
        b = Math.abs(x) >= 1000 ? b.setScale(0, java.math.RoundingMode.HALF_UP)
                : b.round(new java.math.MathContext(4, java.math.RoundingMode.HALF_UP));
        return b.stripTrailingZeros().toPlainString();
    }
}
