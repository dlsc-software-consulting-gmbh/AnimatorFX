package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.doc.Command;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.StateMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The marker edits that the timeline and the Document tab share. Markers are addressed by
 * name, which is unique in a document.
 */
public final class Markers {

    /** The length of a new marker in seconds. */
    public static final double DEFAULT_LENGTH = 0.5;

    private Markers() {
    }

    /** {@code marker1}, {@code marker2}, …: the first one that is free. */
    public static String nextName(Animation animation) {
        for (int i = 1; ; i++) {
            String name = "marker" + i;
            if (animation.marker(name).isEmpty()) {
                return name;
            }
        }
    }

    /**
     * Why a marker cannot have that name: empty, or taken by another marker.
     *
     * @param currentNameOrNull the present name of the marker being renamed, which stays allowed
     */
    public static Optional<String> nameError(Animation animation, String name, String currentNameOrNull) {
        if (name == null || name.isBlank()) {
            return Optional.of("A marker needs a name");
        }
        if (!name.equals(currentNameOrNull) && animation.marker(name).isPresent()) {
            return Optional.of("There is already a marker named " + name);
        }
        return Optional.empty();
    }

    /**
     * A marker range inside {@code 0..duration} that is at least one frame long (when the
     * duration has room for it) and starts before it ends.
     */
    public static Marker limited(Marker marker, double duration, double frameRate) {
        double minLength = Math.min(TimeScale.frame(frameRate), Math.max(duration, 0));
        double start = Math.clamp(Math.min(marker.start(), marker.end()), 0, Math.max(0, duration - minLength));
        double end = Math.clamp(Math.max(marker.start(), marker.end()), start + minLength, Math.max(duration, start + minLength));
        return new Marker(marker.name(), start, end);
    }

    /** Adds a marker of the default length at the time, with the next free name. */
    public static Command add(double time) {
        return Commands.setDocument("Add Marker", a -> {
            double start = Math.clamp(time, 0, Math.max(0, a.duration() - DEFAULT_LENGTH));
            Marker marker = limited(new Marker(nextName(a), start, start + DEFAULT_LENGTH), a.duration(), a.frameRate());
            List<Marker> markers = new ArrayList<>(a.markers());
            markers.add(marker);
            return a.withMarkers(markers);
        });
    }

    public static Command remove(String name) {
        return Commands.setDocument("Delete Marker", a -> {
            List<Marker> markers = new ArrayList<>(a.markers());
            return markers.removeIf(m -> m.name().equals(name)) ? a.withMarkers(markers) : a;
        });
    }

    /** Renames a marker; does nothing when the new name is not allowed. */
    public static Command rename(String name, String newName) {
        return Commands.setDocument("Rename Marker", a -> {
            if (nameError(a, newName, name).isPresent() || a.marker(name).isEmpty()) {
                return a;
            }
            Animation renamed = replace(a, name, m -> new Marker(newName, m.start(), m.end()));
            List<StateMachine> machines = a.stateMachines().stream()
                    .map(machine -> new StateMachine(machine.name(), machine.initialState(),
                            machine.states().stream().map(state -> state.marker().equals(name)
                                    ? new StateMachine.State(state.name(), newName, state.loop(), state.x(), state.y())
                                    : state).toList(), machine.transitions()))
                    .toList();
            return renamed.withStateMachines(machines);
        });
    }

    /** Sets the range of a marker, limited as in {@link #limited}. */
    public static Command setRange(String commandName, String name, double start, double end) {
        return Commands.setDocument(commandName,
                a -> replace(a, name, m -> limited(new Marker(name, start, end), a.duration(), a.frameRate())));
    }

    private static Animation replace(Animation animation, String name, UnaryOperator<Marker> fn) {
        List<Marker> markers = new ArrayList<>(animation.markers());
        boolean changed = false;
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).name().equals(name)) {
                markers.set(i, fn.apply(markers.get(i)));
                changed = true;
            }
        }
        return changed ? animation.withMarkers(markers) : animation;
    }
}
