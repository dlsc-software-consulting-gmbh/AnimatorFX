package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialogs of the application itself, not scripted ones: they show inside the main window.
 */
class InWindowDialogsTest {

    @TempDir
    Path dir;

    private final List<MainWindow> windows = new ArrayList<>();

    @Test
    void aiMenuEntriesOpenInWindowDialogs() {
        String old = System.getProperty("animatorfx.configDir");
        System.setProperty("animatorfx.configDir", dir.resolve("ai-config").toString());
        try {
            MainWindow window = fx(() -> {
                MainWindow w = new MainWindow(new Stage(), new SessionStore(dir.resolve("config")),
                        new RecoveryStore(dir.resolve("config").resolve("recovery")), null,
                        provider -> { throw new IllegalStateException(); },
                        (provider, key) -> java.util.concurrent.CompletableFuture.completedFuture(List.of()));
                w.getStage().setWidth(1100);
                w.getStage().setHeight(720);
                w.show();
                return w;
            });
            windows.add(window);
            settle(window);
            run(() -> window.menuItem("menu-file-ai-generate").fire());
            settle(window);
            assertNotNull(fx(() -> window.getScene().lookup("#ai-description")));
            type(window, KeyCode.ESCAPE);
            run(() -> window.menuItem("menu-edit-ai-preferences").fire());
            settle(window);
            assertNotNull(fx(() -> window.getScene().lookup("#ai-provider")));
        } finally {
            if (old == null) System.clearProperty("animatorfx.configDir");
            else System.setProperty("animatorfx.configDir", old);
        }
    }

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeWindows() {
        fx(() -> {
            for (MainWindow window : windows) {
                window.shutdown();
            }
            for (Window window : List.copyOf(Window.getWindows())) {
                if (windows.stream().anyMatch(w -> w.getStage() == window)
                        || (window instanceof Stage stage && stage.getOwner() != null)) {
                    window.hide();
                }
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

    private MainWindow start() {
        MainWindow window = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(dir.resolve("config")),
                    new RecoveryStore(dir.resolve("config").resolve("recovery")));
            w.getStage().setWidth(1100);
            w.getStage().setHeight(720);
            w.show();
            return w;
        });
        windows.add(window);
        settle(window);
        return window;
    }

    private static void settle(MainWindow window) {
        WaitForAsyncUtils.waitForFxEvents();
        run(() -> {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        });
    }

    private static ShapeLayer rect(String id) {
        return new ShapeLayer(LayerBase.create(id, 3).withId(id),
                new Geometry.Rect(Animatable.of(new Vec2(40, 30)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(Rgba.WHITE)), null, null);
    }

    /** Makes the active document dirty and asks to close it, as File ▸ Close does. */
    private EditorDocument requestCloseOfADirtyDocument(MainWindow window) {
        EditorDocument document = fx(() -> {
            EditorDocument active = window.getWorkspace().getActiveDocument();
            active.execute(Commands.addLayer(null, 0, rect("a")));
            return active;
        });
        Platform.runLater(() -> window.getFileActions().close());
        settle(window);
        return document;
    }

    /** The active document, saved to a file and changed since. */
    private EditorDocument dirtyDocumentWithAFile(MainWindow window, String fileName) {
        return fx(() -> {
            EditorDocument active = window.getWorkspace().getActiveDocument();
            active.saveAs(dir.resolve(fileName));
            active.execute(Commands.addLayer(null, 0, rect("a")));
            return active;
        });
    }

    /** The buttons of the dialog, from left to right. */
    private static List<Button> buttons(MainWindow window) {
        return fx(() -> window.getScene().getRoot().lookupAll(".dialog-pane > .content-pane .footer .button").stream()
                .map(Button.class::cast)
                .sorted(Comparator.comparingDouble(b -> b.localToScene(b.getBoundsInLocal()).getMinX()))
                .collect(Collectors.toList()));
    }

    private static List<String> buttonTexts(MainWindow window) {
        return buttons(window).stream().map(Button::getText).collect(Collectors.toList());
    }

    private static void click(MainWindow window, String buttonText) {
        Button button = buttons(window).stream().filter(b -> b.getText().equals(buttonText)).findFirst()
                .orElseThrow(() -> new AssertionError("No button " + buttonText));
        run(button::fire);
        settle(window);
    }

    private static String title(MainWindow window) {
        return fx(() -> ((Label) window.getScene().getRoot().lookup(".dialog-pane > .content-pane .header .title"))
                .getText());
    }

    /** Presses and releases a key where the keyboard focus is, as the window would deliver it. */
    private static void type(MainWindow window, KeyCode code, boolean shift, boolean shortcut) {
        run(() -> {
            Node owner = window.getScene().getFocusOwner();
            Node target = owner != null ? owner : window.getScene().getRoot();
            String text = code.isLetterKey() ? code.getName().toLowerCase() : code == KeyCode.SPACE ? " " : "";
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, shift, false, false, shortcut));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", text, code, shift, false, false, shortcut));
        });
        settle(window);
    }

