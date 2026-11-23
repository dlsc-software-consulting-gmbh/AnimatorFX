package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionState;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests of the shell under the headless toolkit. Every window uses a temporary configuration directory.
 */
class MainWindowTest {

    @Test
    void theWindowHasAReadableApplicationIcon() {
        MainWindow window = start();
        run(() -> {
            assertFalse(window.getStage().getIcons().isEmpty());
            var icon = window.getStage().getIcons().getFirst();
            assertFalse(icon.isError());
            assertTrue(icon.getWidth() > 0 && icon.getHeight() > 0);
        });
    }

    /** Answers the dialogs from queues, at once; an empty queue means "cancelled". */
    private static final class StubDialogs implements Dialogs {

        final Deque<Path> openAnswers = new ArrayDeque<>();
        final Deque<Path> saveAnswers = new ArrayDeque<>();
        final Deque<CloseChoice> closeAnswers = new ArrayDeque<>();
        final List<String> suggestedNames = new ArrayList<>();
        int closePrompts;

        @Override
        public Optional<Path> chooseOpenFile(javafx.stage.Window owner) {
            return Optional.ofNullable(openAnswers.poll());
        }

        @Override
        public Optional<Path> chooseSaveFile(javafx.stage.Window owner, String suggestedName) {
            suggestedNames.add(suggestedName);
            return Optional.ofNullable(saveAnswers.poll());
        }

        @Override
        public CompletableFuture<CloseChoice> confirmClose(String documentTitle) {
            closePrompts++;
            CloseChoice answer = closeAnswers.poll();
            return CompletableFuture.completedFuture(answer == null ? CloseChoice.CANCEL : answer);
        }

        @Override
        public CompletableFuture<Boolean> confirmRevert(String documentTitle) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void showAbout() {
        }

        @Override
        public void showWarnings(String title, List<String> lines) {
        }
    }

    @TempDir
    Path dir;

    private final StubDialogs dialogs = new StubDialogs();
    private final List<MainWindow> windows = new ArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeWindows() {
        fx(() -> {
            for (MainWindow window : windows) {
                window.shutdown();
                window.getStage().hide();
            }
            return null;
        });
    }

