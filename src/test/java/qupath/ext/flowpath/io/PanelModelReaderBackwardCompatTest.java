package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class PanelModelReaderBackwardCompatTest {

    @Test
    void absentSidecarYieldsEmptyOptionalWithoutThrowing() {
        Optional<PhenotypeTree> t = PanelModelReader.tryRead(new File("/no/such/panel_model.json"));
        assertTrue(t.isEmpty());
        assertTrue(PanelModelReader.tryRead(null).isEmpty());
    }

    @Test
    void presentSidecarYieldsTree() throws Exception {
        File f = File.createTempFile("panel_model", ".json");
        Files.writeString(f.toPath(),
                "{\"phenotypes\":[{\"name\":\"Tumour\",\"parent\":null,\"signature\":{},\"color\":[128,64,0],\"is_leaf\":true}]}");
        Optional<PhenotypeTree> t = PanelModelReader.tryRead(f);
        assertTrue(t.isPresent());
        assertNotNull(t.get().findByName("Tumour"));
    }
}
