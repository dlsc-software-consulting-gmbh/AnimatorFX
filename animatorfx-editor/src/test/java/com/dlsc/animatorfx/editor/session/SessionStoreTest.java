package com.dlsc.animatorfx.editor.session;

import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.session.SessionState.DocumentState;
import com.dlsc.animatorfx.editor.session.SessionState.Layout;
import com.dlsc.animatorfx.editor.session.SessionState.WindowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path dir;

    private static SessionState full(Path dir) {
        DocumentState saved = new DocumentState("doc-1", dir.resolve("a b/über.afx"), 1.25, -40, 12.5, 1.14,
                List.of("layer-1", "layer-2"), List.of(new KeyframeRef("layer-1", "transform.position", "k1")),
                2.5, 0.75, List.of("layer-1"), true, true);
        DocumentState untitled = new DocumentState("doc-2", null, 1, 0, 0, 0, List.of(), List.of(), 1, 0, List.of(),
                false, false);
        return new SessionState(new WindowState(120, 80, 1200, 760, true, 1),
                new Layout(0.71, 0.62, true, false, "document"), "LIGHT", List.of(saved, untitled), "doc-2",
                List.of(dir.resolve("recent-1.afx"), dir.resolve("recent-2.afx")), true, false);
    }

    @Test
    void aMissingFileGivesTheDefaults() {
        SessionState state = new SessionStore(dir).load();

        assertEquals(SessionState.defaults(), state);
        assertNull(state.window());
        assertEquals("SYSTEM", state.theme());
        assertTrue(state.documents().isEmpty());
    }

    @Test
    void everyItemSurvivesARoundTrip() {
        SessionState state = full(dir);

        new SessionStore(dir).save(state);

        assertEquals(state, new SessionStore(dir).load());
    }

    @Test
    void saveCreatesTheDirectoryAndLeavesNoTemporaryFile() throws IOException {
        Path nested = dir.resolve("config/AnimatorFX");

        new SessionStore(nested).save(full(dir));
        new SessionStore(nested).save(SessionState.defaults());

        try (Stream<Path> files = Files.list(nested)) {
            assertEquals(List.of("session.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aCorruptFileIsRenamedAsideAndDefaultsAreUsed() throws IOException {
        Files.writeString(dir.resolve("session.json"), "{ \"theme\": ");

        SessionState state = new SessionStore(dir).load();

        assertEquals(SessionState.defaults(), state);
        assertFalse(Files.exists(dir.resolve("session.json")));
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size());
            assertTrue(names.get(0).matches("session\\.json\\.corrupt-\\d{8}-\\d{6}(-\\d+)?"), names.get(0));
        }
    }

    @Test
    void validJsonOfTheWrongShapeCountsAsCorrupt() throws IOException {
        Files.writeString(dir.resolve("session.json"), "{ \"documents\": 42 }");

        assertEquals(SessionState.defaults(), new SessionStore(dir).load());
        assertFalse(Files.exists(dir.resolve("session.json")));
    }

    @Test
    void missingMembersFallBackToTheirDefaults() throws IOException {
        Files.writeString(dir.resolve("session.json"), "{ \"theme\": \"DARK\", \"documents\": [ { \"id\": \"d\" } ] }");

        SessionState state = new SessionStore(dir).load();

        assertEquals("DARK", state.theme());
        assertEquals(SessionState.defaults().layout(), state.layout());
        assertEquals(new DocumentState("d", null, 1, 0, 0, 0, List.of(), List.of(), 1, 0, List.of(), false, false),
                state.documents().get(0));
        assertTrue(Files.exists(dir.resolve("session.json")));
    }

    @Test
    void recentFilesMoveToTheFrontWithoutDuplicatesAndAreCappedAtTwenty() {
        SessionState state = SessionState.defaults();
        for (int i = 0; i < 25; i++) {
            state = state.withRecentFile(dir.resolve("file-" + i + ".afx"));
        }
        state = state.withRecentFile(dir.resolve("file-10.afx"));

        assertEquals(20, state.recentFiles().size());
        assertEquals(dir.resolve("file-10.afx"), state.recentFiles().get(0));
        assertEquals(dir.resolve("file-24.afx"), state.recentFiles().get(1));
        assertEquals(20, new ArrayList<>(state.recentFiles()).stream().distinct().count());
    }

    @Test
    void nonFiniteNumbersDoNotBreakSaving() {
        SessionState state = new SessionState(new WindowState(Double.NaN, 0, 800, 600, false, 0),
                SessionState.defaults().layout(), "DARK", List.of(), null, List.of());

        new SessionStore(dir).save(state);

        assertEquals(0, new SessionStore(dir).load().window().x());
    }
}
