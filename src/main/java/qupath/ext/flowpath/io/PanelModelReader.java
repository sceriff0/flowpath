package qupath.ext.flowpath.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.ConstraintEntry;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parses the {@code panel_model.json} sidecar into a {@link PhenotypeTree} (Gson). */
public final class PanelModelReader {

    private PanelModelReader() {}

    public static PhenotypeTree read(File file) throws IOException {
        try (Reader r = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            return read(r);
        }
    }

    public static PhenotypeTree read(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        PhenotypeTree tree = new PhenotypeTree();

        // 1. Nodes (flat list with parent refs) -> register all, then wire children.
        Map<String, PhenotypeNode> built = new LinkedHashMap<>();
        List<JsonObject> raw = new ArrayList<>();
        if (root.has("phenotypes")) {
            for (JsonElement el : root.getAsJsonArray("phenotypes")) {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                String parent = (o.has("parent") && !o.get("parent").isJsonNull())
                        ? o.get("parent").getAsString() : null;
                Map<String, Integer> sig = new LinkedHashMap<>();
                if (o.has("signature")) {
                    for (var e : o.getAsJsonObject("signature").entrySet()) {
                        sig.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
                int color = o.has("color") ? ColorUtils.fromJsonArray(o.getAsJsonArray("color")) : 0x808080;
                boolean leaf = o.has("is_leaf") && o.get("is_leaf").getAsBoolean();
                PhenotypeNode node = new PhenotypeNode(name, parent, sig, color, leaf);
                built.put(name, node);
                tree.register(node);
                raw.add(o);
            }
            for (JsonObject o : raw) {
                String name = o.get("name").getAsString();
                String parent = (o.has("parent") && !o.get("parent").isJsonNull())
                        ? o.get("parent").getAsString() : null;
                if (parent == null) tree.addRoot(built.get(name));
                else if (built.containsKey(parent)) built.get(parent).addChild(built.get(name));
            }
        }

        // 2. Reserved palette.
        if (root.has("palette")) {
            for (var e : root.getAsJsonObject("palette").entrySet()) {
                if (e.getValue().isJsonArray()) {
                    tree.setReservedColor(e.getKey(), ColorUtils.fromJsonArray(e.getValue().getAsJsonArray()));
                }
            }
        }

        // 3. Constraint table: flat list of {id, markers, kind, rate}, exactly as
        //    mirage's export_geojson writes it. `rate` is the display word
        //    (never|rare|soft|requires) shown in a Conflict's label; `requires`
        //    entries already carry markers as [if, then], so one uniform loop.
        if (root.has("constraint_table")) {
            for (JsonElement el : root.getAsJsonArray("constraint_table")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;
                List<String> markers = new ArrayList<>();
                for (JsonElement m : o.getAsJsonArray("markers")) markers.add(m.getAsString());
                String kind = o.has("rate") ? o.get("rate").getAsString()
                        : (o.has("kind") ? o.get("kind").getAsString() : "?");
                double rate = o.has("r") ? o.get("r").getAsDouble() : 0.0;
                tree.addConstraint(new ConstraintEntry(o.get("id").getAsInt(), markers, kind, rate));
            }
        }
        return tree;
    }

    /** Feature-detect the sidecar: absent/unreadable → Optional.empty() (degrade to gating-only). */
    public static java.util.Optional<qupath.ext.flowpath.model.PhenotypeTree> tryRead(File sidecar) {
        if (sidecar == null || !sidecar.exists()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(read(sidecar));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
