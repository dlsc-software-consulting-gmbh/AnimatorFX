package com.dlsc.animatorfx.editor.doc;

import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometries;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.PropertyRef;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationJson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The edits of the editor. Every factory returns a pure {@link Command}; a command that would
 * change nothing returns the animation instance it was given. Unknown layer ids are ignored,
 * unknown property keys and values of the wrong type throw {@link IllegalArgumentException}.
 */
public final class Commands {

    private Commands() {
    }

    public static Command addLayer(String parentIdOrNull, int index, Layer layer) {
        Objects.requireNonNull(layer, "layer");
        return command("Add Layer", a -> Layers.insert(a, parentIdOrNull, index, layer));
    }

    /**
     * Removes the layers with their children and clears every mask that pointed to a removed layer.
     */
    public static Command removeLayers(Collection<String> ids) {
        List<String> copy = List.copyOf(ids);
        return command(copy.size() == 1 ? "Delete Layer" : "Delete Layers", before -> {
            Animation after = before;
            for (String id : copy) {
                after = Layers.remove(after, id);
            }
            if (after == before) {
                return before;
            }
            Set<String> removed = layerIds(before);
            removed.removeAll(layerIds(after));
            return after.withLayers(mapLayers(after.layers(), layer -> {
                Mask mask = layer.base().mask();
                return mask != null && removed.contains(mask.layerId())
                        ? layer.withBase(layer.base().withMask(null)) : layer;
            }));
        });
    }

    /**
     * Deep-copies the layers with fresh layer and keyframe ids and inserts each copy directly
     * above its original. A layer inside a group that is duplicated as well is not copied twice.
     * Masks inside the copies point to the copied mask layer when that was duplicated too. A
     * copied slot gets the next free slot name: slot names are unique in a document. Visual
     * copies are offset in their parent's coordinate space; audio layers have no position.
     */
    public static Command duplicateLayers(Collection<String> ids) {
        Set<String> wanted = new LinkedHashSet<>(ids);
        return command(wanted.size() == 1 ? "Duplicate Layer" : "Duplicate Layers", before -> {
            List<Layer> originals = new ArrayList<>();
            collectTopMost(before.layers(), wanted, originals);
            Map<String, String> newIds = new HashMap<>();
            for (Layer original : originals) {
                for (Layer layer : subtree(original)) {
                    newIds.put(layer.id(), UUID.randomUUID().toString());
                }
            }
            Animation after = before;
            Set<String> slotNames = slotNames(before);
            for (Layer original : originals) {
                Layer copy = translated(uniqueSlots(copy(original, newIds), slotNames), new Vec2(10, 10));
                copy = copy.withBase(copy.base().withName(original.name() + " copy"));
                String parentId = Layers.parentOf(after, original.id()).map(Layer::id).orElse(null);
                List<Layer> siblings = parentId == null ? after.layers()
                        : ((GroupLayer) Layers.find(after, parentId).orElseThrow()).children();
                after = Layers.insert(after, parentId, indexOf(siblings, original.id()) + 1, copy);
            }
            return after;
        });
    }

    /**
     * Why the document cannot be nested as it is, as a sentence for the user: a file holds
     * layers up to {@link AnimationJson#MAX_LAYER_DEPTH} levels deep. Empty within the limit.
     * Whatever nests layers deeper checks its result with this before it runs.
     */
    public static Optional<String> nestingObstacle(Animation animation) {
        return exceeds(animation.layers(), AnimationJson.MAX_LAYER_DEPTH)
                ? Optional.of("Layers cannot be nested deeper than " + AnimationJson.MAX_LAYER_DEPTH + " levels.")
                : Optional.empty();
    }

    private static boolean exceeds(List<Layer> layers, int levelsLeft) {
        if (layers.isEmpty()) {
            return false;
        }
        if (levelsLeft == 0) {
            return true;
        }
        for (Layer layer : layers) {
            if (layer instanceof GroupLayer group && exceeds(group.children(), levelsLeft - 1)) {
                return true;
            }
        }
        return false;
    }

    /** The slot names the document uses. */
    public static Set<String> slotNames(Animation animation) {
        Set<String> names = new HashSet<>();
        for (SlotLayer slot : Layers.slots(animation)) {
            names.add(slot.slotName());
        }
        return names;
    }

    /** The first of "slot1", "slot2", … that is not among the names. */
    public static String nextSlotName(Set<String> used) {
        int number = 1;
        while (used.contains("slot" + number)) {
            number++;
        }
        return "slot" + number;
    }

    /**
     * The layer with every slot in it, at any depth, that has one of the names renamed to the
     * next free slot name. The names of all its slots are added to the set, so that layers
     * that are made unique one after the other do not collide with each other either.
     */
    public static Layer uniqueSlots(Layer layer, Set<String> used) {
        if (layer instanceof SlotLayer slot) {
            SlotLayer unique = used.contains(slot.slotName()) ? slot.withSlotName(nextSlotName(used)) : slot;
            used.add(unique.slotName());
            return unique;
        }
        if (layer instanceof GroupLayer group) {
            List<Layer> children = new ArrayList<>();
            for (Layer child : group.children()) {
                children.add(uniqueSlots(child, used));
            }
            return group.withChildren(children);
        }
        return layer;
    }

    public static Command updateLayer(String name, String id, UnaryOperator<Layer> fn) {
        Objects.requireNonNull(fn, "fn");
        return command(name, a -> editLayer(a, id, fn));
    }

    public static Command setProperty(String id, String key, Animatable<?> value) {
        Objects.requireNonNull(value, "value");
        return command("Change " + label(key), a -> editLayer(a, id, layer -> LayerProperties.set(layer, key, value)));
    }

