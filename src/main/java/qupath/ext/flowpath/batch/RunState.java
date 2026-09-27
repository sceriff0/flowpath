package qupath.ext.flowpath.batch;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.SlideSetting;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code <outDir>/.flowpath-run.json}: which slides of a run are done, and under exactly what —
 * so a run that died at slide 37 of 40 resumes at 37 (spec §7 "Resumable"). Rewritten atomically
 * after every slide, so a crash leaves either the old file or the new one, never half of one.
 * <p>
 * A slide is recorded only once all of its outputs are written <em>and</em> its phenotypes are in
 * its data file (see {@link FlowPathBatch#step}); a slide whose write-back was skipped or failed is
 * left unrecorded, so the next run still owes it and does it.
 */
public final class RunState {

    private static final Logger logger = LoggerFactory.getLogger(RunState.class);

    public static final String FILE = ".flowpath-run.json";

    /** One done slide: what it was run under, the stem of its files, and what the report needs from it. */
    public record Entry(String fingerprint, String fileBase, int cells, List<String> markers, List<String> sanity) {
        public Entry {
            markers = List.copyOf(markers);
            sanity = List.copyOf(sanity);
        }
    }

    private final Path file;
    private final Map<String, Entry> entries;

    private RunState(Path file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    /** The state recorded in {@code outDir}; empty when there is none or it cannot be read ("nothing is done"). */
    public static RunState load(File outDir) {
        Path file = outDir.toPath().resolve(FILE);
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject slides = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("slides");
                for (var e : slides.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    entries.put(e.getKey(), new Entry(o.get("fingerprint").getAsString(), o.get("fileBase").getAsString(),
                            o.get("cells").getAsInt(), strings(o.getAsJsonArray("markers")),
                            strings(o.getAsJsonArray("sanity"))));
                }
            } catch (IOException | RuntimeException corrupt) {
                // Every slide is simply run again: slower, never wrong.
                logger.warn("Ignoring unreadable run state {}; every slide will be run", file, corrupt);
                entries.clear();
            }
        }
        return new RunState(file, entries);
    }

    /**
     * Whether {@code slideId} was recorded under exactly {@code fingerprint} and {@code fileBase}, and
     * its three per-slide files are still there — a deleted output is re-made, not assumed.
     */
    public synchronized boolean isDone(String slideId, String fingerprint, File outDir, String fileBase) {
        Entry e = entries.get(slideId);
        return e != null && e.fingerprint().equals(fingerprint) && e.fileBase().equals(fileBase)
                && new File(outDir, fileBase + FlowPathBatch.PHENO_SUFFIX).isFile()
                && new File(outDir, fileBase + FlowPathBatch.POPULATIONS_SUFFIX).isFile()
                && new File(outDir, fileBase + FlowPathBatch.QC_SUFFIX).isFile();
    }

    public synchronized Entry entry(String slideId) {
        return entries.get(slideId);
    }

    /** Record {@code slideId} as done and rewrite the file atomically. */
    public synchronized void record(String slideId, Entry entry) throws IOException {
        entries.put(slideId, entry);
        JsonObject slides = new JsonObject();
        entries.forEach((id, e) -> {
            JsonObject o = new JsonObject();
            o.addProperty("fingerprint", e.fingerprint());
            o.addProperty("fileBase", e.fileBase());
            o.addProperty("cells", e.cells());
            o.add("markers", array(e.markers()));
            o.add("sanity", array(e.sanity()));
            slides.add(id, o);
        });
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("slides", slides);
        Path tmp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network file systems (a cluster's scratch space) cannot rename atomically.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * SHA-256 of what {@code slideId} is gated under: its resolved tree as canonical JSON, the
     * detection fingerprint, and the FlowPath version. The JSON is {@link FlowPathSerializer#toJson},
     * which carries no {@code meta} block — {@code meta.savedAt} would make a resume never match
     * (ruling B20) — and it is stripped of everything that belongs to other slides:
     * <ul>
     *   <li>every other slide's {@link SlideSetting}, so answering slide B never re-runs slide A;</li>
     *   <li>the tree's recorded slide names, which an answer on any slide extends
     *       ({@code ReviewAnswers}) — and which say nothing about how this slide is gated.</li>
     * </ul>
     * This slide's own setting stays: the resolved tree carries a {@code Skip} only as a transient
     * flag the JSON does not write, so its setting is what makes a new {@code Skip} change the hash.
     */
    public static String fingerprint(GateTree resolvedTree, String slideId, String detectionFingerprint, String version) {
        GateTree canonical = resolvedTree.deepCopy();
        canonical.setSlideNames(null);
        keepOnly(canonical.getRoots(), slideId);
        String text = FlowPathSerializer.toJson(canonical) + "\n" + detectionFingerprint + "\n" + version;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private static void keepOnly(List<GateNode> nodes, String slideId) {
        for (GateNode n : nodes) {
            SlideSetting own = n.slideSetting(slideId);
            for (String id : new ArrayList<>(n.getSlideSettings().keySet())) n.setSlideSetting(id, null);
            if (own != null) n.setSlideSetting(slideId, own);
            for (Branch b : n.getBranches()) keepOnly(b.getChildren(), slideId);
        }
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
}
