package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2MZ;

/**
 * The playback bar: transport, timecode, frame rate, loop toggle and mute toggle.
 *
 * <p>The to-start and to-end buttons move the playhead of the document. Playback itself is not
 * part of the toolbar: whoever owns the player sets {@link #onPlayPauseProperty()} and keeps
 * {@link #playingProperty()} up to date; until then the play button is disabled.
 */
public final class ToolBarView extends HBox {

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final BooleanProperty loop = new SimpleBooleanProperty(this, "loop");
    private final BooleanProperty muted = new SimpleBooleanProperty(this, "muted");
    private final BooleanProperty playing = new SimpleBooleanProperty(this, "playing");
    private final ObjectProperty<Runnable> onPlayPause = new SimpleObjectProperty<>(this, "onPlayPause");

    private final Label timecode = pill(new Label(), "timecode");
    private final Label frameRate = pill(new Label(), "fps");

    public ToolBarView() {
        getStyleClass().add("toolbar");

        Button toStart = iconButton("transport-start", Material2MZ.SKIP_PREVIOUS, "Go to start");
        toStart.setOnAction(e -> seek(0));
        FontIcon playIcon = new FontIcon();
        playIcon.iconCodeProperty().bind(Bindings.when(playing)
                .then((Ikon) Material2MZ.PAUSE).otherwise(Material2MZ.PLAY_ARROW));
        Button play = iconButton("transport-play", playIcon, "Play / pause");
        play.disableProperty().bind(onPlayPause.isNull());
        play.setOnAction(e -> {
            Runnable handler = getOnPlayPause();
            if (handler != null) {
                handler.run();
            }
        });
        Button toEnd = iconButton("transport-end", Material2MZ.SKIP_NEXT, "Go to end");
        toEnd.setOnAction(e -> {
            EditorDocument doc = getDocument();
            if (doc != null) {
                seek(doc.getAnimation().duration());
            }
        });
        HBox transport = new HBox(toStart, play, toEnd);
        transport.getStyleClass().addAll("pill", "transport");

        ToggleButton loopButton = new ToggleButton("loop", new FontIcon(Material2MZ.REPEAT));
        loopButton.setId("loop");
        loopButton.getStyleClass().addAll("pill", "pill-toggle");
        loopButton.setFocusTraversable(false);
        loopButton.setTooltip(new Tooltip("Loop playback"));
        loopButton.selectedProperty().bindBidirectional(loop);

        FontIcon muteIcon = new FontIcon();
        muteIcon.iconCodeProperty().bind(Bindings.when(muted)
                .then((Ikon) Material2MZ.VOLUME_OFF).otherwise(Material2MZ.VOLUME_UP));
        ToggleButton muteButton = new ToggleButton(null, muteIcon);
        muteButton.setId("mute");
        muteButton.getStyleClass().addAll("pill", "pill-toggle", "mute-toggle");
        muteButton.setFocusTraversable(false);
        muteButton.setTooltip(new Tooltip("Mute the sound of playback and scrubbing (M)"));
        muteButton.selectedProperty().bindBidirectional(muted);

        HBox centre = new HBox(transport, timecode, frameRate, loopButton, muteButton);
        centre.getStyleClass().add("toolbar-group");

        HBox left = new HBox();
        HBox right = new HBox();

        // equal growth on both sides keeps the transport in the middle of the bar
        for (HBox side : new HBox[]{left, right}) {
            side.setMinWidth(0);
            side.setPrefWidth(0);
            HBox.setHgrow(side, Priority.ALWAYS);
        }
        getChildren().addAll(left, centre, right);

        document.addListener((obs, oldDoc, newDoc) -> bind(newDoc));
        bind(null);
    }

    /**
     * The document whose playhead, duration and frame rate the bar shows.
     *
     * @return the document property
     */
    public final ObjectProperty<EditorDocument> documentProperty() {
        return document;
    }

    public final EditorDocument getDocument() {
        return document.get();
    }

    public final void setDocument(EditorDocument value) {
        document.set(value);
    }

    /**
     * Whether playback starts over at the end.
     *
     * @return the loop property
     */
    public final BooleanProperty loopProperty() {
        return loop;
    }

    public final boolean isLoop() {
        return loop.get();
    }

    public final void setLoop(boolean value) {
        loop.set(value);
    }

    /**
     * Whether the sound of the editor is switched off: playback and scrubbing are silent.
     *
     * @return the muted property
     */
    public final BooleanProperty mutedProperty() {
        return muted;
    }

    public final boolean isMuted() {
        return muted.get();
    }

    public final void setMuted(boolean value) {
        muted.set(value);
    }

    /**
     * Whether the document is playing; it only selects the icon of the play button.
     *
     * @return the playing property
     */
    public final BooleanProperty playingProperty() {
        return playing;
    }

    public final boolean isPlaying() {
        return playing.get();
    }

    public final void setPlaying(boolean value) {
        playing.set(value);
    }

    /**
     * Called when the play button is pressed. The button is disabled while this is {@code null}.
     *
     * @return the on-play-pause property
     */
    public final ObjectProperty<Runnable> onPlayPauseProperty() {
        return onPlayPause;
    }

    public final Runnable getOnPlayPause() {
        return onPlayPause.get();
    }

    public final void setOnPlayPause(Runnable value) {
        onPlayPause.set(value);
    }

    private void seek(double seconds) {
        EditorDocument doc = getDocument();
        if (doc != null) {
            doc.setPlayhead(seconds);
        }
    }

    private void bind(EditorDocument doc) {
        timecode.textProperty().unbind();
        frameRate.textProperty().unbind();
        if (doc == null) {
            timecode.setText(Formats.timecode(0) + " / " + Formats.timecode(0));
            frameRate.setText("");
            return;
        }
        timecode.textProperty().bind(Bindings.createStringBinding(() -> {
            double duration = doc.getAnimation().duration();
            double time = Math.max(0, Math.min(doc.getPlayhead(), duration));
            return Formats.timecode(time) + " / " + Formats.timecode(duration);
        }, doc.playheadProperty(), doc.animationProperty()));
        frameRate.textProperty().bind(Bindings.createStringBinding(
                () -> Formats.frameRate(doc.getAnimation().frameRate()), doc.animationProperty()));
    }

    private static Label pill(Label label, String id) {
        label.setId(id);
        label.getStyleClass().addAll("pill", "mono");
        label.setMinWidth(USE_PREF_SIZE);
        return label;
    }

    private static Button iconButton(String id, Ikon icon, String tooltip) {
        return iconButton(id, new FontIcon(icon), tooltip);
    }

    private static Button iconButton(String id, FontIcon icon, String tooltip) {
        Button button = new Button(null, icon);
        button.setId(id);
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }
}