    /**
     * Sets the value of a property at a time, following the auto-key rule: with auto-key on, a
     * keyframe at that time is added or updated (a static property becomes keyframed with that
     * single keyframe). With auto-key off, a static property gets a new static value and an
     * animated property gets its keyframe at that time updated, or a new keyframe when there is
     * none. New keyframes ease linearly; an updated keyframe keeps its id and easing. A time
     * before 0 counts as 0: a file cannot hold keyframes at negative times.
     */
    public static Command setValueAt(String id, String key, double time, Object value, boolean autoKey) {
        Objects.requireNonNull(value, "value");
        double at = keyTime(time);
        return command("Change " + label(key), a -> editLayer(a, id,
                layer -> LayerProperties.set(layer, key, valueAt(LayerProperties.get(layer, key), at, value, autoKey))));
    }

    /**
     * Adds a keyframe with the value the property currently has at that time. Does nothing when
     * there is a keyframe at that time already. A time before 0 counts as 0.
     */
    public static Command addKeyframe(String id, String key, double time) {
        double at = keyTime(time);
        return command("Add Keyframe", a -> editLayer(a, id,
                layer -> LayerProperties.set(layer, key, withSampledKeyframe(LayerProperties.get(layer, key), at))));
    }

    /** A time a keyframe can have: not negative, and 0 for a time that is no number. */
    private static double keyTime(double time) {
        return Double.isFinite(time) ? Math.max(0, time) : 0;
    }

    public static Command removeKeyframes(Collection<KeyframeRef> refs) {
        return keyframeCommand(refs.size() == 1 ? "Delete Keyframe" : "Delete Keyframes", refs, (value, ids) -> {
            Animatable<?> result = value;
            for (String keyframeId : ids) {
                result = result.withoutKeyframe(keyframeId);
            }
            return result;
        });
    }

    /**
     * Shifts the keyframes in time. Every resulting time is clamped to {@code >= 0}; a moved
     * keyframe that lands on an unmoved keyframe of the same property replaces it. When several
     * moved keyframes end at the same time, the one that was latest before the move stays.
     */
    public static Command moveKeyframes(Collection<KeyframeRef> refs, double deltaSeconds) {
        return keyframeCommand(refs.size() == 1 ? "Move Keyframe" : "Move Keyframes", refs,
                (value, ids) -> moved(value, ids, deltaSeconds));
    }

    public static Command setEasing(Collection<KeyframeRef> refs, Easing easing) {
        Objects.requireNonNull(easing, "easing");
        return keyframeCommand("Change Easing", refs, (value, ids) -> withEasing(value, ids, easing));
    }

    /**
     * Moves the layer as {@link Layers#move} does: the index counts in the target list after the
     * layer has been taken out. A mask needs its layer in the same parent: when the layer changes
     * its parent, its own mask is cleared and so are the masks that pointed to it.
     */
    public static Command moveLayer(String id, String newParentIdOrNull, int index) {
        return command("Move Layer", before -> move(before, List.of(new LayerMove(id, newParentIdOrNull, index))));
    }

    /** One step of {@link #moveLayers}: a layer, its new parent ({@code null}: the top level) and its index there. */
    public record LayerMove(String id, String parentId, int index) {
    }

    /**
     * Moves several layers, one after the other as {@link #moveLayer} would, as one command. The
     * masks are looked at once, when all layers are where they go: a masked layer that moves to
     * another parent together with its mask layer keeps its mask; a mask whose two layers end up
     * in different parents is cleared.
     */
    public static Command moveLayers(List<LayerMove> moves) {
        List<LayerMove> copy = List.copyOf(moves);
        return command(copy.size() == 1 ? "Move Layer" : "Move Layers", before -> move(before, copy));
    }

    private static Animation move(Animation before, List<LayerMove> moves) {
        Animation moved = before;
        Set<String> ids = new HashSet<>();
        for (LayerMove move : moves) {
            if (Layers.find(moved, move.id()).isPresent()) {
                moved = Layers.move(moved, move.id(), move.parentId(), move.index());
                ids.add(move.id());
            }
        }
        if (ids.isEmpty()) {
            return before;
        }
        Animation placed = moved;
        Animation after = placed.withLayers(mapLayers(placed.layers(), layer -> {
            Mask mask = layer.base().mask();
            boolean concerned = mask != null && (ids.contains(layer.id()) || ids.contains(mask.layerId()));
            return concerned && !areSiblings(placed, layer.id(), mask.layerId())
                    ? layer.withBase(layer.base().withMask(null)) : layer;
        }));
        return after.layers().equals(before.layers()) ? before : after;
    }

    // ------------------------------------------------------------------ masks, effects, trim, motion paths

    /**
     * Masks a layer with another layer of the same parent, or removes its mask with {@code null}.
     * Does nothing when the mask layer {@link #canMask cannot mask} the layer.
     */
    public static Command setMask(String id, Mask maskOrNull) {
        return command(maskOrNull == null ? "Remove Mask" : "Set Mask", before -> {
            if (maskOrNull != null && !canMask(before, id, maskOrNull.layerId())) {
                return before;
            }
            return editLayer(before, id, layer -> layer.withBase(layer.base().withMask(maskOrNull)));
        });
    }

    /**
     * Whether a layer can be the mask of another: they are different children of the same parent,
     * neither is an audio layer, and the mask layer is not itself masked by the layer, directly
     * or through other masks. A layer that masks is not drawn, so two layers that mask each
     * other would both disappear.
     */
    public static boolean canMask(Animation animation, String id, String maskLayerId) {
        if (!areSiblings(animation, id, maskLayerId)
                || Layers.find(animation, id).orElse(null) instanceof AudioLayer
                || Layers.find(animation, maskLayerId).orElse(null) instanceof AudioLayer) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        String current = maskLayerId;
        while (current != null && seen.add(current)) {
            Mask mask = Layers.find(animation, current).map(layer -> layer.base().mask()).orElse(null);
            current = mask == null ? null : mask.layerId();
            if (id.equals(current)) {
                return false;
            }
        }
        return true;
    }

    /** Adds an effect after the effects the layer has. */
    public static Command addEffect(String id, Effect effect) {
        Objects.requireNonNull(effect, "effect");
        return command("Add Effect", a -> editEffects(a, id, effects -> effects.add(effect)));
    }