    private static void type(MainWindow window, KeyCode code) {
        type(window, code, false, false);
    }

    private static boolean dialogShowing(MainWindow window) {
        return fx(window::isDialogShowing);
    }

    private static int layers(EditorDocument document) {
        return fx(() -> document.getAnimation().layers().size());
    }

    private static Node dialog(MainWindow window) {
        return fx(() -> window.getScene().getRoot().lookup(".dialog-pane > .content-pane"));
    }

    @Test
    void closingADirtyDocumentAsksInsideTheMainWindow() {
        MainWindow window = start();
        int openWindows = fx(() -> Window.getWindows().size());

        EditorDocument document = requestCloseOfADirtyDocument(window);

        assertEquals(openWindows, fx(() -> Window.getWindows().size()), "the question must not open a window");
        assertNotNull(dialog(window), "the question is a node of the main scene");
        assertTrue(dialogShowing(window));
        assertEquals("Save the changes to \"" + fx(document::getTitle) + "\"?", title(window));
        // "Don't Save" stands apart at the left where the platform has it so
        assertEquals(MenuBarFactory.MAC ? List.of("Don't Save", "Cancel", "Save") : buttonTexts(window),
                buttonTexts(window));
        assertEquals(List.of("Cancel", "Don't Save", "Save"), buttonTexts(window).stream().sorted().toList());
        assertSame(document, fx(() -> window.getWorkspace().getActiveDocument()));
    }

    @Test
    void cancelLeavesTheDocumentOpenAndDirty() {
        MainWindow window = start();
        EditorDocument document = requestCloseOfADirtyDocument(window);

        click(window, "Cancel");

        assertFalse(dialogShowing(window));
        assertNull(dialog(window));
        assertSame(document, fx(() -> window.getWorkspace().getActiveDocument()));
        assertTrue(fx(document::isDirty));
    }

    @Test
    void dontSaveClosesTheDocumentWithoutSaving() {
        MainWindow window = start();
        EditorDocument document = dirtyDocumentWithAFile(window, "kept.afx");
        long saved = fx(() -> Files.size(dir.resolve("kept.afx")));
        Platform.runLater(() -> window.getFileActions().close());
        settle(window);

        click(window, "Don't Save");

        assertFalse(dialogShowing(window));
        assertFalse(fx(() -> window.getWorkspace().getDocuments().contains(document)));
        assertEquals(saved, fx(() -> Files.size(dir.resolve("kept.afx"))));
    }

    @Test
    void saveSavesAndThenClosesTheDocument() {
        MainWindow window = start();
        EditorDocument document = dirtyDocumentWithAFile(window, "saved.afx");
        Platform.runLater(() -> window.getFileActions().close());
        settle(window);
        assertTrue(fx(document::isDirty));

        click(window, "Save");

        assertFalse(dialogShowing(window));
        assertFalse(fx(document::isDirty), "saved");
        assertFalse(fx(() -> window.getWorkspace().getDocuments().contains(document)), "closed");
        assertEquals(1, fx(() -> EditorDocument.open(dir.resolve("saved.afx")).getAnimation().layers().size()));
    }

    @Test
    void escapeCancelsTheQuestion() {
        MainWindow window = start();
        EditorDocument document = requestCloseOfADirtyDocument(window);

        type(window, KeyCode.ESCAPE);

        assertFalse(dialogShowing(window));
        assertSame(document, fx(() -> window.getWorkspace().getActiveDocument()));
        assertTrue(fx(document::isDirty));
    }

    @Test
    void enterPressesTheDefaultButton() {
        MainWindow window = start();
        EditorDocument document = dirtyDocumentWithAFile(window, "entered.afx");
        Platform.runLater(() -> window.getFileActions().close());
        settle(window);
        assertTrue(buttons(window).stream().filter(Button::isDefaultButton).map(Button::getText)
                .toList().equals(List.of("Save")));

        type(window, KeyCode.ENTER);

        assertFalse(dialogShowing(window));
        assertFalse(fx(document::isDirty), "saved");
        assertFalse(fx(() -> window.getWorkspace().getDocuments().contains(document)), "closed");
    }

