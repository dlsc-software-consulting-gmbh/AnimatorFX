package com.dlsc.animatorfx.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.StateMachine;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkersTest {

    private final Animation doc = Animation.create("doc", 100, 100, 3).withMarkers(
            List.of(new Marker("intro", 0, 0.5), new Marker("marker1", 1, 2)));

    @Test
    void newMarkersGetTheNextFreeNameAndTheDefaultLength() {
        Animation after = Markers.add(1.2).apply(doc);
        assertEquals(new Marker("marker2", 1.2, 1.7), after.markers().get(2));
        // near the end the marker is moved back so that it fits
        assertEquals(new Marker("marker3", 2.5, 3), Markers.add(2.9).apply(after).markers().get(3));
        assertEquals("Add Marker", Markers.add(0).name());
    }

    @Test
    void namesHaveToBeUniqueAndNotEmpty() {
        assertTrue(Markers.nameError(doc, "outro", "intro").isEmpty());
        assertTrue(Markers.nameError(doc, "intro", "intro").isEmpty());
        assertTrue(Markers.nameError(doc, "marker1", "intro").isPresent());
        assertTrue(Markers.nameError(doc, " ", "intro").isPresent());
        assertSame(doc, Markers.rename("intro", "marker1").apply(doc));
        assertEquals("outro", Markers.rename("intro", "outro").apply(doc).markers().get(0).name());
    }

    @Test
    void renamingAMarkerUpdatesStateReferencesAcrossMachines() {
        StateMachine first = new StateMachine("first", "idle",
                List.of(new StateMachine.State("idle", "intro", true, 1, 2),
                        new StateMachine.State("busy", "marker1", false, 3, 4)), List.of());
        StateMachine second = new StateMachine("second", "initial",
                List.of(new StateMachine.State("initial", "intro", false, 5, 6)), List.of());
        Animation before = doc.withStateMachines(List.of(first, second));
        Animation after = Markers.rename("intro", "outro").apply(before);

        assertEquals("outro", after.stateMachines().get(0).states().get(0).marker());
        assertEquals("marker1", after.stateMachines().get(0).states().get(1).marker());
        assertEquals("outro", after.stateMachines().get(1).states().get(0).marker());
        after.stateMachines().forEach(machine -> machine.validateMarkers(after));
        assertSame(before, Markers.rename("missing", "outro").apply(before));
    }

    @Test
    void rangesStayInsideTheDurationAndAtLeastOneFrameLong() {
        assertEquals(new Marker("intro", 0, 3), Markers.setRange("Move", "intro", -1, 9).apply(doc).markers().get(0));
        Marker tiny = Markers.setRange("Resize", "intro", 1, 1).apply(doc).markers().get(0);
        assertEquals(1, tiny.start(), 1e-12);
        assertEquals(1 + 1 / 60.0, tiny.end(), 1e-12);
        Marker atEnd = Markers.setRange("Resize", "intro", 3, 3).apply(doc).markers().get(0);
        assertEquals(3, atEnd.end(), 1e-12);
        assertEquals(3 - 1 / 60.0, atEnd.start(), 1e-12);
        // start and end the wrong way round are swapped
        assertEquals(new Marker("intro", 1, 2), Markers.setRange("Resize", "intro", 2, 1).apply(doc).markers().get(0));
    }

    @Test
    void removingAnUnknownMarkerChangesNothing() {
        assertSame(doc, Markers.remove("nope").apply(doc));
        assertEquals(List.of(new Marker("marker1", 1, 2)), Markers.remove("intro").apply(doc).markers());
    }
}
