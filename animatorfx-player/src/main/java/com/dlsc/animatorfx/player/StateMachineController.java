package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;

import java.util.Objects;

/**
 * Runs a state machine on an existing player. Use on the JavaFX application thread. While
 * active, the controller owns the player's playback; stop it before controlling playback
 * directly. Replacing the player's animation stops the controller.
 */
public final class StateMachineController {

    private final AnimationPlayer player;
    private final StateMachine machine;
    private final Runnable completion = this::completed;
    private final ChangeListener<AnimationFile> animationListener = (observable, oldFile, newFile) -> stop();
    private final ReadOnlyStringWrapper state = new ReadOnlyStringWrapper(this, "state");
    private boolean active;
    private long generation;

    public StateMachineController(AnimationPlayer player, StateMachine machine) {
        this.player = Objects.requireNonNull(player, "player");
        this.machine = Objects.requireNonNull(machine, "machine");
    }

    /**
     * The current state name, or null before the controller is started.
     *
     * @return the state property
     */
    public final ReadOnlyStringProperty stateProperty() {
        return state.getReadOnlyProperty();
    }

    public final String getState() {
        return state.get();
    }

    /**
     * Validates marker references and starts from the initial state.
     *
     * @throws IllegalStateException if there is no animation, another controller is active,
     *                               or the player's cycle count or auto reverse property is bound
     * @throws IllegalArgumentException if a marker cannot be played
     */
    public void start() {
        AnimationFile file = player.getAnimation();
        if (file == null) {
            throw new IllegalStateException("No animation");
        }
        machine.validateMarkers(file.animation());
        checkPlaybackProperties();
        if (!active) {
            player.attachStateMachine(completion);
            player.animationProperty().addListener(animationListener);
            active = true;
        }
        try {
            enter(machine.initialState());
        } catch (RuntimeException | Error ex) {
            stop();
            throw ex;
        }
    }

    /**
     * Sends an event, immediately interrupting playback when a transition matches.
     *
     * @return whether a transition matched
     */
    public boolean send(String event) {
        Objects.requireNonNull(event, "event");
        if (!active) {
            return false;
        }
        for (StateMachine.Transition transition : machine.transitions()) {
            if (transition.source().equals(getState()) && event.equals(transition.event())) {
                enter(transition.target());
                return true;
            }
        }
        return false;
    }

    /** Stops playback and detaches the controller. */
    public void stop() {
        if (active) {
            active = false;
            generation++;
            player.animationProperty().removeListener(animationListener);
            player.detachStateMachine(completion);
            player.stop();
        }
    }

    private void completed() {
        if (active) {
            for (StateMachine.Transition transition : machine.transitions()) {
                if (transition.source().equals(getState()) && transition.event() == null) {
                    enter(transition.target());
                    return;
                }
            }
        }
    }

    private void enter(String name) {
        checkPlaybackProperties();
        long entry = ++generation;
        StateMachine.State target = machine.states().stream().filter(s -> s.name().equals(name))
                .findFirst().orElseThrow();
        state.set(name);
        if (!active || generation != entry) return;
        player.setAutoReverse(false);
        if (!active || generation != entry) return;
        player.setCycleCount(target.loop() ? AnimationPlayer.INDEFINITE : 1);
        if (!active || generation != entry) return;
        player.playMarker(target.marker());
    }

    private void checkPlaybackProperties() {
        if (player.autoReverseProperty().isBound() || player.cycleCountProperty().isBound()) {
            throw new IllegalStateException("State machine playback properties must not be bound");
        }
    }
}
