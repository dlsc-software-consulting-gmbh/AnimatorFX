package com.dlsc.animatorfx.editor.ui;

import javafx.beans.property.BooleanProperty;
import com.dlsc.animatorfx.editor.canvas.CanvasActions;
import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.timeline.KeyframeActions;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Builds the menu bar. Every item has an id ({@code menu-file-save}, {@code menu-edit-duplicate},
 * …) for {@link MainWindow#menuItem}.
 *
 * <p>With the system menu bar of macOS an accelerator fires before the focused control sees the
 * key. The Edit items therefore look at the focus first: while a text input has it, Undo, Redo,
 * Cut, Copy, Paste and Select All act on that text, and the other items do nothing. Otherwise Cut,
 * Copy and Paste act on the selected keyframes when there are any, and on the layers when not.
 *
 * <p>For the same reason the items look at the dialog layer: while a dialog is showing inside the
 * window, an item does nothing, except that the text items still serve a focused text input (the
 * list of import warnings can be copied from), the theme can be switched, the panels of the
 * View menu can be shown and hidden, and Quit quits.
 */
final class MenuBarFactory {

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final MainWindow window;
    private final Workspace workspace;
    private final FileActions files;
    private boolean keyframesCopiedLast;

    MenuBarFactory(MainWindow window) {
        this.window = window;
        this.workspace = window.getWorkspace();
        this.files = window.getFileActions();
    }

    MenuBar build() {
        MenuBar bar = new MenuBar(fileMenu(), editMenu(), viewMenu(), helpMenu());
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private Menu fileMenu() {
        MenuItem revert = item("menu-file-revert", "Revert", null, files::revert);
        bindToDocument(doc -> revert.disableProperty().bind(
                        Bindings.createBooleanBinding(() -> doc.getPath() == null || !doc.isDirty(),
                                doc.pathProperty(), doc.dirtyProperty())),
                () -> unbindDisabled(revert));

        Menu imports = new Menu("Import");
        imports.setId("menu-file-import");
        imports.getItems().addAll(
                item("menu-file-import-svg", "SVG…", null, () -> imports().chooseAndImport(ImportActions.Kind.SVG)),
                item("menu-file-import-svg-into", "SVG into Current Document…", null,
                        () -> imports().chooseSvgIntoDocument()),
                item("menu-file-import-lottie", "Lottie…", null,
                        () -> imports().chooseAndImport(ImportActions.Kind.LOTTIE)),
                item("menu-file-import-fxml", "FXML…", null, () -> imports().chooseAndImport(ImportActions.Kind.FXML)),
                item("menu-file-import-image", "Image…", null, () -> window.getCanvasView().getActions().chooseImage()),
                item("menu-file-import-audio", "Audio…", null, () -> imports().chooseAndImportAudio()),
                item("menu-file-import-video", "Video…", null, () -> imports().chooseAndImportVideo()));

        Menu exports = new Menu("Export");
        exports.setId("menu-file-export");
        exports.getItems().add(item("menu-file-export-lottie", "Lottie…", null,
                () -> window.getExportActions().chooseAndExportLottie()));

        // macOS has Quit in the application menu, where it ends the application the same way
        MenuItem quit = always("menu-file-quit", "Quit", shortcut(KeyCode.Q), Platform::exit);
        quit.setVisible(!MAC);
        SeparatorMenuItem quitSeparator = new SeparatorMenuItem();
        quitSeparator.setVisible(!MAC);

        Menu menu = new Menu("File");
        menu.setId("menu-file");
        menu.getItems().addAll(
                item("menu-file-new", "New", shortcut(KeyCode.N), files::newDocument),
                item("menu-file-ai-generate", "Generate with AI…", null, window::showAiGeneration),
                item("menu-file-ai-preferences", "AI Preferences…", null, window::showAiPreferences),
                item("menu-file-open", "Open…", shortcut(KeyCode.O), files::open),
                recentMenu(),
                new SeparatorMenuItem(),
                item("menu-file-save", "Save", shortcut(KeyCode.S), files::save),
                item("menu-file-save-as", "Save As…", shortcut(KeyCode.S, KeyCombination.SHIFT_DOWN), files::saveAs),
                revert,
                new SeparatorMenuItem(),
                imports,
                exports,
                new SeparatorMenuItem(),
                item("menu-file-close", "Close", shortcut(KeyCode.W), files::close),
                quitSeparator,
                quit);
        return menu;
    }

    private ImportActions imports() {
        return window.getImportActions();
    }

    private Menu recentMenu() {
        Menu menu = new Menu("Open Recent");
        menu.setId("menu-file-open-recent");
        InvalidationListener rebuild = obs -> {
            List<MenuItem> items = new ArrayList<>();
            for (Path file : workspace.getRecentFiles()) {
                MenuItem item = new MenuItem(file.getFileName() + "  —  " + file.toAbsolutePath().getParent());
                item.setMnemonicParsing(false);
                item.setOnAction(e -> {
                    if (!window.isDialogShowing()) {
                        files.open(file);
                    }
                });
                items.add(item);
            }
            if (items.isEmpty()) {
                MenuItem none = new MenuItem("No Recent Files");
                none.setDisable(true);
                items.add(none);
            } else {
                items.add(new SeparatorMenuItem());
                items.add(item("menu-file-clear-recent", "Clear Menu", null, workspace.getRecentFiles()::clear));
            }
            menu.getItems().setAll(items);
        };
        workspace.getRecentFiles().addListener(rebuild);
        rebuild.invalidated(null);
        return menu;
    }

    private Menu editMenu() {
        MenuItem undo = textAware("menu-edit-undo", "Undo", shortcut(KeyCode.Z), TextInputControl::undo,
                () -> workspace.getActiveDocument().undo());
        MenuItem redo = textAware("menu-edit-redo", "Redo", shortcut(KeyCode.Z, KeyCombination.SHIFT_DOWN),
                TextInputControl::redo, () -> workspace.getActiveDocument().redo());
        bindToDocument(doc -> {
            undo.textProperty().bind(named("Undo", doc.undoNameProperty()));
            undo.disableProperty().bind(doc.canUndoProperty().not());
            redo.textProperty().bind(named("Redo", doc.redoNameProperty()));
            redo.disableProperty().bind(doc.canRedoProperty().not());
        }, () -> {
            for (MenuItem item : List.of(undo, redo)) {
                item.textProperty().unbind();
                unbindDisabled(item);
            }
            undo.setText("Undo");
            redo.setText("Redo");
        });

        MenuItem cut = textAware("menu-edit-cut", "Cut", shortcut(KeyCode.X), TextInputControl::cut, this::cut);
        MenuItem copy = textAware("menu-edit-copy", "Copy", shortcut(KeyCode.C), TextInputControl::copy, this::copy);
        MenuItem paste = textAware("menu-edit-paste", "Paste", shortcut(KeyCode.V), TextInputControl::paste,
                this::paste);
        MenuItem selectAll = textAware("menu-edit-select-all", "Select All", shortcut(KeyCode.A),
                TextInputControl::selectAll, () -> actions().selectAll());
        MenuItem duplicate = onLayers("menu-edit-duplicate", "Duplicate", shortcut(KeyCode.D), CanvasActions::duplicate);
        MenuItem delete = onLayers("menu-edit-delete", "Delete", null, a -> {
            if (window.getTimelineView().deletesHere()) {
                window.getTimelineView().deleteSelection();
            } else {
                a.delete();
            }
        });
        MenuItem group = onLayers("menu-edit-group", "Group", shortcut(KeyCode.G), CanvasActions::group);
        MenuItem ungroup = onLayers("menu-edit-ungroup", "Ungroup", shortcut(KeyCode.G, KeyCombination.SHIFT_DOWN),
                CanvasActions::ungroup);
        MenuItem forward = onLayers("menu-edit-bring-forward", "Bring Forward", shortcut(KeyCode.CLOSE_BRACKET),
                a -> a.reorder(Commands.Order.FORWARD));
        MenuItem backward = onLayers("menu-edit-send-backward", "Send Backward", shortcut(KeyCode.OPEN_BRACKET),
                a -> a.reorder(Commands.Order.BACKWARD));
        MenuItem front = onLayers("menu-edit-bring-to-front", "Bring to Front",
                shortcut(KeyCode.CLOSE_BRACKET, KeyCombination.SHIFT_DOWN), a -> a.reorder(Commands.Order.FRONT));
        MenuItem back = onLayers("menu-edit-send-to-back", "Send to Back",
                shortcut(KeyCode.OPEN_BRACKET, KeyCombination.SHIFT_DOWN), a -> a.reorder(Commands.Order.BACK));
        MenuItem editPath = onLayers("menu-edit-edit-path", "Edit Path", null, a -> {
            List<String> ids = workspace.getActiveDocument() == null ? List.of()
                    : workspace.getActiveDocument().getSelection().layerIds();
            if (ids.size() == 1 && !window.getCanvasView().editPath(ids.get(0))) {
                window.getCanvasView().notice("Only a path layer can be edited point by point."
                        + " Convert the shape to a path first.");
            }
        });
        MenuItem convert = onLayers("menu-edit-convert-to-path", "Convert to Path", null, CanvasActions::convertToPath);
        // these need selected layers; cut and copy stay enabled because they also serve text inputs
        List<MenuItem> needSelection = List.of(duplicate, group, ungroup, forward, backward, front, back,
                editPath);
        bindToDocument(doc -> needSelection.forEach(item ->
                        item.disableProperty().bind(Bindings.isEmpty(doc.getSelection().layerIds()))),
                () -> needSelection.forEach(MenuBarFactory::unbindDisabled));
        // Delete also removes the selected sound cues, which are selected instead of layers
        bindToDocument(doc -> delete.disableProperty().bind(Bindings.isEmpty(doc.getSelection().layerIds())
                        .and(Bindings.isEmpty(doc.getSelection().cueIds()))),
                () -> unbindDisabled(delete));
        // only for shapes that are not paths yet
        bindToDocument(doc -> convert.disableProperty().bind(Bindings.createBooleanBinding(
                        () -> !CanvasActions.canConvertToPath(doc.getAnimation(), doc.getSelection().layerIds()),
                        doc.getSelection().layerIds(), doc.animationProperty())),
                () -> unbindDisabled(convert));

        Menu menu = new Menu("Edit");
        menu.setId("menu-edit");
        menu.getItems().addAll(undo, redo, new SeparatorMenuItem(),
                cut, copy, paste, duplicate, delete,
                new SeparatorMenuItem(),
                selectAll,
                new SeparatorMenuItem(),
                group, ungroup,
                new SeparatorMenuItem(),
                forward, backward, front, back,
                new SeparatorMenuItem(),
                editPath, convert);
        return menu;
    }

    private CanvasActions actions() {
        return window.getCanvasView().getActions();
    }

    private KeyframeActions keyframes() {
        return window.getTimelineView().getKeyframeActions();
    }

    // While keyframes are selected, Cut, Copy and Paste are about keyframes, otherwise about
    // layers. Paste also follows what was copied last, so that keyframes can be pasted after
    // the selection has been cleared.

    private void copy() {
        keyframesCopiedLast = keyframes().hasSelection();
        if (keyframesCopiedLast) {
            keyframes().copy();
        } else {
            actions().copy();
        }
    }

    private void cut() {
        keyframesCopiedLast = keyframes().hasSelection();
        if (keyframesCopiedLast) {
            keyframes().copy();
            keyframes().delete();
        } else {
            actions().cut();
        }
    }

    private void paste() {
        if (keyframes().hasSelection() || keyframesCopiedLast) {
            keyframes().paste();
        } else {
            actions().paste();
        }
    }

    /** The text input that has the keyboard focus, or {@code null}. */
    private TextInputControl focusedText() {
        return window.getScene() != null && window.getScene().getFocusOwner() instanceof TextInputControl text
                ? text : null;
    }

    /** An item that edits the focused text input when there is one, and the document otherwise. */
    private MenuItem textAware(String id, String text, KeyCombination accelerator,
                               Consumer<TextInputControl> onText, Runnable onDocument) {
        return always(id, text, accelerator, () -> {
            TextInputControl focused = focusedText();
            if (focused != null) {
                onText.accept(focused);
            } else if (!window.isDialogShowing() && workspace.getActiveDocument() != null) {
                onDocument.run();
            }
        });
    }

    /** An item for the selected layers; it does nothing while a text input has the focus. */
    private MenuItem onLayers(String id, String text, KeyCombination accelerator, Consumer<CanvasActions> action) {
        return item(id, text, accelerator, () -> {
            if (focusedText() == null) {
                action.accept(actions());
            }
        });
    }

    private Menu viewMenu() {
        CanvasView canvas = window.getCanvasView();
        Theme theme = window.getTheme();
        ToggleGroup group = new ToggleGroup();
        Menu themes = new Menu("Theme");
        themes.setId("menu-view-theme");
        for (Theme.Mode mode : Theme.Mode.values()) {
            String name = mode.name().charAt(0) + mode.name().substring(1).toLowerCase(Locale.ROOT);
            RadioMenuItem item = new RadioMenuItem(name);
            item.setId("menu-view-theme-" + mode.name().toLowerCase(Locale.ROOT));
            item.setToggleGroup(group);
            item.setSelected(theme.getMode() == mode);
            item.setOnAction(e -> theme.setMode(mode));
            theme.modeProperty().addListener((obs, oldMode, newMode) -> item.setSelected(newMode == mode));
            themes.getItems().add(item);
        }

        CheckMenuItem sidePanel = new CheckMenuItem("Side Panel");
        sidePanel.setId("menu-view-side-panel");
        sidePanel.setAccelerator(shortcut(KeyCode.I, KeyCombination.ALT_DOWN));
        sidePanel.selectedProperty().bindBidirectional(window.sidePanelVisibleProperty());
        CheckMenuItem timeline = new CheckMenuItem("Timeline");
        timeline.setId("menu-view-timeline");
        timeline.setAccelerator(shortcut(KeyCode.T, KeyCombination.ALT_DOWN));
        timeline.selectedProperty().bindBidirectional(window.timelineVisibleProperty());

        // M without a modifier is handled by the window: as an accelerator of the system menu bar
        // it would take the letter away from text fields
        CheckMenuItem mute = new CheckMenuItem("Mute Sound (M)");
        mute.setId("menu-view-mute");
        BooleanProperty muted = window.getToolBar().mutedProperty();
        mute.setSelected(muted.get());
        muted.addListener((obs, was, now) -> mute.setSelected(now));
        // a click has toggled the check mark already, a fired item has not: the flag decides
        mute.setOnAction(e -> {
            if (!window.isDialogShowing()) {
                muted.set(!muted.get());
            }
            mute.setSelected(muted.get());
        });

        Menu menu = new Menu("View");
        menu.setId("menu-view");
        menu.getItems().addAll(
                item("menu-view-zoom-in", "Zoom In", shortcut(KeyCode.PLUS), canvas::zoomIn),
                item("menu-view-zoom-out", "Zoom Out", shortcut(KeyCode.MINUS), canvas::zoomOut),
                item("menu-view-zoom-fit", "Zoom to Fit", shortcut(KeyCode.DIGIT0), canvas::zoomToFit),
                item("menu-view-actual-size", "Actual Size", shortcut(KeyCode.DIGIT1), canvas::zoomToActualSize),
                new SeparatorMenuItem(), mute,
                new SeparatorMenuItem(), themes, new SeparatorMenuItem(), sidePanel, timeline);
        return menu;
    }

    private Menu helpMenu() {
        Menu menu = new Menu("Help");
        menu.setId("menu-help");
        menu.getItems().add(item("menu-help-about", "About AnimatorFX", null,
                () -> window.getDialogs().showAbout()));
        return menu;
    }

    private interface DocumentBinding {
        void bind(EditorDocument document);
    }

    /** Runs {@code unbind} and then, for a non-null document, {@code bind} now and whenever the active document changes. */
    private void bindToDocument(DocumentBinding bind, Runnable unbind) {
        InvalidationListener rebind = obs -> {
            unbind.run();
            EditorDocument document = workspace.getActiveDocument();
            if (document != null) {
                bind.bind(document);
            }
        };
        workspace.activeDocumentProperty().addListener(rebind);
        rebind.invalidated(null);
    }

    private static void unbindDisabled(MenuItem item) {
        item.disableProperty().unbind();
        item.setDisable(true);
    }

    private static ObservableValue<String> named(String verb, ObservableValue<String> commandName) {
        return Bindings.createStringBinding(
                () -> commandName.getValue() == null ? verb : verb + " " + commandName.getValue(), commandName);
    }

    /** An item that does nothing while a dialog is showing. */
    private MenuItem item(String id, String text, KeyCombination accelerator, Runnable action) {
        return always(id, text, accelerator, () -> {
            if (!window.isDialogShowing()) {
                action.run();
            }
        });
    }

    private static MenuItem always(String id, String text, KeyCombination accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private static KeyCombination shortcut(KeyCode code, KeyCombination.Modifier... modifiers) {
        KeyCombination.Modifier[] all = new KeyCombination.Modifier[modifiers.length + 1];
        all[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, all, 1, modifiers.length);
        return new KeyCodeCombination(code, all);
    }
}
