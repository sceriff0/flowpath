package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortSession.SlideRef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SlideChoicesTest {

    @Test
    void uniqueNamesAreShownAsTheyAre() {
        Map<String, String> m = SlideChoices.labels(List.of(new SlideRef("1", "a.tif"), new SlideRef("2", "b.tif")));
        assertEquals(List.of("a.tif", "b.tif"), List.copyOf(m.keySet()));
        assertEquals("2", m.get("b.tif"));
    }

    @Test
    void sharedNamesCarryTheirIdAndMapBackToTheRightSlide() {
        Map<String, String> m = SlideChoices.labels(List.of(
                new SlideRef("1", "slide.tif"), new SlideRef("2", "other.tif"), new SlideRef("3", "slide.tif")));
        assertEquals(List.of("slide.tif (1)", "other.tif", "slide.tif (3)"), List.copyOf(m.keySet()));
        assertEquals("3", m.get("slide.tif (3)"), "the second of two same-named slides is itself, not the first");
        assertEquals("slide.tif (3)", SlideChoices.labelOf(m, "3"));
        assertNull(SlideChoices.labelOf(m, "9"));
        assertNull(SlideChoices.labelOf(m, null));
    }
}
