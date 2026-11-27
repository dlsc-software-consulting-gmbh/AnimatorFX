package com.dlsc.animatorfx.model;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An animation document. Size is in document pixels, duration in seconds; the frame rate is used
 * for snapping and display only. Layers are ordered bottom to top. Cues are sounds triggered at
 * points in time.
 */
public record Animation(int formatVersion, String name, double width, double height, double frameRate,
                        double duration, Rgba background, List<Marker> markers, List<Asset> assets,
                        List<Layer> layers, List<SoundCue> cues, List<StateMachine> stateMachines) {

    public static final int CURRENT_FORMAT = 4;

    public Animation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(background, "background");
        markers = List.copyOf(markers);
        assets = List.copyOf(assets);
        layers = List.copyOf(layers);
        cues = List.copyOf(cues);
        stateMachines = List.copyOf(stateMachines);
        validateExportNames(layers);
        Set<String> machineNames = new HashSet<>();
        for (StateMachine machine : stateMachines) {
            if (!machineNames.add(machine.name())) {
                throw new IllegalArgumentException("Duplicate state machine: " + machine.name());
            }
        }
    }

    private static void validateExportNames(List<Layer> layers) {
        Set<String> names = new HashSet<>();
        ArrayDeque<Layer> pending = new ArrayDeque<>(layers);
        while (!pending.isEmpty()) {
            Layer layer = pending.removeFirst();
            String name = layer.base().exportName();
            if (!name.isEmpty() && !names.add(name)) {
                throw new IllegalArgumentException("Duplicate export name: " + name);
            }
            if (layer instanceof GroupLayer group) {
                pending.addAll(group.children());
            }
        }
    }

    /**
     * A document without state machines.
     */
    public Animation(int formatVersion, String name, double width, double height, double frameRate,
                     double duration, Rgba background, List<Marker> markers, List<Asset> assets,
                     List<Layer> layers, List<SoundCue> cues) {
        this(formatVersion, name, width, height, frameRate, duration, background, markers, assets, layers,
                cues, List.of());
    }

    /**
     * A document without sound cues.
     */
    public Animation(int formatVersion, String name, double width, double height, double frameRate,
                     double duration, Rgba background, List<Marker> markers, List<Asset> assets,
                     List<Layer> layers) {
        this(formatVersion, name, width, height, frameRate, duration, background, markers, assets, layers,
                List.of());
    }

    /**
     * An empty document at 60 frames per second with a transparent background.
     */
    public static Animation create(String name, double width, double height, double duration) {
        return new Animation(CURRENT_FORMAT, name, width, height, 60, duration, Rgba.TRANSPARENT, List.of(),
                List.of(), List.of());
    }

    public Optional<Marker> marker(String name) {
        return markers.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    public Animation withFormatVersion(int formatVersion) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withName(String name) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withWidth(double width) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withHeight(double height) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withFrameRate(double frameRate) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withDuration(double duration) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withBackground(Rgba background) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withMarkers(List<Marker> markers) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withAssets(List<Asset> assets) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withLayers(List<Layer> layers) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withCues(List<SoundCue> cues) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }

    public Animation withStateMachines(List<StateMachine> stateMachines) {
        return new Animation(formatVersion, name, width, height, frameRate, duration, background, markers, assets,
                layers, cues, stateMachines);
    }
}
