package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometries;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.PathMeasure;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.player.LayerState.EffectState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Samples a document at a point in time. Sampling is a pure function of the document and the
 * time and may be called from any thread.
 * <p>
 * Everything that does not depend on time (static values, the outline of an unanimated shape,
 * the arc-length table of a motion path) is worked out once per document instance and reused, so
 * sampling the same document frame after frame only pays for what is animated.
 */
public final class Evaluator {

    private static final int CACHE_SIZE = 8;

    /** Most recently used first. */
    private static final List<Plan> PLANS = new ArrayList<>();

    private Evaluator() {
    }

    /**
     * The state of every layer of the document at the given time in seconds.
     *
     * @throws IllegalArgumentException if two layers share an id
     */
    public static Frame sample(Animation animation, double time) {
        return plan(animation).sample(time);
    }

    private static Plan plan(Animation animation) {
        synchronized (PLANS) {
            for (int i = 0; i < PLANS.size(); i++) {
                Plan plan = PLANS.get(i);
                if (plan.animation.get() == animation) {
                    if (i > 0) {
                        PLANS.remove(i);
                        PLANS.add(0, plan);
                    }
                    return plan;
                }
            }
        }
        Plan plan = new Plan(animation);
        synchronized (PLANS) {
            PLANS.removeIf(p -> p.animation.get() == null);
            PLANS.add(0, plan);
            while (PLANS.size() > CACHE_SIZE) {
                PLANS.remove(PLANS.size() - 1);
            }
        }
        return plan;
    }

    /** The time-independent part of a document. Immutable, so it can be shared between threads. */
    private static final class Plan {

        final WeakReference<Animation> animation;
        private final double duration;
        private final LayerPlan[] layers;

        Plan(Animation animation) {
            this.animation = new WeakReference<>(animation);
            duration = animation.duration();
            List<LayerPlan> plans = new ArrayList<>();
            collect(animation.layers(), plans, new HashSet<>());
            layers = plans.toArray(LayerPlan[]::new);
        }

        private static void collect(List<Layer> layers, List<LayerPlan> out, Set<String> ids) {
            for (Layer layer : layers) {
                if (!ids.add(layer.id())) {
                    throw new IllegalArgumentException("Duplicate layer id: " + layer.id());
                }
                out.add(new LayerPlan(layer));
                if (layer instanceof GroupLayer group) {
                    collect(group.children(), out, ids);
                }
            }
        }