    public static Command removeEffect(String id, int index) {
        return command("Remove Effect", a -> editEffects(a, id, effects -> {
            if (index >= 0 && index < effects.size()) {
                effects.remove(index);
            }
        }));
    }

    /**
     * Moves an effect to another place in the list. The effect moves as a whole, so its
     * keyframes stay with it although the property keys of the effects change.
     */
    public static Command moveEffect(String id, int from, int to) {
        return command("Reorder Effects", a -> editEffects(a, id, effects -> {
            if (from >= 0 && from < effects.size() && to >= 0 && to < effects.size()) {
                effects.add(to, effects.remove(from));
            }
        }));
    }

    private static Animation editEffects(Animation animation, String id, Consumer<List<Effect>> edit) {
        return editLayer(animation, id, layer -> {
            List<Effect> effects = new ArrayList<>(layer.base().effects());
            edit.accept(effects);
            return layer.withBase(layer.base().withEffects(effects));
        });
    }

    /**
     * Gives a shape layer a trim that shows the whole stroke (start 0, end 1, offset 0), or takes
     * its trim away with all its keyframes. A layer that already has a trim keeps it.
     */
    public static Command setTrimEnabled(String id, boolean enabled) {
        return command(enabled ? "Enable Trim" : "Remove Trim", a -> editLayer(a, id, layer -> {
            if (!(layer instanceof ShapeLayer shape) || (shape.trim() != null) == enabled) {
                return layer;
            }
            return shape.withTrim(enabled ? new Trim(Animatable.of(0.0), Animatable.of(1.0), Animatable.of(0.0)) : null);
        }));
    }

    /** Sets or replaces the motion path of a layer, or removes it with {@code null}. */
    public static Command setMotionPath(String id, MotionPath motionPathOrNull) {
        return command(motionPathOrNull == null ? "Remove Motion Path" : "Set Motion Path",
                a -> editLayer(a, id, layer -> layer.withBase(layer.base().withMotionPath(motionPathOrNull))));
    }

    /**
     * Puts a layer on a motion path, in the coordinates of its parent, and makes it travel: the
     * progress gets a keyframe of 0 at the layer's in-point and one of 1 at its out-point (both
     * limited to the document), easing in and out. A layer without a time range gets a static 0.
     */
    public static Command addMotionPath(String id, PathData path) {
        Objects.requireNonNull(path, "path");
        return command("Add Motion Path", a -> editLayer(a, id, layer -> {
            double from = Math.clamp(layer.base().inPoint(), 0, Math.max(0, a.duration()));
            double to = Math.clamp(layer.base().outPoint(), 0, Math.max(0, a.duration()));
            Animatable<Double> progress = Animatable.of(0.0);
            if (to - from > Animatable.TIME_EPSILON) {
                progress = progress.withKeyframe(from, 0.0, Easing.presets().get("Ease In Out"))
                        .withKeyframe(to, 1.0, Easing.LINEAR);
            }
            return layer.withBase(layer.base().withMotionPath(new MotionPath(path, progress, false)));
        }));
    }

    /**
     * Replaces the rectangle, ellipse, line, polygon or star of shape layers with the Bezier path
     * of its outline at the given time. Other layers, paths, and shapes with an
     * {@link #convertObstacle obstacle} are left alone.
     */
    public static Command convertToPath(Collection<String> ids, double time) {
        List<String> copy = List.copyOf(ids);
        return command("Convert to Path", before -> {
            Animation after = before;
            for (String id : copy) {
                after = editLayer(after, id, layer -> {
                    if (!(layer instanceof ShapeLayer shape) || shape.geometry() instanceof Geometry.PathGeometry
                            || hasAnimatedGeometry(shape)) {
                        return layer;
                    }
                    return shape.withGeometry(new Geometry.PathGeometry(
                            Animatable.of(Geometries.toPath(shape.geometry(), time))));
                });
            }
            return after;
        });
    }

    /**
     * Why {@link #convertToPath} would leave one of the layers alone, as a sentence for the user:
     * a path has one outline per keyframe, so the keyframes of a size or a radius would be lost.
     */
    public static Optional<String> convertObstacle(Animation animation, Collection<String> ids) {
        for (String id : ids) {
            Layer layer = Layers.find(animation, id).orElse(null);
            if (layer instanceof ShapeLayer shape && hasAnimatedGeometry(shape)) {
                return Optional.of("\u201c" + layer.name() + "\u201d has geometry keyframes, which a path cannot keep."
                        + " Remove them before converting.");
            }
        }
        return Optional.empty();
    }

