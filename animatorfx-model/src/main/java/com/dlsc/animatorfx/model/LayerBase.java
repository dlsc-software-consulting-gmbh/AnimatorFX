package com.dlsc.animatorfx.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What every layer has in common. The layer renders only within {@code inPoint..outPoint}
 * (seconds). {@code mask} and {@code motionPath} may be {@code null}. An empty
 * {@code exportName} keeps the layer private; a nonempty name exposes it to the host application
 * and must be unique throughout the document, including nested groups.
 */
public record LayerBase(String id, String name, boolean visible, boolean locked, double inPoint, double outPoint,
                        Transform transform, Animatable<Double> opacity, Mask mask,
                        List<Effect> effects, MotionPath motionPath, String exportName) {

    public LayerBase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(opacity, "opacity");
        effects = List.copyOf(effects);
        exportName = Objects.requireNonNull(exportName, "exportName").strip();
    }

    /**
     * Creates a layer base without an application-facing export name.
     */
    public LayerBase(String id, String name, boolean visible, boolean locked, double inPoint, double outPoint,
                     Transform transform, Animatable<Double> opacity, Mask mask,
                     List<Effect> effects, MotionPath motionPath) {
        this(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects, motionPath, "");
    }

    public LayerBase withExportName(String exportName) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    /**
     * A visible, unlocked, fully opaque layer base with a new random id, the identity transform
     * and the time range {@code 0..duration}.
     */
    public static LayerBase create(String name, double duration) {
        return new LayerBase(UUID.randomUUID().toString(), name, true, false, 0, duration, Transform.IDENTITY,
                Animatable.of(1.0), null, List.of(), null);
    }

    public LayerBase withId(String id) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withName(String name) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withVisible(boolean visible) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withLocked(boolean locked) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withInPoint(double inPoint) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withOutPoint(double outPoint) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withTransform(Transform transform) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withOpacity(Animatable<Double> opacity) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    /**
     * @param mask the mask, or {@code null} for none
     */
    public LayerBase withMask(Mask mask) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    public LayerBase withEffects(List<Effect> effects) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }

    /**
     * @param motionPath the motion path, or {@code null} for none
     */
    public LayerBase withMotionPath(MotionPath motionPath) {
        return new LayerBase(id, name, visible, locked, inPoint, outPoint, transform, opacity, mask, effects,
                motionPath, exportName);
    }
}
