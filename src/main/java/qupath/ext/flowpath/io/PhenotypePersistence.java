package qupath.ext.flowpath.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.ext.flowpath.engine.ReconciliationController;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.model.Provenance;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persist manual reconciliation edits and carry them forward across add_cycle (Decision 13). */
public final class PhenotypePersistence {

    private PhenotypePersistence() {}

    public static String cellId(String patientId, CellPhenotype cell) {
        return patientId + ":" + cell.getLabel();
    }

    public static void saveManual(File file, String patientId, Collection<CellPhenotype> cells) throws IOException {
        JsonObject root = new JsonObject();
        for (CellPhenotype c : cells) {
            if (c.getProvenance() == Provenance.MANUAL && c.getCommitted() != null) {
                root.addProperty(cellId(patientId, c), c.getCommitted());
            }
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            gson.toJson(root, w);
        }
    }

    public static Map<String, String> loadManual(File file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Reader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (var e : root.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return out;
    }

    /** Result of carrying manual edits forward: how many were kept + which cells re-surface into the queue. */
    public record ReattachResult(int kept, List<CellPhenotype> resurfaced) {}

    /**
     * Re-attach prior manual edits to the new cycle's cells by stable id. Keep an edit only
     * when the manual phenotype is still a candidate; otherwise the new markers contradict it,
     * so re-surface the cell (spec §7.5).
     */
    public static ReattachResult reattach(Map<String, String> priorManual,
                                          Collection<CellPhenotype> current, String patientId) {
        int kept = 0;
        List<CellPhenotype> resurfaced = new ArrayList<>();
        for (CellPhenotype c : current) {
            String manual = priorManual.get(cellId(patientId, c));
            if (manual == null) continue;
            if (c.candidateNames().contains(manual)) {
                // package-visible setters live in the model package; use the controller to apply.
                new ReconciliationController(new PhenotypeTree()).commit(c, manual);
                kept++;
            } else {
                resurfaced.add(c);
            }
        }
        return new ReattachResult(kept, resurfaced);
    }
}