    private static boolean hasAnimatedGeometry(ShapeLayer shape) {
        for (PropertyRef ref : LayerProperties.list(shape)) {
            if (ref.key().startsWith("geometry.") && ref.value().isAnimated()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the two layers are different children of the same parent. */
    private static boolean areSiblings(Animation animation, String id, String otherId) {
        if (id.equals(otherId) || Layers.find(animation, id).isEmpty() || Layers.find(animation, otherId).isEmpty()) {
            return false;
        }
        return Objects.equals(Layers.parentOf(animation, id).map(Layer::id).orElse(null),
                Layers.parentOf(animation, otherId).map(Layer::id).orElse(null));
    }

    // ------------------------------------------------------------------ audio

    /**
     * Adds an audio layer for an audio asset on top of the other layers. It starts at the frame
     * of the time, or at 0 when the time is the end of the document, has no clip offset and full
     * volume, and lasts as long as its clip, at most to the end of the document; a clip of
     * unknown length (not a positive number) lasts to the end. Does nothing when the document
     * has no audio asset with that id.
     */
    public static Command addAudioLayer(String layerId, String name, String assetId, double time, double clipDuration) {
        Objects.requireNonNull(layerId, "layerId");
        Objects.requireNonNull(name, "name");
        return command("Add Audio", a -> {
            if (!isAudioAsset(a, assetId)) {
                return a;
            }
            double duration = Math.max(0, a.duration());
            double in = a.frameRate() > 0 ? Math.round(keyTime(time) * a.frameRate()) / a.frameRate() : keyTime(time);
            if (in >= duration - Animatable.TIME_EPSILON) {
                in = 0;
            }
            double out = Double.isFinite(clipDuration) && clipDuration > 0 ? Math.min(in + clipDuration, duration) : duration;
            AudioLayer layer = AudioLayer.create(name, assetId, in, out);
            return Layers.insert(a, null, a.layers().size(), layer.withBase(layer.base().withId(layerId)));
        });
    }

    /** Adds an embedded video at the frame of the playhead, preserving its natural dimensions. */
    public static Command addVideoLayer(String layerId, String name, String assetId, double time,
                                        double clipDuration, double width, double height) {
        Objects.requireNonNull(layerId, "layerId");
        Objects.requireNonNull(name, "name");
        return command("Add Video", animation -> {
            if (animation.assets().stream().noneMatch(asset -> asset.id().equals(assetId)
                    && asset.kind() == Asset.Kind.VIDEO) || !(width > 0) || !Double.isFinite(width)
                    || !(height > 0) || !Double.isFinite(height)) return animation;
            double duration = Math.max(0, animation.duration());
            double in = animation.frameRate() > 0
                    ? Math.round(keyTime(time) * animation.frameRate()) / animation.frameRate() : keyTime(time);
            if (in >= duration - Animatable.TIME_EPSILON) in = 0;
            double out = clipDuration > 0 && Double.isFinite(clipDuration)
                    ? Math.min(in + clipDuration, duration) : duration;
            VideoLayer video = new VideoLayer(LayerBase.create(name, out).withId(layerId).withInPoint(in),
                    assetId, width, height, 0, Animatable.of(1.0), false, false);
            return Layers.insert(animation, null, animation.layers().size(), video);
        });
    }

    /** Sets where in its source an audio or video layer starts, in seconds; never before the start. */
    public static Command setClipOffset(String id, double clipOffset) {
        return command("Change Clip Offset", a -> Double.isFinite(clipOffset) ? editLayer(a, id,
                layer -> layer instanceof AudioLayer audio ? audio.withClipOffset(clipOffset)
                        : layer instanceof VideoLayer video ? video.withClipOffset(clipOffset) : layer) : a);
    }

    /**
     * Adds a sound cue, with its time limited to the document and its volume to {@code 0..1}.
     * Does nothing when the document has no audio asset with the id of the cue, when the time or
     * the volume is no number, or when there is a cue with that id already.
     */
    public static Command addCue(SoundCue cue) {
        Objects.requireNonNull(cue, "cue");
        return command("Add Cue", a -> {
            SoundCue valid = inRange(a, cue);
            if (valid == null || a.cues().stream().anyMatch(other -> other.id().equals(cue.id()))) {
                return a;
            }
            List<SoundCue> cues = new ArrayList<>(a.cues());
            cues.add(valid);
            return a.withCues(cues);
        });
    }

    /**
     * Changes a cue with a function, within the limits of {@link #addCue}; a result outside of
     * them, or with another id that is taken, changes nothing.
     */
    public static Command updateCue(String name, String cueId, UnaryOperator<SoundCue> fn) {
        Objects.requireNonNull(fn, "fn");
        return command(name, a -> {
            List<SoundCue> cues = new ArrayList<>(a.cues());
            for (int i = 0; i < cues.size(); i++) {
                SoundCue cue = cues.get(i);
                if (cue.id().equals(cueId)) {
                    SoundCue changed = inRange(a, fn.apply(cue));
                    if (changed == null || changed.equals(cue) || (!changed.id().equals(cueId)
                            && a.cues().stream().anyMatch(other -> other.id().equals(changed.id())))) {
                        return a;
                    }
                    cues.set(i, changed);
                    return a.withCues(cues);
                }
            }
            return a;
        });
    }

    /** Moves a cue to a time, limited to the document. */
    public static Command moveCue(String cueId, double time) {
        return updateCue("Move Cue", cueId, cue -> cue.withTime(time));
    }

    public static Command removeCues(Collection<String> cueIds) {
        Set<String> ids = Set.copyOf(cueIds);
        return command(ids.size() == 1 ? "Delete Cue" : "Delete Cues", a -> {
            List<SoundCue> cues = new ArrayList<>(a.cues());
            return cues.removeIf(cue -> ids.contains(cue.id())) ? a.withCues(cues) : a;
        });
    }

    /** Whether the document has an audio asset with that id. */
    public static boolean isAudioAsset(Animation animation, String assetId) {
        return animation.assets().stream().anyMatch(a -> a.kind() == Asset.Kind.AUDIO && a.id().equals(assetId));
    }

    /** The cue as a file can hold it, or {@code null} when it cannot be made valid. */
    private static SoundCue inRange(Animation animation, SoundCue cue) {
        if (cue == null || !Double.isFinite(cue.time()) || !Double.isFinite(cue.volume())
                || !isAudioAsset(animation, cue.assetId())) {
            return null;
        }
        double time = Math.clamp(cue.time(), 0, Math.max(0, animation.duration()));
        double volume = Math.clamp(cue.volume(), 0, 1);
        return time == cue.time() && volume == cue.volume() ? cue : cue.withTime(time).withVolume(volume);
    }

    /**
     * The audio layer as a file can hold it: the model does not check ranges, the reader does.
     * Volume within {@code 0..1} (1 for no number), balance within {@code -1..1} (0 for no
     * number), the clip offset not negative.
     */
    private static AudioLayer inRange(AudioLayer layer) {
        Animatable<Double> volume = limited(layer.volume(), 0, 1, 1);
        Animatable<Double> balance = limited(layer.balance(), -1, 1, 0);
        double offset = Double.isFinite(layer.clipOffset()) ? Math.max(0, layer.clipOffset()) : 0;
        if (volume == layer.volume() && balance == layer.balance() && offset == layer.clipOffset()) {
            return layer;
        }
        return layer.withVolume(volume).withBalance(balance).withClipOffset(offset);
    }

    private static Animatable<Double> limited(Animatable<Double> value, double min, double max, double fallback) {
        boolean valid = value.isAnimated()
                ? value.keyframes().stream().allMatch(k -> k.value() >= min && k.value() <= max)
                : value.at(0) >= min && value.at(0) <= max;
        return valid ? value : mapValues(value, v -> Double.isNaN(v) ? fallback : Math.clamp(v, min, max));
    }

    public static Command setDocument(String name, UnaryOperator<Animation> fn) {
        Objects.requireNonNull(fn, "fn");
        return command(name, before -> {
            Animation after = Objects.requireNonNull(fn.apply(before), "animation");
            return after.equals(before) ? before : after;
        });
    }

    /**
     * Runs the commands one after the other as a single command.
     */
    public static Command all(String name, Command... commands) {
        return batch(name, List.of(commands));
    }

    /**
     * Runs the commands one after the other as a single command, and so as one undo step: an
     * edit that applies to several layers at once.
     */
    public static Command batch(String name, List<Command> commands) {
        List<Command> copy = List.copyOf(commands);
        return command(name, before -> {
            Animation after = before;
            for (Command c : copy) {
                after = c.apply(after);
            }
            return after;
        });
    }

    /**
     * A copied keyframe: where it came from, its time relative to the earliest copied keyframe,
     * its value and its easing.
     */
    public record KeyframeCopy(String layerId, String propertyKey, double offset, Object value, Easing easing) {
    }

    /**
     * The keyframes of the references as copies, earliest first. References to keyframes the
     * document does not have are left out.
     */
    public static List<KeyframeCopy> copyKeyframes(Animation animation, Collection<KeyframeRef> refs) {
        record Found(KeyframeRef ref, Keyframe<?> keyframe) {
        }
        List<Found> found = new ArrayList<>();
        for (KeyframeRef ref : refs) {
            Layer layer = Layers.find(animation, ref.layerId()).orElse(null);
            if (layer == null || !hasProperty(layer, ref.propertyKey())) {
                continue;
            }
            for (Keyframe<?> keyframe : LayerProperties.get(layer, ref.propertyKey()).keyframes()) {
                if (keyframe.id().equals(ref.keyframeId())) {
                    found.add(new Found(ref, keyframe));
                }
            }
        }
        found.sort(Comparator.comparingDouble(f -> f.keyframe().time()));
        List<KeyframeCopy> copies = new ArrayList<>();
        for (Found f : found) {
            copies.add(new KeyframeCopy(f.ref().layerId(), f.ref().propertyKey(),
                    f.keyframe().time() - found.get(0).keyframe().time(), f.keyframe().value(), f.keyframe().easing()));
        }
        return List.copyOf(copies);
    }

    /**
     * Adds the copied keyframes to the properties they came from, the earliest one at the time,
     * snapped to the frames of the document, and the others at their offsets after it. Times
     * are limited to {@code 0..duration}: keyframes that would land after the end are put at the
     * end, where the one that was latest in the copy stays. A keyframe that is already at such a
     * time takes the pasted value and easing and keeps its id. Layers and properties that no
     * longer exist are skipped.
     */
    public static Command pasteKeyframes(List<KeyframeCopy> copies, double time) {
        List<KeyframeCopy> list = List.copyOf(copies);
        return command("Paste Keyframes", before -> {
            Animation after = before;
            // earliest first, so of several that end at the same time the later one replaces the earlier
            for (KeyframeCopy copy : list) {
                double at = pasteTime(before, copy, time);
                after = editLayer(after, copy.layerId(), layer -> hasProperty(layer, copy.propertyKey())
                        ? LayerProperties.set(layer, copy.propertyKey(), withKeyframe(
                                LayerProperties.get(layer, copy.propertyKey()), at, copy.value(), copy.easing()))
                        : layer);
            }
            return after;
        });
    }

    /**
     * The time at which {@link #pasteKeyframes} puts a copied keyframe: the paste time snapped
     * to the frame grid, plus the offset of the copy, limited to {@code 0..duration}.
     */
    public static double pasteTime(Animation animation, KeyframeCopy copy, double time) {
        double rate = animation.frameRate();
        double from = keyTime(time);
        double anchor = rate > 0 ? Math.round(from * rate) / rate : from;
        return Math.clamp(anchor + copy.offset(), 0, Math.max(0, animation.duration()));
    }

    private static boolean hasProperty(Layer layer, String key) {
        for (PropertyRef property : LayerProperties.list(layer)) {
            if (property.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    // The cast is checked afterwards: LayerProperties.set rejects values of the wrong type.
    @SuppressWarnings("unchecked")
    private static <T> Animatable<T> withKeyframe(Animatable<T> current, double time, Object value, Easing easing) {
        return current.withKeyframe(time, (T) value, easing);
    }

    /**
     * Inserts the layers into the parent, the first one at the index and the others above it.
     */
    public static Command addLayers(String name, String parentIdOrNull, int index, List<Layer> layers) {
        List<Layer> copy = List.copyOf(layers);
        return command(name, before -> {
            Animation after = before;
            for (int i = 0; i < copy.size(); i++) {
                after = Layers.insert(after, parentIdOrNull, index + i, copy.get(i));
            }
            return after;
        });
    }

    /** How {@link #reorderLayers} moves layers among their siblings. */
    public enum Order {
        FORWARD("Bring Forward"), BACKWARD("Send Backward"), FRONT("Bring to Front"), BACK("Send to Back");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Moves the layers within their own parent: one step up or down past the next layer that
     * is not moved itself, or all the way to the top or bottom. Layers that move together keep
     * their order.
     */
    public static Command reorderLayers(Collection<String> ids, Order order) {
        Set<String> wanted = Set.copyOf(ids);
        Objects.requireNonNull(order, "order");
        return command(order.label(), before -> {
            List<Layer> layers = reorder(before.layers(), wanted, order);
            return layers == before.layers() ? before : before.withLayers(layers);
        });
    }

    /**
     * Wraps layers that share a parent into a new group with an identity transform, placed where
     * the top-most of them was, so nothing moves on screen. Does nothing when the layers do not
     * share a parent, or when the group would separate a layer from its mask.
     */
    public static Command groupLayers(Collection<String> ids, String groupId, String groupName) {
        Set<String> wanted = Set.copyOf(ids);
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(groupName, "groupName");
        return command("Group", before -> {
            if (wanted.isEmpty()) {
                return before;
            }
            String first = wanted.iterator().next();
            if (Layers.find(before, first).isEmpty()) {
                return before;
            }
            String parentId = Layers.parentOf(before, first).map(Layer::id).orElse(null);
            List<Layer> siblings = childrenOf(before, parentId);
            List<Layer> members = siblings.stream().filter(l -> wanted.contains(l.id())).toList();
            if (members.size() != wanted.size()) {
                return before;
            }
            for (Layer sibling : siblings) {
                Mask mask = sibling.base().mask();
                if (mask != null && wanted.contains(sibling.id()) != wanted.contains(mask.layerId())
                        && siblings.stream().anyMatch(l -> l.id().equals(mask.layerId()))) {
                    return before;
                }
            }
            int top = indexOf(siblings, members.get(members.size() - 1).id());
            GroupLayer group = new GroupLayer(LayerBase.create(groupName, before.duration()).withId(groupId), members);
            Animation after = before;
            for (Layer member : members) {
                after = Layers.remove(after, member.id());
            }
            return Layers.insert(after, parentId, top - (members.size() - 1), group);
        });
    }

    /**
     * Why {@link #ungroupLayers} would leave one of the groups alone, as a sentence for the
     * user; empty when every group can be dissolved. Ids that are not groups are ignored.
     */
    public static Optional<String> ungroupObstacle(Animation animation, Collection<String> groupIds) {
        for (String id : groupIds) {
            if (Layers.find(animation, id).orElse(null) instanceof GroupLayer group) {
                String obstacle = obstacle(group);
                if (obstacle != null) {
                    return Optional.of("Cannot ungroup \u201c" + group.name() + "\u201d: " + obstacle + ".");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Dissolves groups: the children take the place of their group. A static group transform
     * and a static group opacity are folded into the children, and the children are limited to
     * the group's in/out range, so the picture stays the same. Groups with an
     * {@link #ungroupObstacle obstacle} are left alone. Masks that pointed to a dissolved group
     * are cleared.
     */
    public static Command ungroupLayers(Collection<String> groupIds) {
        List<String> copy = List.copyOf(groupIds);
        return command("Ungroup", before -> {
            Animation after = before;
            Set<String> dissolved = new HashSet<>();
            for (String id : copy) {
                if (!(Layers.find(after, id).orElse(null) instanceof GroupLayer group) || obstacle(group) != null) {
                    continue;
                }
                String parentId = Layers.parentOf(after, id).map(Layer::id).orElse(null);
                int index = indexOf(childrenOf(after, parentId), id);
                after = Layers.remove(after, id);
                for (Layer child : group.children()) {
                    after = Layers.insert(after, parentId, index++, baked(group, child));
                }
                dissolved.add(id);
            }
            if (dissolved.isEmpty()) {
                return before;
            }
            return after.withLayers(mapLayers(after.layers(), layer -> {
                Mask mask = layer.base().mask();
                return mask != null && dissolved.contains(mask.layerId())
                        ? layer.withBase(layer.base().withMask(null)) : layer;
            }));
        });
    }

    /**
     * Deep copies with fresh layer and keyframe ids, for pasting. Masks point to the copy of
     * their mask layer when that is among the copies, and are dropped otherwise.
     */
    public static List<Layer> freshCopies(List<Layer> layers) {
        Map<String, String> newIds = new HashMap<>();
        for (Layer layer : layers) {
            for (Layer member : subtree(layer)) {
                newIds.put(member.id(), UUID.randomUUID().toString());
            }
        }
        Set<String> fresh = new HashSet<>(newIds.values());
        List<Layer> copies = new ArrayList<>();
        for (Layer layer : layers) {
            copies.add(copy(layer, newIds));
        }
        return mapLayers(copies, layer -> {
            Mask mask = layer.base().mask();
            return mask != null && !fresh.contains(mask.layerId())
                    ? layer.withBase(layer.base().withMask(null)) : layer;
        });
    }

    /**
     * A copy of the layer with its position, every position keyframe, and its motion path shifted.
     * An audio layer, which is nowhere, is returned as it is.
     */
    public static Layer translated(Layer layer, Vec2 delta) {
        if (layer instanceof AudioLayer) {
            return layer;
        }
        Transform transform = layer.base().transform();
        LayerBase base = layer.base().withTransform(
                transform.withPosition(mapValues(transform.position(), p -> p.plus(delta))));
        MotionPath motion = base.motionPath();
        if (motion != null) {
            List<PathData.Contour> contours = new ArrayList<>();
            for (PathData.Contour contour : motion.path().contours()) {
                contours.add(new PathData.Contour(contour.vertices().stream()
                        .map(v -> new PathData.Vertex(v.point().plus(delta), v.in(), v.out())).toList(), contour.closed()));
            }
            base = base.withMotionPath(motion.withPath(new PathData(contours)));
        }
        return layer.withBase(base);
    }

    private static Command command(String name, UnaryOperator<Animation> fn) {
        Objects.requireNonNull(name, "name");
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Animation apply(Animation before) {
                return fn.apply(before);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private interface KeyframeEdit {
        Animatable<?> apply(Animatable<?> value, Set<String> keyframeIds);
    }

    private static Command keyframeCommand(String name, Collection<KeyframeRef> refs, KeyframeEdit edit) {
        Map<String, Map<String, Set<String>>> byLayer = new LinkedHashMap<>();
        for (KeyframeRef ref : refs) {
            byLayer.computeIfAbsent(ref.layerId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(ref.propertyKey(), k -> new LinkedHashSet<>())
                    .add(ref.keyframeId());
        }
        return command(name, before -> {
            Animation after = before;
            for (Map.Entry<String, Map<String, Set<String>>> entry : byLayer.entrySet()) {
                after = editLayer(after, entry.getKey(), layer -> {
                    Layer result = layer;
                    for (Map.Entry<String, Set<String>> property : entry.getValue().entrySet()) {
                        Animatable<?> value = LayerProperties.get(result, property.getKey());
                        result = LayerProperties.set(result, property.getKey(), edit.apply(value, property.getValue()));
                    }
                    return result;
                });
            }
            return after;
        });
    }

    /**
     * Like {@link Layers#replace}, but a result that equals the layer also counts as "no change".
     * An audio layer is kept within what a file can hold, see {@link #inRange(AudioLayer)}.
     */
    private static Animation editLayer(Animation animation, String id, UnaryOperator<Layer> fn) {
        return Layers.replace(animation, id, layer -> {
            Layer result = fn.apply(layer);
            if (result instanceof AudioLayer audio) {
                result = inRange(audio);
            } else if (result instanceof VideoLayer video) {
                double offset = Double.isFinite(video.clipOffset()) ? Math.max(0, video.clipOffset()) : 0;
                double width = video.width() > 0 && Double.isFinite(video.width()) ? video.width() : 1;
                double height = video.height() > 0 && Double.isFinite(video.height()) ? video.height() : 1;
                result = video.withClipOffset(offset).withWidth(width).withHeight(height)
                        .withVolume(limited(video.volume(), 0, 1, 1));
            }
            return layer.equals(result) ? layer : result;
        });
    }

    // The cast is checked afterwards: LayerProperties.set rejects values of the wrong type.
    @SuppressWarnings("unchecked")
    private static <T> Animatable<T> valueAt(Animatable<T> current, double time, Object value, boolean autoKey) {
        T typed = (T) value;
        if (!autoKey && !current.isAnimated()) {
            return Animatable.of(typed);
        }
        Easing easing = Easing.LINEAR;
        for (Keyframe<T> keyframe : current.keyframes()) {
            if (Math.abs(keyframe.time() - time) < Animatable.TIME_EPSILON) {
                easing = keyframe.easing();
            }
        }
        return current.withKeyframe(time, typed, easing);
    }

    private static <T> Animatable<T> withSampledKeyframe(Animatable<T> current, double time) {
        for (Keyframe<T> keyframe : current.keyframes()) {
            if (Math.abs(keyframe.time() - time) < Animatable.TIME_EPSILON) {
                return current;
            }
        }
        return current.withKeyframe(time, current.at(time), Easing.LINEAR);
    }

    private static <T> Animatable<T> moved(Animatable<T> value, Set<String> ids, double delta) {
        if (!value.isAnimated() || !Double.isFinite(delta)) {
            return value;
        }
        List<Keyframe<T>> moved = new ArrayList<>();
        List<Keyframe<T>> unmoved = new ArrayList<>();
        for (Keyframe<T> keyframe : value.keyframes()) {
            if (ids.contains(keyframe.id())) {
                double time = Math.max(0, keyframe.time() + delta);
                Keyframe<T> shifted = new Keyframe<>(keyframe.id(), time, keyframe.value(), keyframe.easing());
                moved.removeIf(other -> sameTime(other, shifted));
                moved.add(shifted);
            } else {
                unmoved.add(keyframe);
            }
        }
        if (moved.isEmpty()) {
            return value;
        }
        unmoved.removeIf(keyframe -> moved.stream().anyMatch(other -> sameTime(other, keyframe)));
        unmoved.addAll(moved);
        return Animatable.keyframed(unmoved);
    }

    private static boolean sameTime(Keyframe<?> a, Keyframe<?> b) {
        return Math.abs(a.time() - b.time()) < Animatable.TIME_EPSILON;
    }

    private static <T> Animatable<T> withEasing(Animatable<T> value, Set<String> ids, Easing easing) {
        if (!value.isAnimated()) {
            return value;
        }
        List<Keyframe<T>> list = new ArrayList<>();
        for (Keyframe<T> keyframe : value.keyframes()) {
            list.add(ids.contains(keyframe.id())
                    ? new Keyframe<>(keyframe.id(), keyframe.time(), keyframe.value(), easing) : keyframe);
        }
        return Animatable.keyframed(list);
    }

    private static <T> Animatable<T> withFreshKeyframeIds(Animatable<T> value) {
        List<Keyframe<T>> list = new ArrayList<>();
        for (Keyframe<T> keyframe : value.keyframes()) {
            list.add(new Keyframe<>(UUID.randomUUID().toString(), keyframe.time(), keyframe.value(), keyframe.easing()));
        }
        return Animatable.keyframed(list);
    }

    private static Layer copy(Layer original, Map<String, String> newIds) {
        Layer copy = original;
        if (copy instanceof GroupLayer group) {
            List<Layer> children = new ArrayList<>();
            for (Layer child : group.children()) {
                children.add(copy(child, newIds));
            }
            copy = group.withChildren(children);
        }
        for (PropertyRef ref : LayerProperties.list(copy)) {
            if (ref.value().isAnimated()) {
                copy = LayerProperties.set(copy, ref.key(), withFreshKeyframeIds(ref.value()));
            }
        }
        LayerBase base = copy.base().withId(newIds.get(original.id()));
        Mask mask = base.mask();
        if (mask != null && newIds.containsKey(mask.layerId())) {
            base = base.withMask(new Mask(newIds.get(mask.layerId()), mask.mode()));
        }
        return copy.withBase(base);
    }

    private static void collectTopMost(List<Layer> layers, Set<String> wanted, List<Layer> out) {
        for (Layer layer : layers) {
            if (wanted.contains(layer.id())) {
                out.add(layer);
            } else if (layer instanceof GroupLayer group) {
                collectTopMost(group.children(), wanted, out);
            }
        }
    }

    private static List<Layer> subtree(Layer layer) {
        List<Layer> out = new ArrayList<>();
        out.add(layer);
        if (layer instanceof GroupLayer group) {
            for (Layer child : group.children()) {
                out.addAll(subtree(child));
            }
        }
        return out;
    }

    private static int indexOf(List<Layer> layers, String id) {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("Layer is not in its parent: " + id);
    }

    private static Set<String> layerIds(Animation animation) {
        Set<String> ids = new HashSet<>();
        for (Layer layer : Layers.flatten(animation)) {
            ids.add(layer.id());
        }
        return ids;
    }

    /** Applies the function to every layer, children first, and keeps the instances of unchanged subtrees. */
    private static List<Layer> mapLayers(List<Layer> layers, UnaryOperator<Layer> fn) {
        List<Layer> out = new ArrayList<>(layers.size());
        boolean changed = false;
        for (Layer layer : layers) {
            Layer result = layer;
            if (result instanceof GroupLayer group) {
                List<Layer> children = mapLayers(group.children(), fn);
                if (children != group.children()) {
                    result = group.withChildren(children);
                }
            }
            result = fn.apply(result);
            changed |= result != layer;
            out.add(result);
        }
        return changed ? List.copyOf(out) : layers;
    }

    private static List<Layer> childrenOf(Animation animation, String parentIdOrNull) {
        return parentIdOrNull == null ? animation.layers()
                : ((GroupLayer) Layers.find(animation, parentIdOrNull).orElseThrow()).children();
    }

    private static <T> Animatable<T> mapValues(Animatable<T> value, UnaryOperator<T> fn) {
        if (!value.isAnimated()) {
            return Animatable.of(fn.apply(value.at(0)));
        }
        List<Keyframe<T>> list = new ArrayList<>();
        for (Keyframe<T> keyframe : value.keyframes()) {
            list.add(new Keyframe<>(keyframe.id(), keyframe.time(), fn.apply(keyframe.value()), keyframe.easing()));
        }
        return Animatable.keyframed(list);
    }

    private static List<Layer> reorder(List<Layer> layers, Set<String> wanted, Order order) {
        List<Layer> result = new ArrayList<>(layers.size());
        boolean changed = false;
        for (Layer layer : layers) {
            Layer mapped = layer;
            if (layer instanceof GroupLayer group) {
                List<Layer> children = reorder(group.children(), wanted, order);
                if (children != group.children()) {
                    mapped = group.withChildren(children);
                    changed = true;
                }
            }
            result.add(mapped);
        }
        Predicate<Layer> moves = layer -> wanted.contains(layer.id());
        List<Layer> ordered = new ArrayList<>(result);
        switch (order) {
            case FRONT -> {
                ordered.removeIf(moves);
                result.stream().filter(moves).forEach(ordered::add);
            }
            case BACK -> {
                ordered.removeIf(moves);
                ordered.addAll(0, result.stream().filter(moves).toList());
            }
            case FORWARD -> {
                for (int i = ordered.size() - 2; i >= 0; i--) {
                    if (moves.test(ordered.get(i)) && !moves.test(ordered.get(i + 1))) {
                        ordered.set(i, ordered.set(i + 1, ordered.get(i)));
                    }
                }
            }
            case BACKWARD -> {
                for (int i = 1; i < ordered.size(); i++) {
                    if (moves.test(ordered.get(i)) && !moves.test(ordered.get(i - 1))) {
                        ordered.set(i, ordered.set(i - 1, ordered.get(i)));
                    }
                }
            }
        }
        for (int i = 0; i < ordered.size() && !changed; i++) {
            changed = ordered.get(i) != layers.get(i);
        }
        return changed ? List.copyOf(ordered) : layers;
    }

    /** What keeps a group from being dissolved, or {@code null}. */
    private static String obstacle(GroupLayer group) {
        LayerBase base = base(group);
        if (base.mask() != null || !base.effects().isEmpty() || base.motionPath() != null) {
            return "it has a mask, effects or a motion path";
        }
        if (base.opacity().isAnimated()) {
            return "its opacity is animated";
        }
        if (isIdentity(base.transform())) {
            return null;
        }
        if (isAnimated(base.transform())) {
            return "its transform is animated";
        }
        for (Layer child : group.children()) {
            if (isAnimated(child.base().transform()) || child.base().motionPath() != null) {
                return "it is transformed and the layer \u201c" + child.name() + "\u201d is animated";
            }
        }
        return null;
    }

    private static LayerBase base(Layer layer) {
        return layer.base();
    }

    private static boolean isAnimated(Transform t) {
        return t.anchor().isAnimated() || t.position().isAnimated() || t.scale().isAnimated()
                || t.rotation().isAnimated() || t.skew().isAnimated();
    }

    private static boolean isIdentity(Transform t) {
        return !isAnimated(t) && Matrices.isIdentity(t.matrixAt(0));
    }

    /** The child as it has to be outside its group to look the same; see {@link #obstacle}. */
    private static Layer baked(GroupLayer group, Layer child) {
        LayerBase base = child.base();
        LayerBase groupBase = group.base();
        if (!isIdentity(groupBase.transform())) {
            Transform t = base.transform();
            Matrices.Decomposed d = Matrices.decompose(
                    Matrices.multiply(groupBase.transform().matrixAt(0), t.matrixAt(0)), t.anchor().at(0));
            base = base.withTransform(new Transform(t.anchor(), Animatable.of(d.position()), Animatable.of(d.scale()),
                    Animatable.of(d.rotation()), Animatable.of(d.skew())));
        }
        double groupOpacity = groupBase.opacity().at(0);
        if (groupOpacity != 1) {
            base = base.withOpacity(mapValues(base.opacity(), o -> o * groupOpacity));
        }
        double in = Math.max(base.inPoint(), groupBase.inPoint());
        double out = Math.max(in, Math.min(base.outPoint(), groupBase.outPoint()));
        if (in != base.inPoint() || out != base.outPoint()) {
            base = base.withInPoint(in).withOutPoint(out);
        }
        return base == child.base() ? child : child.withBase(base);
    }

    /** "transform.position" gives "Position", "stroke.dashOffset" gives "Stroke Dash Offset". */
    static String label(String key) {
        String rest = key.replaceFirst("^(transform|geometry)\\.", "").replaceFirst("^effects\\[\\d+]\\.", "effect.");
        StringBuilder out = new StringBuilder();
        for (String word : rest.split("\\.|(?<=[a-z])(?=[A-Z])")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }
}