        Frame sample(double time) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, LayerState>[] entries = new Map.Entry[layers.length];
            for (int i = 0; i < layers.length; i++) {
                entries[i] = Map.entry(layers[i].id, layers[i].at(time, duration));
            }
            return new Frame(time, Map.ofEntries(entries));
        }
    }

    private record Placement(double[] matrix, Vec2 position, Vec2 scale, double rotation) {
    }

    private static final class LayerPlan {

        final String id;
        private final LayerBase base;
        private final PathMeasure motion;
        private final Geometry geometry;
        private final Animatable<Paint> fill;
        private final Stroke stroke;
        private final Trim trim;
        private final Animatable<Double> fontSize;
        private final Animatable<Double> volume;
        private final Animatable<Double> balance;

        // Each of these is null when the value is animated and has to be sampled.
        private final Placement placement;
        private final PathData path;
        private final PathMeasure measure;
        private final PathData strokePath;
        private final List<EffectState> effects;
        private final LayerState shown;
        private final LayerState hidden;

        LayerPlan(Layer layer) {
            id = layer.id();
            // an audio layer renders nothing: the visual members of its base have no effect
            base = layer instanceof AudioLayer ? layer.base().withTransform(Transform.IDENTITY)
                    .withOpacity(Animatable.of(1.0)).withEffects(List.of()).withMotionPath(null) : layer.base();
            MotionPath motionPath = base.motionPath();
            motion = motionPath == null ? null : new PathMeasure(motionPath.path());

            Geometry geometry = null;
            Animatable<Paint> fill = null;
            Stroke stroke = null;
            Trim trim = null;
            Animatable<Double> fontSize = null;
            if (layer instanceof ShapeLayer shape) {
                geometry = shape.geometry();
                fill = shape.fill();
                stroke = shape.stroke();
                // only the stroke is trimmed
                trim = stroke == null ? null : shape.trim();
            } else if (layer instanceof TextLayer text) {
                fill = text.fill();
                stroke = text.stroke();
                fontSize = text.fontSize();
            }
            this.geometry = geometry;
            this.fill = fill;
            this.stroke = stroke;
            this.trim = trim;
            this.fontSize = fontSize;
            volume = layer instanceof AudioLayer audio ? audio.volume()
                    : layer instanceof VideoLayer video ? video.volume() : null;
            balance = layer instanceof AudioLayer audio ? audio.balance() : null;

            Transform transform = base.transform();
            boolean placementAnimated = animated(transform.anchor(), transform.position(), transform.scale(),
                    transform.rotation(), transform.skew()) || (motionPath != null && motionPath.progress().isAnimated());
            placement = placementAnimated ? null : place(0);

            boolean geometryAnimated = geometry != null && animated(geometry);
            boolean trimAnimated = trim != null && animated(trim.start(), trim.end(), trim.offset());
            path = geometry == null || geometryAnimated ? null : Geometries.toPath(geometry, 0);
            measure = path == null || trim == null ? null : new PathMeasure(path);
            strokePath = path == null || trimAnimated ? null : trim == null ? path : trim(measure, 0);

            boolean effectsAnimated = false;
            for (Effect effect : base.effects()) {
                effectsAnimated |= animated(effect);
            }
            effects = effectsAnimated ? null : effects(0);

            boolean anythingAnimated = placementAnimated || geometryAnimated || trimAnimated || effectsAnimated
                    || base.opacity().isAnimated() || (fill != null && fill.isAnimated())
                    || (stroke != null && animated(stroke.paint(), stroke.width(), stroke.dashOffset()))
                    || (fontSize != null && fontSize.isAnimated())
                    || (volume != null && volume.isAnimated()) || (balance != null && balance.isAnimated());
            shown = anythingAnimated ? null : compute(0, true);
            hidden = anythingAnimated ? null : shown.withVisible(false);
        }

        LayerState at(double time, double duration) {
            boolean visible = base.visible() && time >= base.inPoint()
                    && (time < base.outPoint() || (time >= duration && base.outPoint() >= duration));
            if (shown != null) {
                return visible ? shown : hidden;
            }
            return compute(time, visible);
        }

        private LayerState compute(double time, boolean visible) {
            Placement placed = placement != null ? placement : place(time);
            PathData fillPath = null;
            PathData tracePath = null;
            if (geometry != null) {
                fillPath = path != null ? path : Geometries.toPath(geometry, time);
                if (strokePath != null) {
                    tracePath = strokePath;
                } else if (trim == null) {
                    tracePath = fillPath;
                } else {
                    tracePath = trim(measure != null ? measure : new PathMeasure(fillPath), time);
                }
            }
            return new LayerState(visible, placed.matrix(), base.opacity().at(time), fillPath, tracePath,
                    fill == null ? null : fill.at(time),
                    stroke == null ? null : stroke.paint().at(time),
                    stroke == null ? 0 : stroke.width().at(time),
                    stroke == null ? 0 : stroke.dashOffset().at(time),
                    fontSize == null ? 0 : fontSize.at(time),
                    effects != null ? effects : effects(time),
                    placed.position(), placed.scale(), placed.rotation(),
                    volume == null ? 1 : volume.at(time), balance == null ? 0 : balance.at(time));
        }

        private Placement place(double time) {
            Transform transform = base.transform();
            Vec2 position;
            double rotation = transform.rotation().at(time);
            if (motion != null) {
                double progress = base.motionPath().progress().at(time);
                position = motion.pointAt(progress);
                if (base.motionPath().orientToPath()) {
                    rotation += motion.angleAt(progress);
                }
            } else {
                position = transform.position().at(time);
            }
            Vec2 scale = transform.scale().at(time);
            // the model owns the matrix maths: feed it the resolved values
            double[] matrix = new Transform(Animatable.of(transform.anchor().at(time)), Animatable.of(position),
                    Animatable.of(scale), Animatable.of(rotation), Animatable.of(transform.skew().at(time)))
                    .matrixAt(time);
            return new Placement(matrix, position, scale, rotation);
        }

        private PathData trim(PathMeasure measure, double time) {
            return measure.trim(trim.start().at(time), trim.end().at(time), trim.offset().at(time));
        }

        private List<EffectState> effects(double time) {
            List<Effect> sources = base.effects();
            if (sources.isEmpty()) {
                return List.of();
            }
            EffectState[] states = new EffectState[sources.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = effect(sources.get(i), time);
            }
            return List.of(states);
        }

        private static EffectState effect(Effect effect, double time) {
            return switch (effect) {
                case Effect.Blur blur -> new EffectState(blur, new double[]{blur.radius().at(time)}, null);
                case Effect.DropShadow shadow -> new EffectState(shadow, new double[]{shadow.radius().at(time),
                        shadow.offsetX().at(time), shadow.offsetY().at(time), shadow.spread().at(time)},
                        shadow.color().at(time));
                case Effect.Glow glow -> new EffectState(glow, new double[]{glow.level().at(time)}, null);
            };
        }

        private static boolean animated(Animatable<?>... values) {
            for (Animatable<?> value : values) {
                if (value.isAnimated()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean animated(Geometry geometry) {
            return switch (geometry) {
                case Geometry.Rect rect -> animated(rect.size(), rect.cornerRadius());
                case Geometry.Ellipse ellipse -> animated(ellipse.size());
                case Geometry.Line line -> animated(line.start(), line.end());
                case Geometry.Polygon polygon -> animated(polygon.radius());
                case Geometry.Star star -> animated(star.outerRadius(), star.innerRadius());
                case Geometry.PathGeometry path -> animated(path.path());
            };
        }

        private static boolean animated(Effect effect) {
            return switch (effect) {
                case Effect.Blur blur -> animated(blur.radius());
                case Effect.DropShadow shadow -> animated(shadow.color(), shadow.radius(), shadow.offsetX(),
                        shadow.offsetY(), shadow.spread());
                case Effect.Glow glow -> animated(glow.level());
            };
        }
    }
}