    @Test
    void theKeyboardFocusIsInTheDialogAndReturnsWhenItCloses() {
        MainWindow window = start();
        run(() -> window.getCanvasView().requestFocus());
        Node before = fx(() -> window.getScene().getFocusOwner());
        assertNotNull(before);

        requestCloseOfADirtyDocument(window);
        Node inDialog = fx(() -> window.getScene().getFocusOwner());
        assertEquals("Save", ((Button) inDialog).getText());

        // Tab goes round the buttons and never to the window behind
        List<String> visited = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            type(window, KeyCode.TAB);
            visited.add(((Button) fx(() -> window.getScene().getFocusOwner())).getText());
        }
        assertEquals(3, visited.size());
        assertTrue(visited.containsAll(List.of("Don't Save", "Cancel", "Save")), visited.toString());
        assertEquals("Save", ((Button) fx(() -> window.getScene().getFocusOwner())).getText());

        click(window, "Cancel");
        assertSame(before, fx(() -> window.getScene().getFocusOwner()));
    }

    @Test
    void keysAndMenuItemsDoNotActOnTheDocumentWhileTheDialogShows() {
        MainWindow window = start();
        EditorDocument document = requestCloseOfADirtyDocument(window);
        run(() -> document.getSelection().layerIds().setAll("a"));

        type(window, KeyCode.R);
        assertEquals(ToolKind.SELECT, fx(() -> window.getToolRail().getActiveTool()), "tool shortcut");
        type(window, KeyCode.DELETE);
        assertEquals(1, layers(document), "Delete");
        type(window, KeyCode.Z, false, true);
        assertEquals(1, layers(document), "the accelerator of Undo");
        // the system menu bar of macOS fires an item without a key event reaching the scene
        run(() -> window.menuItem("menu-edit-undo").fire());
        run(() -> window.menuItem("menu-edit-delete").fire());
        run(() -> window.menuItem("menu-file-new").fire());
        assertEquals(1, layers(document), "the menu items");
        assertEquals(1, fx(() -> window.getWorkspace().getDocuments().size()));
        assertTrue(dialogShowing(window));

        // the same keys act once the dialog is gone
        click(window, "Cancel");
        type(window, KeyCode.R);
        assertEquals(ToolKind.RECTANGLE, fx(() -> window.getToolRail().getActiveTool()));
        run(() -> window.menuItem("menu-edit-undo").fire());
        assertEquals(0, layers(document));
    }

    @Test
    void spacePressesTheFocusedButtonInsteadOfStartingPlayback() {
        MainWindow window = start();
        EditorDocument document = requestCloseOfADirtyDocument(window);
        Button cancel = buttons(window).stream().filter(button -> button.getText().equals("Cancel"))
                .findFirst().orElseThrow();
        run(cancel::requestFocus);
        assertSame(cancel, fx(() -> window.getScene().getFocusOwner()));

        type(window, KeyCode.SPACE);

        assertFalse(fx(() -> window.getCanvasView().isPlaying()));
        assertFalse(dialogShowing(window));
        assertSame(document, fx(() -> window.getWorkspace().getActiveDocument()));
    }

    @Test
    void askingToCloseTwiceShowsOneDialog() {
        MainWindow window = start();
        EditorDocument document = requestCloseOfADirtyDocument(window);

        run(() -> window.menuItem("menu-file-close").fire());
        boolean second = fx(() -> window.getFileActions().close().getNow(true));
        settle(window);

        assertFalse(second, "the second request is answered with Cancel at once");
        assertEquals(1, fx(() -> window.getScene().getRoot().lookupAll(".dialog-pane > .content-pane").size()));
        click(window, "Cancel");
        assertFalse(dialogShowing(window));
        assertSame(document, fx(() -> window.getWorkspace().getActiveDocument()));
    }

    @Test
    void revertAsksAndFollowsTheAnswer() {
        MainWindow window = start();
        EditorDocument document = dirtyDocumentWithAFile(window, "reverted.afx");
        int openWindows = fx(() -> Window.getWindows().size());

        run(() -> window.menuItem("menu-file-revert").fire());
        settle(window);
        assertEquals(openWindows, fx(() -> Window.getWindows().size()));
        assertEquals("Revert \"reverted.afx\" to the saved file?", title(window));
        assertEquals(List.of("Cancel", "Revert"), buttonTexts(window));
        click(window, "Cancel");
        assertEquals(1, layers(document));
        assertTrue(fx(document::isDirty));

        run(() -> window.menuItem("menu-file-revert").fire());
        settle(window);
        click(window, "Revert");
        assertEquals(0, layers(document));
        assertFalse(fx(document::isDirty));
    }

    @Test
    void aboutShowsTheVersionOfTheBuildAndTheRuntime() {
        MainWindow window = start();
        int openWindows = fx(() -> Window.getWindows().size());

        run(() -> window.menuItem("menu-help-about").fire());
        settle(window);

        assertEquals(openWindows, fx(() -> Window.getWindows().size()));
        List<String> texts = fx(() -> dialogLabels(window));
        String version = SystemDialogs.version();
        assertTrue(version.matches("\\d+\\.\\d+.*"), "the version comes from the build, was: " + version);
        assertTrue(texts.contains("AnimatorFX"), texts.toString());
        assertTrue(texts.contains("Version " + version), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.contains("Java " + System.getProperty("java.version"))
                && t.contains("JavaFX " + System.getProperty("javafx.runtime.version"))), texts.toString());
        assertEquals(List.of("Close"), buttonTexts(window));

        type(window, KeyCode.ENTER);
        assertFalse(dialogShowing(window));
    }

    private static List<String> dialogLabels(MainWindow window) {
        return window.getScene().getRoot().lookupAll(".dialog-pane > .content-pane .label").stream()
                .map(label -> ((Label) label).getText()).collect(Collectors.toList());
    }

    @Test
    void theWarningsOfAnImportAreListedAndCanBeCopied() {
        MainWindow window = start();
        List<String> lines = List.of("first warning", "second warning", "third warning", "fourth warning",
                "fifth warning", "sixth warning", "seventh warning", "eighth warning");
        int openWindows = fx(() -> Window.getWindows().size());

        run(() -> window.getDialogs().showWarnings("Imported demo.svg with 8 warnings.", lines));
        settle(window);

        assertEquals(openWindows, fx(() -> Window.getWindows().size()));
        TextArea list = fx(() -> (TextArea) window.getScene().getRoot().lookup(".dialog-pane #import-warnings"));
        assertEquals(lines, fx(() -> List.of(list.getText().split("\n"))));
        assertFalse(fx(list::isEditable));
        assertTrue(fx(() -> dialogLabels(window)).contains("Imported demo.svg with 8 warnings."));

        // Copy is no way out of the dialog
        assertEquals(List.of("Copy", "Close"), buttonTexts(window));
        click(window, "Copy");
        assertTrue(dialogShowing(window));
        assertEquals(String.join("\n", lines), fx(() -> Clipboard.getSystemClipboard().getString()));

        click(window, "Close");
        assertFalse(dialogShowing(window));
    }

    @Test
    void theWarningsCanBeOpenedAgainFromTheNotice() {
        MainWindow window = start();
        Path svg = dir.resolve("badge.svg");
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("import/badge.svg"))) {
            Files.copy(in, svg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(fx(() -> window.getImportActions().importFile(svg, ImportActions.Kind.SVG)).isPresent());
        assertEquals(List.of("Imported badge.svg with 2 warnings."), fx(() -> window.getNoticeBar().getMessages()));

        for (int time = 1; time <= 2; time++) {
            run(() -> ((Button) window.getNoticeBar().lookup(".notice-action")).fire());
            settle(window);
            TextArea list = fx(() -> (TextArea) window.getScene().getRoot().lookup(".dialog-pane #import-warnings"));
            assertNotNull(list, "opened, time " + time);
            assertEquals(2, fx(() -> list.getText().split("\n").length));
            type(window, KeyCode.ESCAPE);
            assertFalse(dialogShowing(window));
        }
    }

    @Test
    void switchingTheThemeRestylesAnOpenDialog() {
        MainWindow window = start();
        run(() -> window.getTheme().setMode(Theme.Mode.DARK));
        requestCloseOfADirtyDocument(window);
        Region surface = (Region) dialog(window);
        Object dark = fx(() -> surface.getBackground().getFills().getLast().getFill());

        run(() -> window.getTheme().setMode(Theme.Mode.LIGHT));
        settle(window);

        Object light = fx(() -> surface.getBackground().getFills().getLast().getFill());
        assertNotEquals(dark, light);
        assertEquals(fx(() -> ((Region) window.getToolBar()).getBackground().getFills().getLast().getFill()), light,
                "the dialog has the colour of the panels");
    }
}