    private static <T> T fx(Callable<T> action) {
        try {
            return WaitForAsyncUtils.asyncFx(action).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(Runnable action) {
        fx(() -> {
            action.run();
            return null;
        });
    }

    private Path config() {
        return dir.resolve("config");
    }

    /** Opens a window on the configuration directory, as a start of the application does. */
    private MainWindow start() {
        MainWindow window = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(config()),
                    new RecoveryStore(config().resolve("recovery")), dialogs);
            w.show();
            return w;
        });
        windows.add(window);
        WaitForAsyncUtils.waitForFxEvents();
        run(() -> {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        });
        return window;
    }

    /** Ends a window as quitting the application does. */
    private void quit(MainWindow window) {
        run(() -> {
            window.shutdown();
            window.getStage().hide();
        });
        windows.remove(window);
    }

    private static ShapeLayer rect(String id) {
        return new ShapeLayer(LayerBase.create(id, 3).withId(id),
                new Geometry.Rect(Animatable.of(new Vec2(40, 30)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(Rgba.WHITE)), null, null);
    }

    private static String text(MainWindow window, String selector) {
        return ((Label) window.getScene().lookup(selector)).getText();
    }

    @Test
    void theWindowShowsTheLayoutWithOneUntitledDocument() {
        MainWindow window = start();

        run(() -> {
            assertTrue(window.getStage().isShowing());
            for (String region : List.of(".toolbar", ".tool-rail", ".canvas-pane", ".side-panel", ".timeline-pane",
                    ".status-bar", ".document-tabs")) {
                assertNotNull(window.getScene().lookup(region), region);
            }
            assertEquals(1, window.getWorkspace().getDocuments().size());
            EditorDocument document = window.getWorkspace().getActiveDocument();
            assertEquals("Untitled", document.getTitle());
            assertFalse(document.isDirty());
            assertSame(document, window.getCanvasView().getDocument());
            assertSame(document, window.getSidePanel().getDocument());
            assertSame(document, window.getTimelineView().getDocument());
            assertEquals("800 × 600", text(window, "#status-size"));
            assertEquals("0 layers", text(window, "#status-layers"));
            assertEquals("0 selected", text(window, "#status-selection"));
            assertEquals("Saved", text(window, "#status-autosave"));
            assertEquals("00:00.000 / 00:03.000", text(window, "#timecode"));
            assertEquals("60 fps", text(window, "#fps"));
            // an untouched document is zoomed to fit the canvas, at most to 100 %
            assertEquals(Formats.zoom(document.getViewState().getCanvasZoom()), text(window, "#zoom"));
        });
    }

    @Test
    void switchingTheThemeTogglesTheLightStyleClassAndTheUserAgentStylesheet() {
        MainWindow window = start();

        run(() -> {
            window.getTheme().setMode(Theme.Mode.DARK);
            assertFalse(window.getScene().getRoot().getStyleClass().contains("light"));
            String dark = javafx.application.Application.getUserAgentStylesheet();

            window.getTheme().setMode(Theme.Mode.LIGHT);
            assertTrue(window.getScene().getRoot().getStyleClass().contains("light"));
            assertNotEquals(dark, javafx.application.Application.getUserAgentStylesheet());

            ((Button) window.getScene().lookup("#theme-toggle")).fire();
            assertEquals(Theme.Mode.DARK, window.getTheme().getMode());
            assertFalse(window.getScene().getRoot().getStyleClass().contains("light"));
        });
    }

    @Test
    void aRestartRestoresWindowSizeDividersThemePanelsAndOpenDocuments() throws Exception {
        Path file = dir.resolve("saved.afx");
        MainWindow first = start();
        run(() -> {
            first.getStage().setX(40);
            first.getStage().setY(30);
            first.getStage().setWidth(1000);
            first.getStage().setHeight(700);
        });
        // the dividers are set once the window has its new size, as a user dragging them would
        Thread.sleep(300);
        WaitForAsyncUtils.waitForFxEvents();
        run(() -> {
            first.getTheme().setMode(Theme.Mode.LIGHT);
            first.applyLayout(new SessionState.Layout(0.6, 0.5, false, false, "document"));
            first.getScene().getRoot().layout();

            EditorDocument untitled = first.getWorkspace().getActiveDocument();
            untitled.execute(Commands.addLayer(null, 0, rect("a")));
            untitled.execute(Commands.setValueAt("a", "opacity", 1, 0.5, true));
            try {
                untitled.saveAs(file);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            untitled.setPlayhead(1.25);
            untitled.setAutoKey(true);
            untitled.getSelection().layerIds().add("a");
            String keyId = untitled.getAnimation().layers().get(0).base().opacity().keyframes().get(0).id();
            untitled.getSelection().keyframes().add(new KeyframeRef("a", "opacity", keyId));
            untitled.getViewState().setCanvasZoom(2);
            untitled.getViewState().setCanvasPanX(-30);
            untitled.getViewState().setTimelineZoom(3);
            untitled.getViewState().setTimelineScroll(0.5);
            untitled.getViewState().getExpandedLayerIds().add("a");
            untitled.getViewState().setShowAllProperties(true);

            first.getFileActions().newDocument();
            first.getWorkspace().setActiveDocument(untitled);
        });
        String savedId = fx(() -> first.getWorkspace().getActiveDocument().getId());
        quit(first);

        MainWindow second = start();

        run(() -> {
            assertEquals(1000, second.getStage().getWidth(), 1);
            assertEquals(700, second.getStage().getHeight(), 1);
            assertEquals(Theme.Mode.LIGHT, second.getTheme().getMode());
            assertTrue(second.getScene().getRoot().getStyleClass().contains("light"));
            SessionState.Layout layout = second.getLayoutState();
            assertEquals(0.6, layout.sideDivider(), 0.005);
            assertEquals(0.5, layout.timelineDivider(), 0.005);
            assertEquals("document", layout.sideTab());

            assertEquals(2, second.getWorkspace().getDocuments().size());
            EditorDocument restored = second.getWorkspace().getActiveDocument();
            assertEquals(savedId, restored.getId());
            assertEquals(file, restored.getPath());
            assertFalse(restored.isDirty());
            assertEquals(1.25, restored.getPlayhead());
            assertTrue(restored.isAutoKey());
            assertEquals(List.of("a"), restored.getSelection().layerIds());
            assertEquals(1, restored.getSelection().keyframes().size());
            assertEquals(2, restored.getViewState().getCanvasZoom());
            assertEquals(-30, restored.getViewState().getCanvasPanX());
            assertEquals(3, restored.getViewState().getTimelineZoom());
            assertEquals(0.5, restored.getViewState().getTimelineScroll());
            assertTrue(restored.getViewState().getExpandedLayerIds().contains("a"));
            assertTrue(restored.getViewState().isShowAllProperties());
            assertEquals("Untitled", second.getWorkspace().getDocuments().get(1).getTitle());
            assertEquals(List.of(file), second.getWorkspace().getRecentFiles());
            assertTrue(second.getNoticeBar().getMessages().isEmpty());
        });
    }

    @Test
    void collapsedPanelsAreRestored() {
        MainWindow first = start();
        run(() -> {
            first.setSidePanelVisible(false);
            first.setTimelineVisible(false);
            assertNull(first.getScene().lookup(".side-panel"));
            assertNull(first.getScene().lookup(".timeline-pane"));
        });
        quit(first);

        MainWindow second = start();

        run(() -> {
            assertFalse(second.isSidePanelVisible());
            assertFalse(second.isTimelineVisible());
            second.setSidePanelVisible(true);
            assertNotNull(second.getScene().lookup(".side-panel"));
        });
    }

    @Test
    void aDirtyUntitledDocumentIsRecoveredAfterARestartAndCanBeDiscarded() {
        MainWindow first = start();
        String id = fx(() -> {
            EditorDocument document = first.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            return document.getId();
        });
        quit(first);

        MainWindow second = start();

        run(() -> {
            EditorDocument recovered = second.getWorkspace().getActiveDocument();
            assertEquals(id, recovered.getId());
            assertEquals(1, recovered.getAnimation().layers().size());
            assertTrue(recovered.isDirty());
            assertNull(recovered.getPath());
            assertEquals(1, second.getNoticeBar().getMessages().size());
            assertTrue(second.getNoticeBar().getMessages().get(0).contains("Untitled"));

            Button discard = (Button) second.getNoticeBar().lookup(".notice-action");
            assertEquals("Discard recovered changes", discard.getText());
            discard.fire();

            assertTrue(second.getNoticeBar().getMessages().isEmpty());
            assertEquals(1, second.getWorkspace().getDocuments().size());
            EditorDocument fresh = second.getWorkspace().getActiveDocument();
            assertFalse(fresh.isDirty());
            assertTrue(fresh.getAnimation().layers().isEmpty());
        });
        assertTrue(new RecoveryStore(config().resolve("recovery")).all().isEmpty());
    }

    @Test
    void aDirtySavedDocumentIsRecoveredAndDiscardingRevertsToTheFile() throws Exception {
        Path file = dir.resolve("work.afx");
        MainWindow first = start();
        run(() -> {
            EditorDocument document = first.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            try {
                document.saveAs(file);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            document.execute(Commands.addLayer(null, 0, rect("b")));
        });
        quit(first);

        MainWindow second = start();

        run(() -> {
            EditorDocument recovered = second.getWorkspace().getActiveDocument();
            assertEquals(file, recovered.getPath());
            assertEquals(2, recovered.getAnimation().layers().size());
            assertTrue(recovered.isDirty());

            ((Button) second.getNoticeBar().lookup(".notice-action")).fire();

            assertSame(recovered, second.getWorkspace().getActiveDocument());
            assertEquals(1, recovered.getAnimation().layers().size());
            assertFalse(recovered.isDirty());
        });
        assertTrue(new RecoveryStore(config().resolve("recovery")).all().isEmpty());
    }

    /** Starts, makes the untitled document dirty, quits and starts again: the second window shows the recovered document. */
    private MainWindow restartWithARecoveredUntitledDocument() {
        MainWindow first = start();
        run(() -> first.getWorkspace().getActiveDocument().execute(Commands.addLayer(null, 0, rect("a"))));
        quit(first);
        MainWindow second = start();
        assertEquals(1, fx(() -> second.getNoticeBar().getMessages().size()));
        return second;
    }

    @Test
    void editingARecoveredDocumentRetiresTheDiscardNotice() {
        MainWindow window = restartWithARecoveredUntitledDocument();

        run(() -> {
            EditorDocument recovered = window.getWorkspace().getActiveDocument();
            recovered.execute(Commands.addLayer(null, 0, rect("b")));

            assertTrue(window.getNoticeBar().getMessages().isEmpty());
            assertNull(window.getNoticeBar().lookup(".notice-action"));
            assertSame(recovered, window.getWorkspace().getActiveDocument());
            assertEquals(2, recovered.getAnimation().layers().size());
        });
    }

    @Test
    void undoInARecoveredDocumentRetiresTheDiscardNotice() throws Exception {
        Path file = dir.resolve("work.afx");
        MainWindow first = start();
        run(() -> {
            EditorDocument document = first.getWorkspace().getActiveDocument();
            try {
                document.saveAs(file);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            document.execute(Commands.addLayer(null, 0, rect("a")));
        });
        quit(first);
        MainWindow second = start();

        run(() -> {
            EditorDocument recovered = second.getWorkspace().getActiveDocument();
            recovered.execute(Commands.addLayer(null, 0, rect("b")));
            recovered.undo();

            assertTrue(second.getNoticeBar().getMessages().isEmpty());
            assertEquals(1, recovered.getAnimation().layers().size());
        });
    }

    @Test
    void savingARecoveredDocumentRetiresTheDiscardNotice() {
        MainWindow window = restartWithARecoveredUntitledDocument();

        dialogs.saveAnswers.add(dir.resolve("kept.afx"));
        assertTrue(fx(() -> window.getFileActions().save()));

        assertTrue(fx(() -> window.getNoticeBar().getMessages().isEmpty()));
    }

    @Test
    void closingARecoveredDocumentRetiresTheDiscardNotice() {
        MainWindow window = restartWithARecoveredUntitledDocument();

        dialogs.closeAnswers.add(Dialogs.CloseChoice.DISCARD);
        assertTrue(fx(() -> window.getFileActions().close().getNow(false)));

        assertTrue(fx(() -> window.getNoticeBar().getMessages().isEmpty()));
    }

    @Test
    void aRecoveryCopyTheWindowDidNotWriteIsDeletedWhenTheDocumentIsSaved() {
        MainWindow window = start();
        RecoveryStore recovery = new RecoveryStore(config().resolve("recovery"));

        String id = fx(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            // as a store that failed half way leaves it: the copy is there, the window does not know
            recovery.store(document.getId(), document.getFileContent(), null);
            return document.getId();
        });
        dialogs.saveAnswers.add(dir.resolve("saved.afx"));
        assertTrue(fx(() -> window.getFileActions().save()));

        assertTrue(recovery.load(id).isEmpty());
    }

    @Test
    void savingADocumentDeletesItsRecoveryCopy() {
        MainWindow window = start();
        RecoveryStore recovery = new RecoveryStore(config().resolve("recovery"));

        String id = fx(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            window.getSession().autosaveNow();
            return document.getId();
        });
        assertTrue(recovery.load(id).isPresent());
        assertTrue(fx(() -> text(window, "#status-autosave")).startsWith("Autosaved "));

        dialogs.saveAnswers.add(dir.resolve("out.afx"));
        assertTrue(fx(() -> window.getFileActions().save()));

        assertTrue(recovery.load(id).isEmpty());
        assertTrue(Files.exists(dir.resolve("out.afx")));
        assertEquals("Saved", fx(() -> text(window, "#status-autosave")));
    }

    /** Two slots with one name: the model and the writer take that, the reader does not. */
    private static void addTwoSlotsNamedCard(EditorDocument document) {
        SlotLayer slot = new SlotLayer(LayerBase.create("s", 3), "card", 10, 10, Transform.IDENTITY);
        document.execute(Commands.addLayer(null, 0, slot));
        document.execute(Commands.addLayer(null, 1, slot.withBase(slot.base().withId("other"))));
    }

    @Test
    void aDocumentThatCouldNotBeOpenedAgainIsNotSavedAndTheNoticeSaysWhy() throws Exception {
        MainWindow window = start();
        Path file = dir.resolve("out.afx");
        dialogs.saveAnswers.add(file);
        run(() -> window.getWorkspace().getActiveDocument().execute(Commands.addLayer(null, 0, rect("a"))));
        assertTrue(fx(() -> window.getFileActions().save()));
        byte[] good = Files.readAllBytes(file);

        run(() -> addTwoSlotsNamedCard(window.getWorkspace().getActiveDocument()));
        assertFalse(fx(() -> window.getFileActions().save()));

        assertEquals(List.of("Could not save out.afx: layers[1].slotName: Duplicate slot name: card"),
                fx(() -> window.getNoticeBar().getMessages()));
        assertTrue(fx(() -> window.getWorkspace().getActiveDocument().isDirty()));
        assertArrayEquals(good, Files.readAllBytes(file));
    }

    @Test
    void aRecoveryCopyThatCouldNotBeReadBackKeepsThePreviousOneAndIsReportedOnce() {
        MainWindow window = start();
        RecoveryStore recovery = new RecoveryStore(config().resolve("recovery"));
        String id = fx(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            window.getSession().autosaveNow();
            addTwoSlotsNamedCard(document);
            window.getSession().autosaveNow();
            window.getSession().autosaveNow();
            document.execute(Commands.addLayer(null, 0, rect("b")));
            window.getSession().autosaveNow();
            return document.getId();
        });

        assertEquals(List.of("Recovery copy could not be written: layers[1].slotName: Duplicate slot name: card (Untitled)"),
                fx(() -> window.getNoticeBar().getMessages()));
        assertEquals(1, recovery.load(id).orElseThrow().file().animation().layers().size(), "the last good copy");

        // once the document can be read back again, its copy is written again
        run(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.undo();
            document.undo();
            document.undo();
            document.execute(Commands.addLayer(null, 0, rect("c")));
            window.getSession().autosaveNow();
        });
        assertEquals(2, recovery.load(id).orElseThrow().file().animation().layers().size());
    }

    @Test
    void anUnreadableRecoveryCopyIsKeptUnderAnotherNameAndReported() throws Exception {
        MainWindow first = start();
        String id = fx(() -> {
            EditorDocument document = first.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));
            return document.getId();
        });
        quit(first);
        Path copy = config().resolve("recovery").resolve(id + ".afx");
        Files.writeString(copy, "damaged");

        MainWindow second = start();

        Path kept;
        try (var files = Files.list(config().resolve("recovery"))) {
            kept = files.filter(p -> p.getFileName().toString().startsWith(id + ".afx.unreadable-")).findFirst().orElseThrow();
        }
        assertEquals(List.of("The recovery copy of an unsaved document could not be read. It was kept as " + kept + "."),
                fx(() -> second.getNoticeBar().getMessages()));
        assertFalse(Files.exists(copy));
        // the document comes back empty; working on it and closing it leaves the copy where it is
        run(() -> {
            EditorDocument document = second.getWorkspace().getActiveDocument();
            assertEquals(id, document.getId());
            document.execute(Commands.addLayer(null, 0, rect("b")));
            second.getSession().autosaveNow();
            second.getFileActions().discard(document);
        });
        quit(second);
        assertEquals("damaged", Files.readString(kept));
    }

    @Test
    void aFileThatNoLongerExistsIsDroppedWithANotice() throws Exception {
        Path file = dir.resolve("gone.afx");
        MainWindow first = start();
        run(() -> {
            try {
                first.getWorkspace().getActiveDocument().saveAs(file);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        quit(first);
        Files.delete(file);

        MainWindow second = start();

        run(() -> {
            assertEquals(1, second.getWorkspace().getDocuments().size());
            assertNull(second.getWorkspace().getActiveDocument().getPath());
            assertEquals(1, second.getNoticeBar().getMessages().size());
            assertTrue(second.getNoticeBar().getMessages().get(0).contains("gone.afx"));
            assertNull(second.getNoticeBar().lookup(".notice-action"));

            ((Button) second.getNoticeBar().lookup(".notice-dismiss")).fire();
            assertTrue(second.getNoticeBar().getMessages().isEmpty());
        });
        // the stored session forgets the file without any further change
        WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                () -> new SessionStore(config()).load().documents().stream().noneMatch(d -> file.equals(d.path())));
    }

    @Test
    void aCorruptSessionFileDoesNotStopTheStart() throws Exception {
        Files.createDirectories(config());
        Files.writeString(config().resolve("session.json"), "not json");

        MainWindow window = start();

        assertEquals(1, fx(() -> window.getWorkspace().getDocuments().size()));
    }

    @Test
    void theSessionIsWrittenDebouncedAfterAChange() throws Exception {
        MainWindow window = start();
        Path session = config().resolve("session.json");

        run(() -> {
            // nothing is pending after this, so the file can only come back through the change below
            window.getSession().saveNow();
            try {
                Files.deleteIfExists(session);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            window.getWorkspace().getActiveDocument().setPlayhead(2);
            // still on the FX thread, where the delayed save would run: it has not happened yet
            assertFalse(Files.exists(session));
        });

        WaitForAsyncUtils.waitFor(30, java.util.concurrent.TimeUnit.SECONDS, () -> Files.exists(session));
        assertEquals(2, new SessionStore(config()).load().documents().get(0).playhead());
    }

    @Test
    void undoAndRedoMenuItemsShowTheCommandName() {
        MainWindow window = start();

        run(() -> {
            MenuItem undo = window.menuItem("menu-edit-undo");
            MenuItem redo = window.menuItem("menu-edit-redo");
            assertEquals("Undo", undo.getText());
            assertTrue(undo.isDisable());

            window.getWorkspace().getActiveDocument().execute(Commands.addLayer(null, 0, rect("a")));
            assertEquals("Undo Add Layer", undo.getText());
            assertFalse(undo.isDisable());
            assertEquals("1 layer", text(window, "#status-layers"));
            assertEquals("Unsaved changes", text(window, "#status-autosave"));

            undo.fire();
            assertTrue(window.getWorkspace().getActiveDocument().getAnimation().layers().isEmpty());
            assertEquals("Redo Add Layer", redo.getText());
            redo.fire();
            assertEquals(1, window.getWorkspace().getActiveDocument().getAnimation().layers().size());
        });
    }

    @Test
    void menuItemsThatNeedASelectionAreDisabledWithoutOne() {
        MainWindow window = start();

        run(() -> {
            // the imports and the canvas commands are live; those that need selected layers wait for a selection
            for (String id : List.of("menu-file-import-svg", "menu-file-import-svg-into", "menu-file-import-lottie",
                    "menu-file-import-fxml", "menu-file-import-image", "menu-edit-cut", "menu-edit-copy", "menu-edit-paste",
                    "menu-edit-select-all", "menu-view-zoom-in", "menu-view-zoom-out", "menu-view-zoom-fit",
                    "menu-view-actual-size")) {
                assertFalse(window.menuItem(id).isDisable(), id);
            }
            for (String id : List.of("menu-edit-duplicate", "menu-edit-delete", "menu-edit-group",
                    "menu-edit-ungroup", "menu-edit-bring-forward", "menu-edit-send-backward",
                    "menu-edit-bring-to-front", "menu-edit-send-to-back")) {
                assertTrue(window.menuItem(id).isDisable(), id);
            }
            for (String id : List.of("menu-file-new", "menu-file-open", "menu-file-open-recent", "menu-file-save",
                    "menu-file-save-as", "menu-file-close", "menu-file-quit", "menu-view-theme-dark",
                    "menu-view-theme-light", "menu-view-theme-system", "menu-view-side-panel", "menu-view-timeline",
                    "menu-help-about")) {
                assertFalse(window.menuItem(id).isDisable(), id);
            }
            assertTrue(window.menuItem("menu-file-revert").isDisable());
        });
    }

    @Test
    void toolShortcutsSelectTheToolAndTheRailFollows() {
        MainWindow window = start();

        run(() -> {
            assertEquals(ToolKind.SELECT, window.getToolRail().getActiveTool());

            Event.fireEvent(window.getScene().getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "r", "r", KeyCode.R,
                    false, false, false, false));
            assertEquals(ToolKind.RECTANGLE, window.getToolRail().getActiveTool());

            Event.fireEvent(window.getScene().getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "k", "k", KeyCode.K,
                    false, false, false, false));
            assertEquals(ToolKind.SLOT, window.getToolRail().getActiveTool());

            ((javafx.scene.control.ToggleButton) window.getScene().lookup("#tool-star")).fire();
            assertEquals(ToolKind.STAR, window.getToolRail().getActiveTool());
        });
    }

    @Test
    void theTimecodeFollowsThePlayheadAndTheTransportButtonsMoveIt() {
        MainWindow window = start();

        run(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.setPlayhead(1.14);
            assertEquals("00:01.140 / 00:03.000", text(window, "#timecode"));

            ((Button) window.getScene().lookup("#transport-end")).fire();
            assertEquals(3, document.getPlayhead());
            ((Button) window.getScene().lookup("#transport-start")).fire();
            assertEquals(0, document.getPlayhead());
        });
    }

    @Test
    void saveAsOntoAFileThatIsOpenInAnotherTabIsRefused() throws Exception {
        MainWindow window = start();
        Path file = dir.resolve("first.afx");
        dialogs.saveAnswers.add(file);
        run(() -> window.getWorkspace().getActiveDocument().execute(Commands.addLayer(null, 0, rect("a"))));
        assertTrue(fx(() -> window.getFileActions().save()));
        byte[] saved = Files.readAllBytes(file);

        EditorDocument second = fx(() -> {
            EditorDocument document = window.getFileActions().newDocument();
            document.execute(Commands.addLayer(null, 0, rect("b")));
            return document;
        });
        dialogs.saveAnswers.add(dir.resolve(".").resolve("first.afx"));
        assertFalse(fx(() -> window.getFileActions().saveAs(second)));

        assertEquals(List.of("first.afx is open in another tab (first.afx). Close that tab or choose another name."),
                fx(() -> window.getNoticeBar().getMessages()));
        assertNull(second.getPath());
        assertTrue(second.isDirty());
        assertArrayEquals(saved, Files.readAllBytes(file));

        // saving a document onto its own file is no conflict
        EditorDocument first = fx(() -> window.getWorkspace().getDocuments().get(0));
        dialogs.saveAnswers.add(file);
        assertTrue(fx(() -> window.getFileActions().saveAs(first)));
    }

    @Test
    void savingADocumentOpenedFromABareJsonFileAsksForAnAfxFile() throws Exception {
        Path json = dir.resolve("plain.json");
        Files.writeString(json, "{\"formatVersion\": 1, \"width\": 100, \"height\": 50, \"duration\": 2}");
        String source = Files.readString(json);
        MainWindow window = start();
        EditorDocument document = fx(() -> window.getFileActions().open(json)).orElseThrow();
        run(() -> document.execute(Commands.addLayer(null, 0, rect("a"))));

        // cancelled: nothing is written, least of all a zip named .json
        assertFalse(fx(() -> window.getFileActions().save()));
        assertEquals(List.of("plain.afx"), dialogs.suggestedNames);
        assertEquals(source, Files.readString(json));
        assertTrue(document.isDirty());

        dialogs.saveAnswers.add(dir.resolve("plain.afx"));
        assertTrue(fx(() -> window.getFileActions().save()));
        assertEquals(source, Files.readString(json));
        assertEquals(dir.resolve("plain.afx"), document.getPath());
        assertFalse(document.isDirty());

        // from now on Save writes the .afx file without asking
        run(() -> document.execute(Commands.addLayer(null, 0, rect("b"))));
        assertTrue(fx(() -> window.getFileActions().save()));
        assertEquals(2, dialogs.suggestedNames.size());
    }

    @Test
    void theSaveDialogAddsTheExtensionUnlessTheNameHasIt() {
        assertEquals(Path.of("dir", "my.animation.afx"), SystemDialogs.withExtension(Path.of("dir", "my.animation")));
        assertEquals(Path.of("plain.afx"), SystemDialogs.withExtension(Path.of("plain")));
        assertEquals(Path.of("done.afx"), SystemDialogs.withExtension(Path.of("done.afx")));
        assertEquals(Path.of("LOUD.AFX"), SystemDialogs.withExtension(Path.of("LOUD.AFX")));
        assertEquals(Path.of("notes.afx.bak.afx"), SystemDialogs.withExtension(Path.of("notes.afx.bak")));
    }

    @Test
    void closingADirtyDocumentAsksAndFollowsTheAnswer() {
        MainWindow window = start();

        run(() -> {
            EditorDocument document = window.getWorkspace().getActiveDocument();
            document.execute(Commands.addLayer(null, 0, rect("a")));

            dialogs.closeAnswers.add(Dialogs.CloseChoice.CANCEL);
            assertFalse(window.getFileActions().close(document).getNow(true));
            assertSame(document, window.getWorkspace().getActiveDocument());

            dialogs.closeAnswers.add(Dialogs.CloseChoice.SAVE);
            dialogs.saveAnswers.add(dir.resolve("closed.afx"));
            assertTrue(window.getFileActions().close(document).getNow(false));
            assertTrue(Files.exists(dir.resolve("closed.afx")));
            assertEquals(2, dialogs.closePrompts);

            assertEquals(1, window.getWorkspace().getDocuments().size());
            EditorDocument next = window.getWorkspace().getActiveDocument();
            assertNotEquals(document.getId(), next.getId());

            next.execute(Commands.addLayer(null, 0, rect("b")));
            dialogs.closeAnswers.add(Dialogs.CloseChoice.DISCARD);
            assertTrue(window.getFileActions().close(next).getNow(false));
            assertEquals(3, dialogs.closePrompts);
        });
    }

    @Test
    void closingACleanDocumentDoesNotAsk() {
        MainWindow window = start();

        run(() -> {
            window.getFileActions().newDocument();
            assertEquals(2, window.getWorkspace().getDocuments().size());
            assertEquals(2, window.getScene().getRoot().lookupAll(".document-tab").size());

            assertTrue(window.getFileActions().close(window.getWorkspace().getActiveDocument()).getNow(false));

            assertEquals(0, dialogs.closePrompts);
            assertEquals(1, window.getWorkspace().getDocuments().size());
        });
    }

    @Test
    void openingAFileAddsATabAndARecentFileAndOpeningItAgainActivatesIt() throws Exception {
        Path file = dir.resolve("open-me.afx");
        EditorDocument source = EditorDocument.untitled();
        source.execute(Commands.addLayer(null, 0, rect("a")));
        source.saveAs(file);
        MainWindow window = start();

        run(() -> {
            dialogs.openAnswers.add(file);
            window.getFileActions().open();

            EditorDocument opened = window.getWorkspace().getActiveDocument();
            assertEquals(file, opened.getPath());
            assertEquals(List.of(file), window.getWorkspace().getRecentFiles());
            assertEquals("AnimatorFX — open-me.afx", window.getStage().getTitle());
            // the untouched untitled start document makes way for the opened file
            assertEquals(1, window.getWorkspace().getDocuments().size());

            window.getFileActions().newDocument();
            window.getFileActions().open(file);
            assertSame(opened, window.getWorkspace().getActiveDocument());
            assertEquals(2, window.getWorkspace().getDocuments().size());
        });
    }

    @Test
    void anUnreadableFileGivesANoticeInsteadOfADocument() throws Exception {
        Path file = dir.resolve("broken.afx");
        Files.writeString(file, "this is not a document");
        MainWindow window = start();

        run(() -> {
            window.getFileActions().open(file);

            assertEquals(1, window.getWorkspace().getDocuments().size());
            assertTrue(window.getNoticeBar().getMessages().get(0).contains("broken.afx"));
        });
    }

    @Test
    void theDirtyDotOfATabFollowsTheDocument() {
        MainWindow window = start();

        run(() -> {
            javafx.scene.Node dot = window.getScene().lookup(".document-tab .dirty-dot");
            assertFalse(dot.isVisible());
            window.getWorkspace().getActiveDocument().execute(Commands.addLayer(null, 0, rect("a")));
            assertTrue(dot.isVisible());
            window.getWorkspace().getActiveDocument().undo();
            assertFalse(dot.isVisible());
        });
    }
}
