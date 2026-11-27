package com.dlsc.animatorfx.editor.ui;

import com.dlsc.gemsfx.DialogPane;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * The dialogs of the application: the file choosers of the platform, and questions and messages
 * that show inside the main window, in its {@link DialogLayer}.
 */
public final class SystemDialogs implements Dialogs {

    private static final FileChooser.ExtensionFilter AFX =
            new FileChooser.ExtensionFilter("AnimatorFX documents (*.afx)", "*.afx");
    private static final String EXTENSION = ".afx";
    private static final String RESOURCES = "/com/dlsc/animatorfx/editor/";
    private static final double QUESTION_WIDTH = 440;
    private static final double LIST_WIDTH = 640;

    private final DialogLayer layer;
    private Path lastDirectory;

    SystemDialogs(DialogLayer layer) {
        this.layer = layer;
    }

    @Override
    public Optional<Path> chooseOpenFile(Window owner) {
        FileChooser chooser = chooser("Open");
        return remember(chooser.showOpenDialog(owner));
    }

    @Override
    public Optional<Path> chooseSaveFile(Window owner, String suggestedName) {
        FileChooser chooser = chooser("Save As");
        chooser.setInitialFileName(suggestedName.endsWith(EXTENSION) ? suggestedName : suggestedName + EXTENSION);
        return remember(chooser.showSaveDialog(owner)).map(SystemDialogs::withExtension);
    }

    /**
     * The file with {@code .afx} added to its name, unless the name ends with it already, in
     * upper or lower case: a name with another dot in it is not a name with an extension.
     */
    static Path withExtension(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? path : path.resolveSibling(name + EXTENSION);
    }

    @Override
    public CompletableFuture<AudioPlacement> chooseAudioInsertion(double cursorTime) {
        ButtonType beginning = new ButtonType("Beginning", ButtonData.NO);
        ButtonType cursor = new ButtonType("Current Time Cursor", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.CONFIRMATION, "Insert Audio",
                message("Where should the audio start? The current time cursor is at "
                        + String.format(Locale.ROOT, "%.2f s.", cursorTime)), QUESTION_WIDTH,
                List.of(beginning, cancel, cursor));
        CompletableFuture<AudioPlacement> answer = new CompletableFuture<>();
        if (!layer.show(dialog, button -> answer.complete(button == beginning ? AudioPlacement.BEGINNING
                : button == cursor ? AudioPlacement.CURSOR : AudioPlacement.CANCEL))) {
            answer.complete(AudioPlacement.CANCEL);
        }
        return answer;
    }

    @Override
    public CompletableFuture<CloseChoice> confirmClose(String documentTitle) {
        ButtonType save = new ButtonType("Save", ButtonData.YES);
        // macOS sets the button that throws the changes away apart, at the left
        ButtonType discard = new ButtonType("Don't Save", MenuBarFactory.MAC ? ButtonData.LEFT : ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.CONFIRMATION,
                "Save the changes to \"" + documentTitle + "\"?",
                message("Your changes will be lost if you don't save them."), QUESTION_WIDTH,
                List.of(discard, cancel, save));
        CompletableFuture<CloseChoice> choice = new CompletableFuture<>();
        if (!layer.show(dialog, button -> choice.complete(
                button == save ? CloseChoice.SAVE : button == discard ? CloseChoice.DISCARD : CloseChoice.CANCEL))) {
            choice.complete(CloseChoice.CANCEL);
        }
        return choice;
    }

    @Override
    public CompletableFuture<Boolean> confirmRevert(String documentTitle) {
        ButtonType revert = new ButtonType("Revert", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.WARNING,
                "Revert \"" + documentTitle + "\" to the saved file?",
                message("All changes since the last save will be lost."), QUESTION_WIDTH, List.of(cancel, revert));
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        if (!layer.show(dialog, button -> answer.complete(button == revert))) {
            answer.complete(false);
        }
        return answer;
    }

    @Override
    public void showAbout() {
        Label name = new Label("AnimatorFX");
        name.getStyleClass().add("about-name");
        Label version = new Label("Version " + version());
        version.getStyleClass().add("about-version");
        Label description = message("An animation editor for JavaFX. Documents play back with the"
                + " dependency-free animatorfx-player library.");
        Label runtime = new Label("Java " + System.getProperty("java.version") + "  ·  JavaFX "
                + System.getProperty("javafx.runtime.version"));
        runtime.getStyleClass().addAll("about-runtime", "mono");
        VBox text = new VBox(name, version, description, runtime);
        text.getStyleClass().add("about-text");
        HBox content = new HBox(text);
        content.getStyleClass().add("about");
        try (InputStream in = SystemDialogs.class.getResourceAsStream(RESOURCES + "app-icon.png")) {
            if (in != null) {
                ImageView icon = new ImageView(new Image(in));
                icon.setFitWidth(64);
                icon.setFitHeight(64);
                icon.setSmooth(true);
                content.getChildren().add(0, icon);
            }
        } catch (IOException e) {
            // no icon then
        }
        ButtonType close = new ButtonType("Close", ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.INFORMATION, "About AnimatorFX", content,
                QUESTION_WIDTH, List.of(close));
        dialog.getStyleClass().add(DialogLayer.NO_ICON);
        dialog.setShowHeader(false);
        showMessage(dialog, close);
    }

    @Override
    public void showWarnings(String title, List<String> lines) {
        TextArea text = new TextArea(String.join("\n", lines));
        text.setId("import-warnings");
        text.getStyleClass().add("mono");
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(Math.clamp(lines.size() + 1, 6, 16));
        VBox content = new VBox(message(title), text);
        content.getStyleClass().add("warnings");

        Button copy = new Button("Copy");
        copy.setId("import-warnings-copy");
        PauseTransition copied = new PauseTransition(Duration.seconds(1.5));
        copied.setOnFinished(e -> copy.setText("Copy"));
        copy.setOnAction(e -> {
            ClipboardContent clip = new ClipboardContent();
            clip.putString(text.getText());
            Clipboard.getSystemClipboard().setContent(clip);
            copy.setText("Copied");
            copied.playFromStart();
        });

        ButtonType close = new ButtonType("Close", ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.WARNING, "Import Warnings", content,
                LIST_WIDTH, List.of(close));
        dialog.setExtras(copy);
        showMessage(dialog, close);
    }

    /** Shows a dialog whose only way out is the Close button, which Enter presses too. */
    private void showMessage(DialogPane.Dialog<Void> dialog, ButtonType close) {
        if (layer.show(dialog, button -> {
        })) {
            dialog.getButton(close).setDefaultButton(true);
        }
    }

    private static Label message(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("message");
        label.setWrapText(true);
        return label;
    }

    /** The version of the build; the resource is filtered by Maven. */
    static String version() {
        Properties build = new Properties();
        try (InputStream in = SystemDialogs.class.getResourceAsStream(RESOURCES + "build.properties")) {
            if (in != null) {
                build.load(in);
            }
        } catch (IOException e) {
            // unknown then
        }
        return build.getProperty("version", "unknown");
    }

    private FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(AFX);
        if (lastDirectory != null && Files.isDirectory(lastDirectory)) {
            chooser.setInitialDirectory(lastDirectory.toFile());
        }
        return chooser;
    }

    private Optional<Path> remember(File file) {
        if (file == null) {
            return Optional.empty();
        }
        Path path = file.toPath();
        lastDirectory = path.toAbsolutePath().getParent();
        return Optional.of(path);
    }
}
