package com.dlsc.animatorfx.editor.ui;

import com.dlsc.gemsfx.DialogPane;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The layer above the content of the main window in which dialogs show: a GemsFX
 * {@link DialogPane} with the header, the footer and the entrance of the editor.
 *
 * <p>One dialog shows at a time; {@link #show} refuses a second one. While a dialog is showing
 * the keyboard belongs to it: the focus stays inside, Tab cycles through its controls, Enter
 * presses its default button, Escape cancels it, and no other key reaches the window behind.
 * When it closes, the focus goes back to where it was.
 */
final class DialogLayer {

    /** Style class of a dialog that shows no icon next to its title, or has no header at all. */
    static final String NO_ICON = "no-icon";

    private static final Duration ENTRANCE = Duration.millis(160);

    private final DialogPane pane = new DialogPane();
    private DialogPane.Dialog<?> current;

    DialogLayer() {
        pane.getStyleClass().add("editor-dialogs");
        pane.getGlassPane().setEnableStageDragging(true);
        // the veil fades; the dialog gets the short entrance of show() instead of flying in from the top
        pane.setAnimateDialogs(false);
        pane.setFadeInOut(true);
        pane.setAnimationDuration(ENTRANCE);
        pane.setHeaderFactory(DialogLayer::header);
        pane.setFooterFactory(Footer::new);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, this::cycleFocus);
        pane.addEventHandler(KeyEvent.ANY, this::keyInDialog);
        EventHandler<KeyEvent> keepOut = this::keyOutsideDialog;
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.ANY, keepOut);
            }
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.ANY, keepOut);
            }
        });
    }

    /** The node to put on top of the window content. */
    DialogPane getPane() {
        return pane;
    }

    boolean isShowing() {
        return pane.isShowingDialog();
    }

    /**
     * A dialog of this layer that is not showing yet.
     *
     * @param width the width of the dialog; the height follows from the content
     */
    DialogPane.Dialog<Void> dialog(DialogPane.Type type, String title, Node content, double width,
                                   List<ButtonType> buttons) {
        DialogPane.Dialog<Void> dialog = new DialogPane.Dialog<>(pane, type);
        dialog.setTitle(title);
        dialog.setContent(content);
        dialog.setPrefWidth(width);
        dialog.getButtonTypes().setAll(buttons);
        return dialog;
    }

    /**
     * Shows the dialog, unless another one is showing.
     *
     * @param onClose gets the button that closed the dialog; {@link ButtonType#CANCEL} for Escape
     * @return whether the dialog is showing now
     */
    boolean show(DialogPane.Dialog<?> dialog, Consumer<ButtonType> onClose) {
        Objects.requireNonNull(onClose, "onClose");
        if (isShowing()) {
            return false;
        }
        Scene scene = pane.getScene();
        Node focusBefore = scene == null ? null : scene.getFocusOwner();
        dialog.onClose(button -> {
            current = null;
            if (focusBefore != null && focusBefore.getScene() == pane.getScene()) {
                focusBefore.requestFocus();
            }
            onClose.accept(button);
        });
        current = dialog;
        dialog.show();

        Button first = defaultButton(dialog);
        if (first != null) {
            first.requestFocus();
        }
        Node surface = pane.getChildren().get(pane.getChildren().size() - 1);
        FadeTransition fade = new FadeTransition(ENTRANCE, surface);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(ENTRANCE, surface);
        rise.setFromY(8);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, rise).play();
        return true;
    }

    /** The button Enter presses: the default button, or the cancel button of a dialog without one. */
    private static Button defaultButton(DialogPane.Dialog<?> dialog) {
        Button cancel = null;
        for (ButtonType type : dialog.getButtonTypes()) {
            Button button = dialog.getButton(type);
            if (button != null && button.isDefaultButton()) {
                return button;
            }
            if (button != null && button.isCancelButton()) {
                cancel = button;
            }
        }
        return cancel;
    }

    private void keyInDialog(KeyEvent event) {
        if (!isShowing()) {
            return;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == KeyCode.ENTER && current != null
                && !event.isShortcutDown() && !event.isAltDown()) {
            Button button = defaultButton(current);
            if (button != null && !button.isDisabled()) {
                button.fire();
            }
        }
        // nothing of what was typed in a dialog is a shortcut of the window behind it
        event.consume();
    }

    private void keyOutsideDialog(KeyEvent event) {
        if (isShowing() && !(event.getTarget() instanceof Node node && isInside(node))) {
            event.consume();
        }
    }

    /** Tab goes round the controls of the dialog instead of leaving it. */
    private void cycleFocus(KeyEvent event) {
        if (!isShowing() || event.getCode() != KeyCode.TAB || event.isShortcutDown() || event.isAltDown()
                || event.isControlDown()) {
            return;
        }
        List<Node> stops = new ArrayList<>();
        collectFocusStops(pane, stops);
        if (!stops.isEmpty()) {
            int at = stops.indexOf(pane.getScene().getFocusOwner());
            int next = at < 0 ? 0 : Math.floorMod(at + (event.isShiftDown() ? -1 : 1), stops.size());
            stops.get(next).requestFocus();
        }
        event.consume();
    }

    private static void collectFocusStops(Parent parent, List<Node> stops) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isVisible() || child.isDisabled()) {
                continue;
            }
            if (child.isFocusTraversable()) {
                stops.add(child);
            } else if (child instanceof Parent group) {
                collectFocusStops(group, stops);
            }
        }
    }

    private boolean isInside(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == pane) {
                return true;
            }
        }
        return false;
    }

    private static Node header(DialogPane.Dialog<?> dialog) {
        Label title = new Label();
        title.textProperty().bind(dialog.titleProperty());
        title.getStyleClass().add("title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox header = new HBox(title);
        header.getStyleClass().add("header");
        Ikon ikon = dialog.getStyleClass().contains(NO_ICON) ? null : switch (dialog.getType()) {
            case CONFIRMATION -> Material2AL.HELP_OUTLINE;
            case INFORMATION -> Material2AL.INFO;
            case WARNING -> Material2MZ.WARNING;
            case ERROR -> Material2AL.ERROR_OUTLINE;
            case INPUT, BLANK -> null;
        };
        if (ikon != null) {
            FontIcon icon = new FontIcon(ikon);
            icon.getStyleClass().add("dialog-icon");
            header.getChildren().add(0, icon);
        }
        return header;
    }

    /**
     * The buttons of a dialog. A node set as {@code extras} of the dialog, such as a button that
     * does not close it, sits at the left.
     */
    private static final class Footer extends DialogPane.DialogButtonBar {

        Footer(DialogPane.Dialog<?> dialog) {
            super(dialog);
            // the order of the platform, with what is at the left pushed all the way there
            setButtonOrder(MenuBarFactory.MAC ? "L+NCYOA" : MenuBarFactory.WINDOWS ? "L+YNOCA" : "L+NYCOA");
            Node extras = dialog.getExtras();
            if (extras != null) {
                ButtonBar.setButtonData(extras, ButtonBar.ButtonData.LEFT);
                getButtons().add(0, extras);
            }
        }
    }
}
