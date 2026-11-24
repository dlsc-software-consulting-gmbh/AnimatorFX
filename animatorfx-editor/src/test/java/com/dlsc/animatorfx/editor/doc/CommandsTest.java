package com.dlsc.animatorfx.editor.doc;

import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    private static final String OPACITY = "opacity";
    private static final String POSITION = "transform.position";

    static ShapeLayer rect(String id) {
        LayerBase base = LayerBase.create(id, 3).withId(id);
        return new ShapeLayer(base, new Geometry.Rect(Animatable.of(new Vec2(40, 30)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(Rgba.WHITE)), null, null);
    }

    static GroupLayer group(String id, Layer... children) {
        return new GroupLayer(LayerBase.create(id, 3).withId(id), List.of(children));
    }

    static Animation doc(Layer... layers) {
        return Animation.create("Test", 800, 600, 3).withLayers(List.of(layers));
    }

    private static List<String> ids(List<Layer> layers) {
        return layers.stream().map(Layer::id).toList();
    }

    private static Layer layer(Animation animation, String id) {
        return Layers.find(animation, id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Keyframe<Double>> opacityKeys(Animation animation, String id) {
        return (List<Keyframe<Double>>) (List<?>) LayerProperties.get(layer(animation, id), OPACITY).keyframes();
    }

    /** A document with layer "a" whose opacity has keyframes k1 (t=0, 0.0), k2 (t=1, 0.5) and k3 (t=2, 1.0). */
    private static Animation animated() {
        Animatable<Double> opacity = Animatable.keyframed(List.of(
                new Keyframe<>("k1", 0, 0.0, Easing.LINEAR),
                new Keyframe<>("k2", 1, 0.5, new Easing.Hold()),
                new Keyframe<>("k3", 2, 1.0, Easing.LINEAR)));
        ShapeLayer a = rect("a");
        return doc(a.withBase(a.base().withOpacity(opacity)), rect("b"));
    }

    @Test
    void addLayerInsertsAtTheTopLevelAndIntoGroups() {
        Animation before = doc(rect("a"), group("g", rect("c")));

        Animation top = Commands.addLayer(null, 1, rect("x")).apply(before);
        assertEquals(List.of("a", "x", "g"), ids(top.layers()));

        Animation nested = Commands.addLayer("g", 0, rect("y")).apply(before);
        assertEquals(List.of("y", "c"), ids(((GroupLayer) layer(nested, "g")).children()));
    }

    @Test
    void removeLayersRemovesNestedLayersAndIgnoresUnknownIds() {
        Animation before = doc(rect("a"), group("g", rect("c"), rect("d")), rect("e"));

        Animation after = Commands.removeLayers(List.of("a", "d", "nope")).apply(before);

        assertEquals(List.of("g", "e"), ids(after.layers()));
        assertEquals(List.of("c"), ids(((GroupLayer) layer(after, "g")).children()));
    }

    @Test
    void removeLayersClearsMasksThatPointedToARemovedLayer() {
        ShapeLayer masked = rect("masked");
        masked = masked.withBase(masked.base().withMask(new Mask("m", Mask.Mode.CLIP)));
        ShapeLayer keeps = rect("keeps");
        keeps = keeps.withBase(keeps.base().withMask(new Mask("other", Mask.Mode.ALPHA)));
        Animation before = doc(rect("m"), masked, rect("other"), keeps);

        Animation after = Commands.removeLayers(List.of("m")).apply(before);

        assertNull(layer(after, "masked").base().mask());
        assertEquals(new Mask("other", Mask.Mode.ALPHA), layer(after, "keeps").base().mask());
    }

    @Test
    void removeLayersWithOnlyUnknownIdsReturnsTheSameInstance() {
        Animation before = doc(rect("a"));
        assertSame(before, Commands.removeLayers(List.of("nope")).apply(before));
    }

    @Test
    void updateLayerAppliesTheFunctionAndCarriesTheName() {
        Animation before = doc(rect("a"));
        Command command = Commands.updateLayer("Rename Layer", "a", l -> l.withBase(l.base().withName("Hero")));

        assertEquals("Rename Layer", command.name());
        assertEquals("Hero", layer(command.apply(before), "a").name());
    }

    @Test
    void updateLayerThatChangesNothingReturnsTheSameInstance() {
        Animation before = doc(rect("a"));
        Command same = Commands.updateLayer("Rename Layer", "a", l -> l.withBase(l.base().withName("a")));
        assertSame(before, same.apply(before));
    }

    @Test
    void setPropertyReplacesTheWholeAnimatable() {
        Animation before = doc(rect("a"));

        Animation after = Commands.setProperty("a", OPACITY, Animatable.of(0.25)).apply(before);

        assertEquals(Animatable.of(0.25), LayerProperties.get(layer(after, "a"), OPACITY));
        assertSame(before, Commands.setProperty("a", OPACITY, Animatable.of(1.0)).apply(before));
    }

    @Test
    void setPropertyRejectsValuesOfTheWrongType() {
        Animation before = doc(rect("a"));
        Command command = Commands.setProperty("a", OPACITY, Animatable.of(new Vec2(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> command.apply(before));
    }

    @Test
    void setValueAtStaticPropertyWithoutAutoKeyReplacesTheStaticValue() {
        Animation before = doc(rect("a"));

        Animation after = Commands.setValueAt("a", OPACITY, 1.5, 0.4, false).apply(before);

        assertEquals(Animatable.of(0.4), LayerProperties.get(layer(after, "a"), OPACITY));
    }

    @Test
    void setValueAtStaticPropertyWithAutoKeyCreatesASingleLinearKeyframeAtThatTime() {
        Animation before = doc(rect("a"));

        Animation after = Commands.setValueAt("a", POSITION, 1.5, new Vec2(10, 20), true).apply(before);

        List<? extends Keyframe<?>> keys = LayerProperties.get(layer(after, "a"), POSITION).keyframes();
        assertEquals(1, keys.size());
        assertEquals(1.5, keys.get(0).time());
        assertEquals(new Vec2(10, 20), keys.get(0).value());
        assertEquals(Easing.LINEAR, keys.get(0).easing());
    }

    @Test
    void setValueAtAnimatedPropertyWithoutAutoKeyUpdatesTheKeyframeAtThatTimeAndKeepsItsIdAndEasing() {
        Animation after = Commands.setValueAt("a", OPACITY, 1.0000001, 0.9, false).apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "a");
        assertEquals(3, keys.size());
        assertEquals(new Keyframe<>("k2", 1, 0.9, new Easing.Hold()), keys.get(1));
    }

    @Test
    void setValueAtAnimatedPropertyWithoutAutoKeyAddsAKeyframeWhenNoneIsAtThatTime() {
        Animation after = Commands.setValueAt("a", OPACITY, 1.5, 0.9, false).apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "a");
        assertEquals(List.of(0.0, 1.0, 1.5, 2.0), keys.stream().map(Keyframe::time).toList());
        assertEquals(0.9, keys.get(2).value());
        assertEquals(Easing.LINEAR, keys.get(2).easing());
    }

    @Test
    void setValueAtAnimatedPropertyWithAutoKeyAddsOrUpdatesAKeyframe() {
        Animation added = Commands.setValueAt("a", OPACITY, 0.5, 0.2, true).apply(animated());
        assertEquals(4, opacityKeys(added, "a").size());

        Animation updated = Commands.setValueAt("a", OPACITY, 2, 0.7, true).apply(animated());
        assertEquals(new Keyframe<>("k3", 2, 0.7, Easing.LINEAR), opacityKeys(updated, "a").get(2));
    }

    @Test
    void setValueAtWithTheCurrentValueReturnsTheSameInstance() {
        Animation staticDoc = doc(rect("a"));
        assertSame(staticDoc, Commands.setValueAt("a", OPACITY, 1, 1.0, false).apply(staticDoc));

        Animation animated = animated();
        assertSame(animated, Commands.setValueAt("a", OPACITY, 1, 0.5, true).apply(animated));
    }

    @Test
    void addKeyframeUsesTheSampledValue() {
        Animation after = Commands.addKeyframe("a", OPACITY, 0.5).apply(animated());

        Keyframe<Double> added = opacityKeys(after, "a").get(1);
        assertEquals(0.5, added.time());
        assertEquals(0.25, added.value(), 1e-9);
    }

    @Test
    void addKeyframeOnAStaticPropertyMakesItKeyframed() {
        Animation after = Commands.addKeyframe("b", OPACITY, 1).apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "b");
        assertEquals(1, keys.size());
        assertEquals(1.0, keys.get(0).value());
    }

    @Test
    void addKeyframeWhereOneExistsReturnsTheSameInstance() {
        Animation before = animated();
        assertSame(before, Commands.addKeyframe("a", OPACITY, 1).apply(before));
    }

    @Test
    void removeKeyframesRemovesByIdAndTurnsTheLastOneIntoAStaticValue() {
        Animation two = Commands.removeKeyframes(List.of(new KeyframeRef("a", OPACITY, "k2"))).apply(animated());
        assertEquals(List.of("k1", "k3"), opacityKeys(two, "a").stream().map(Keyframe::id).toList());

        Animation none = Commands.removeKeyframes(List.of(new KeyframeRef("a", OPACITY, "k1"),
                new KeyframeRef("a", OPACITY, "k2"), new KeyframeRef("a", OPACITY, "k3"))).apply(animated());
        assertFalse(LayerProperties.get(layer(none, "a"), OPACITY).isAnimated());
    }

    @Test
    void moveKeyframesShiftsTheSelectedKeyframesAndKeepsTheirIds() {
        Animation after = Commands.moveKeyframes(List.of(new KeyframeRef("a", OPACITY, "k2")), 0.5)
                .apply(animated());

        assertEquals(new Keyframe<>("k2", 1.5, 0.5, new Easing.Hold()), opacityKeys(after, "a").get(1));
    }

    @Test
    void moveKeyframesClampsTimesAtZero() {
        Animation after = Commands.moveKeyframes(List.of(new KeyframeRef("a", OPACITY, "k3")), -5)
                .apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "a");
        assertTrue(keys.stream().allMatch(k -> k.time() >= 0));
        assertEquals(0.0, keys.stream().filter(k -> k.id().equals("k3")).findFirst().orElseThrow().time());
    }

    @Test
    void aMovedKeyframeReplacesTheUnmovedKeyframeItLandsOn() {
        Animation after = Commands.moveKeyframes(List.of(new KeyframeRef("a", OPACITY, "k2")), 1)
                .apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "a");
        assertEquals(List.of("k1", "k2"), keys.stream().map(Keyframe::id).toList());
        assertEquals(2.0, keys.get(1).time());
        assertEquals(0.5, keys.get(1).value());
    }

    @Test
    void moveKeyframesByZeroReturnsTheSameInstance() {
        Animation before = animated();
        assertSame(before, Commands.moveKeyframes(List.of(new KeyframeRef("a", OPACITY, "k2")), 0).apply(before));
    }

    @Test
    void setEasingChangesOnlyTheSelectedKeyframes() {
        Easing ease = new Easing.CubicBezier(0.22, 1, 0.36, 1);

        Animation after = Commands.setEasing(List.of(new KeyframeRef("a", OPACITY, "k1")), ease).apply(animated());

        List<Keyframe<Double>> keys = opacityKeys(after, "a");
        assertEquals(new Keyframe<>("k1", 0, 0.0, ease), keys.get(0));
        assertEquals(new Easing.Hold(), keys.get(1).easing());
    }

    @Test
    void moveLayerReordersAndReparents() {
        Animation before = doc(rect("a"), group("g", rect("c")), rect("e"));

        assertEquals(List.of("g", "e", "a"), ids(Commands.moveLayer("a", null, 2).apply(before).layers()));

        Animation nested = Commands.moveLayer("e", "g", 1).apply(before);
        assertEquals(List.of("a", "g"), ids(nested.layers()));
        assertEquals(List.of("c", "e"), ids(((GroupLayer) layer(nested, "g")).children()));
    }

    @Test
    void moveLayerToItsCurrentPlaceReturnsTheSameInstance() {
        Animation before = doc(rect("a"), rect("b"));
        assertSame(before, Commands.moveLayer("a", null, 0).apply(before));
    }

    @Test
    void setDocumentAppliesTheFunctionAndCarriesTheName() {
        Animation before = doc();
        Command command = Commands.setDocument("Change Duration", a -> a.withDuration(5));

        assertEquals("Change Duration", command.name());
        assertEquals(5, command.apply(before).duration());
        assertSame(before, Commands.setDocument("Change Duration", a -> a.withDuration(3)).apply(before));
    }

    @Test
    void duplicateLayersInsertsACopyWithFreshIdsDirectlyAboveEachOriginal() {
        Animation before = animated();

        Animation after = Commands.duplicateLayers(List.of("a")).apply(before);

        assertEquals(3, after.layers().size());
        assertEquals("a", after.layers().get(0).id());
        assertEquals("b", after.layers().get(2).id());
        Layer copy = after.layers().get(1);
        assertNotEquals("a", copy.id());
        assertEquals("a copy", copy.name());

        List<Keyframe<Double>> original = opacityKeys(after, "a");
        List<Keyframe<Double>> copied = opacityKeys(after, copy.id());
        assertEquals(original.stream().map(Keyframe::value).toList(), copied.stream().map(Keyframe::value).toList());
        assertEquals(original.stream().map(Keyframe::easing).toList(), copied.stream().map(Keyframe::easing).toList());
        Set<String> keyIds = new HashSet<>();
        original.forEach(k -> keyIds.add(k.id()));
        copied.forEach(k -> keyIds.add(k.id()));
        assertEquals(6, keyIds.size());
    }

    @Test
    void duplicateLayersDeepCopiesGroupsAndRemapsMasksInsideTheCopy() {
        ShapeLayer masked = rect("masked");
        masked = masked.withBase(masked.base().withMask(new Mask("m", Mask.Mode.CLIP)));
        Animation before = doc(group("g", rect("m"), masked));

        Animation after = Commands.duplicateLayers(List.of("g", "m")).apply(before);

        assertEquals(2, after.layers().size());
        assertEquals(6, new HashSet<>(ids(Layers.flatten(after))).size());
        GroupLayer copy = (GroupLayer) after.layers().get(1);
        assertEquals(2, copy.children().size());
        assertEquals(copy.children().get(0).id(), copy.children().get(1).base().mask().layerId());
    }

    @Test
    void duplicateOffsetsAnimatedPositionsAndMotionPathsWithoutMovingTheOriginal() {
        Animatable<Vec2> position = Animatable.keyframed(List.of(
                new Keyframe<>("p1", 0, new Vec2(5, 6), Easing.LINEAR),
                new Keyframe<>("p2", 1, new Vec2(15, 16), Easing.LINEAR)));
        PathData path = new PathData(List.of(new PathData.Contour(List.of(
                new PathData.Vertex(new Vec2(20, 30), Vec2.ZERO, Vec2.ZERO),
                new PathData.Vertex(new Vec2(40, 50), Vec2.ZERO, Vec2.ZERO)), false)));
        ShapeLayer original = rect("a");
        original = original.withBase(original.base()
                .withTransform(Transform.IDENTITY.withPosition(position))
                .withMotionPath(new MotionPath(path, Animatable.of(0.0), false)));

        Animation after = Commands.duplicateLayers(List.of("a")).apply(doc(original));

        Layer copy = after.layers().get(1);
        assertEquals(new Vec2(15, 16), copy.base().transform().position().at(0));
        assertEquals(new Vec2(25, 26), copy.base().transform().position().at(1));
        assertEquals(new Vec2(30, 40), copy.base().motionPath().path().contours().get(0).vertices().get(0).point());
        assertEquals(new Vec2(5, 6), after.layers().get(0).base().transform().position().at(0));
        assertEquals(path, after.layers().get(0).base().motionPath().path());
    }

    @Test
    void duplicateOffsetsAGroupOnlyOnceIncludingWhenAChildIsSelected() {
        Animation after = Commands.duplicateLayers(List.of("g", "a")).apply(doc(group("g", rect("a"))));
        GroupLayer copy = (GroupLayer) after.layers().get(1);

        assertEquals(new Vec2(10, 10), copy.base().transform().position().at(0));
        assertEquals(Vec2.ZERO, copy.children().get(0).base().transform().position().at(0));
    }
}
