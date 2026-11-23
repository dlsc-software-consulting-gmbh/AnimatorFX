package com.dlsc.animatorfx.editor.canvas;

import com.dlsc.animatorfx.editor.audio.EditorAudio;
import com.dlsc.animatorfx.editor.video.EditorVideo;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.AnimationView;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Plays the document in the canvas: the player of the canvas view runs and drives the playhead
 * of the document, so everything that follows the playhead follows playback. It is also the one
 * player of the editor that makes sound: while playing, and for a moment when scrubbing.
 */
final class Playback {

    private static final long SCRUB_INTERVAL_NANOS = 50_000_000L;

    private final AnimationView view;
    /** The sound of the player: kept across edits, see {@link EditorAudio#install}. */
    private final EditorAudio.Installed audio;
    private final ReadOnlyBooleanWrapper playing = new ReadOnlyBooleanWrapper(this, "playing");
    private final BooleanProperty loop = new SimpleBooleanProperty(this, "loop");
    private EditorDocument document;
    private boolean driving;
    private long lastScrub;

    Playback(AnimationView view, java.util.function.Consumer<String> notice) {
        this.view = view;
        this.audio = EditorAudio.install(view.getPlayer(), this::isPlaying);
        EditorVideo.install(view.getPlayer(), notice);
        view.statusProperty().addListener((obs, oldStatus, status) -> {
            playing.set(status == AnimationPlayer.Status.PLAYING);
            pushTime();
        });
        view.currentTimeProperty().addListener((obs, oldTime, time) -> {
            if (playing.get()) {
                pushTime();
            }
        });
        loop.addListener((obs, was, now) -> view.setCycleCount(now ? AnimationPlayer.INDEFINITE : 1));
    }

    /**
     * Whether the document is playing.
     *
     * @return the playing property
     */
    final ReadOnlyBooleanProperty playingProperty() {
        return playing.getReadOnlyProperty();
    }

    final boolean isPlaying() {
        return playing.get();
    }

    /**
     * Whether playback starts over at the end.
     *
     * @return the loop property
     */
    final BooleanProperty loopProperty() {
        return loop;
    }

    final boolean isLoop() {
        return loop.get();
    }

    final void setLoop(boolean value) {
        loop.set(value);
    }

    /** Another document, or none: playback stops and the sound of the previous one is released for good. */
    void setDocument(EditorDocument document) {
        view.pause();
        audio.release();
        view.getPlayer().dispose();
        this.document = document;
    }

    /** The window closes: no sound from here on. */
    void dispose() {
        view.pause();
        audio.release();
        view.dispose();
        document = null;
    }

    void toggle() {
        if (document == null) {
            return;
        }
        if (isPlaying()) {
            pause();
        } else {
            view.seek(document.getPlayhead());
            view.play();
        }
    }

    /**
     * The user stops playback: unlike a player that is paused, the editor also cuts the sound
     * cues that are sounding. Playback that runs into the end lets them ring out.
     */
    void pause() {
        view.pause();
        audio.stopCues();
    }

    /**
     * Lets the sound at a time be heard for a moment, as the user drags the playhead. Not while
     * playing, and not more often than every {@link #SCRUB_INTERVAL_NANOS}: a fast drag would
     * otherwise ask for a snippet on every pixel.
     */
    void scrub(double seconds) {
        if (document == null || isPlaying()) {
            return;
        }
        long now = System.nanoTime();
        if (lastScrub != 0 && now - lastScrub < SCRUB_INTERVAL_NANOS) {
            return;
        }
        lastScrub = now;
        view.scrub(seconds);
    }

    /** The playhead was moved: by playback itself, or by the user, which the view then follows. */
    void playheadChanged() {
        if (!driving && document != null) {
            view.seek(document.getPlayhead());
            // a jump, such as going to the start: what sounded belongs to where playback was
            audio.stopCues();
        }
    }

    private void pushTime() {
        if (document == null || driving) {
            return;
        }
        driving = true;
        try {
            document.setPlayhead(view.getCurrentTime());
        } finally {
            driving = false;
        }
    }
}
