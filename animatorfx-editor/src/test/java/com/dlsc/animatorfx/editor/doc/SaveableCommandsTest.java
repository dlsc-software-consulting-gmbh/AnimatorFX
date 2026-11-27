package com.dlsc.animatorfx.editor.doc;

import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.inspector.EasingCurve;
import com.dlsc.animatorfx.editor.timeline.Markers;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationJson;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static com.dlsc.animatorfx.editor.doc.CommandsTest.doc;
import static com.dlsc.animatorfx.editor.doc.CommandsTest.group;
import static com.dlsc.animatorfx.editor.doc.CommandsTest.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the commands produce can be saved and opened again: the reader is stricter than the model. */
public class SaveableCommandsTest {

    static SlotLayer slot(String id, String slotName) {
        return new SlotLayer(LayerBase.create(id, 3).withId(id), slotName, 100, 50, Transform.IDENTITY);
    }

    private static List<String> slotNames(Animation animation) {
        return Layers.slots(animation).stream().map(SlotLayer::slotName).toList();
    }

    @Test
    void aDuplicatedSlotGetsItsOwnName() {
        Animation after = Commands.duplicateLayers(List.of("s")).apply(doc(slot("s", "card")));

        assertEquals(List.of("card", "slot1"), slotNames(after));
        RoundTrip.of(after);
    }

    @Test
    void duplicatedAndPastedGroupsDoNotCopyApplicationExportNames() {
        Layer child = rect("r").withBase(rect("r").base().withExportName("accent"));
        GroupLayer group = group("g", child);
        group = group.withBase(group.base().withExportName("panel"));
        Animation before = doc(group);

        Animation duplicated = Commands.duplicateLayers(List.of("g")).apply(before);
        assertEquals(List.of("panel", "accent", "", ""),
                Layers.flatten(duplicated).stream().map(layer -> layer.base().exportName()).toList());
        Animation pasted = Commands.addLayers("Paste", null, 1, Commands.freshCopies(before.layers())).apply(before);
        assertEquals(List.of("panel", "accent", "", ""),
                Layers.flatten(pasted).stream().map(layer -> layer.base().exportName()).toList());
        RoundTrip.of(duplicated);
        RoundTrip.of(pasted);
    }

    @Test
    void theSlotsOfADuplicatedGroupGetTheirOwnNames() {
        Animation before = doc(group("g", slot("s", "card"), group("inner", slot("t", "slot1")), rect("r")));

        Animation after = Commands.duplicateLayers(List.of("g")).apply(before);

        assertEquals(List.of("card", "slot1", "slot2", "slot3"), slotNames(after));
        RoundTrip.of(after);
    }

    @Test
    void twoSlotsDuplicatedAtOnceDoNotShareAName() {
        Animation after = Commands.duplicateLayers(List.of("s", "t")).apply(doc(slot("s", "card"), slot("t", "badge")));

        List<String> names = slotNames(after);
        assertEquals(4, names.size());
        assertEquals(4, new HashSet<>(names).size(), names.toString());
        RoundTrip.of(after);
    }

    // ------------------------------------------------------------------ audit: ids, masks, keyframes, markers, easings

    private static final String OPACITY = "opacity";

    private static ShapeLayer animated(String id) {
        ShapeLayer layer = rect(id);
        return (ShapeLayer) layer.withBase(layer.base().withOpacity(Animatable.keyframed(List.of(
                new Keyframe<>("k1", 0, 0.0, Easing.LINEAR),
                new Keyframe<>("k2", 1, 0.5, Easing.LINEAR),
                new Keyframe<>("k3", 2, 1.0, Easing.LINEAR)))));
    }

    private static Layer masked(Layer layer, String maskLayerId) {
        return layer.withBase(layer.base().withMask(new Mask(maskLayerId, Mask.Mode.CLIP)));
    }

    private static Mask maskOf(Animation animation, String id) {
        return Layers.find(animation, id).orElseThrow().base().mask();
    }

    @Test
    void duplicatedAndPastedLayersHaveTheirOwnLayerAndKeyframeIds() {
        Animation before = doc(group("g", animated("a"), rect("b")));

        Animation duplicated = Commands.duplicateLayers(List.of("g", "a")).apply(before);
        assertEquals(6, Layers.flatten(RoundTrip.of(duplicated)).size());

        List<Layer> copies = Commands.freshCopies(before.layers());
        Animation pasted = Commands.addLayers("Paste", null, 1, copies).apply(before);
        pasted = Commands.addLayers("Paste", "g", 0, Commands.freshCopies(before.layers())).apply(pasted);
        assertEquals(9, Layers.flatten(RoundTrip.of(pasted)).size());
    }

    @Test
    void groupingAndUngroupingKeepIdsAndMasksValid() {
        Animation before = doc(masked(rect("a"), "m"), rect("m"), rect("c"));

        // a group that would separate a layer from its mask is refused
        assertSame(before, Commands.groupLayers(List.of("a", "c"), "g", "Group").apply(before));
        Animation grouped = Commands.groupLayers(List.of("a", "m"), "g", "Group").apply(before);
        assertEquals("m", maskOf(RoundTrip.of(grouped), "a").layerId());
        Animation ungrouped = Commands.ungroupLayers(List.of("g")).apply(grouped);
        assertEquals("m", maskOf(RoundTrip.of(ungrouped), "a").layerId());

        // a layer masked by a group loses the mask when the group is dissolved
        Animation byGroup = doc(masked(rect("a"), "g"), group("g", rect("x")));
        assertNull(maskOf(RoundTrip.of(Commands.ungroupLayers(List.of("g")).apply(byGroup)), "a"));
    }

    @Test
    void movingOrDeletingOneLayerOfAMaskPairClearsTheMask() {
        Animation before = doc(masked(rect("a"), "m"), rect("m"), group("g", rect("x")));

        assertNull(maskOf(RoundTrip.of(Commands.moveLayer("a", "g", 0).apply(before)), "a"));
        assertNull(maskOf(RoundTrip.of(Commands.moveLayer("m", "g", 0).apply(before)), "a"));
        assertNull(maskOf(RoundTrip.of(Commands.removeLayers(List.of("m")).apply(before)), "a"));
        Animation both = Commands.moveLayers(List.of(new Commands.LayerMove("a", "g", 0),
                new Commands.LayerMove("m", "g", 0))).apply(before);
        assertEquals("m", maskOf(RoundTrip.of(both), "a").layerId());
    }

    @Test
    void aDuplicatedMaskedLayerKeepsItsSiblingMaskAndAPastedOneLosesIt() {
        Animation before = doc(group("g", masked(rect("a"), "m"), rect("m")), rect("top"));

        Animation duplicated = RoundTrip.of(Commands.duplicateLayers(List.of("a")).apply(before));
        Layer copy = ((GroupLayer) duplicated.layers().get(0)).children().get(1);
        assertEquals("m", copy.base().mask().layerId(), "the original mask layer is still a sibling");

        Animation maskOnly = RoundTrip.of(Commands.duplicateLayers(List.of("m")).apply(before));
        assertEquals("m", maskOf(maskOnly, "a").layerId());

        // pasted somewhere else, the mask layer is no sibling any more
        List<Layer> pasted = Commands.freshCopies(List.of(Layers.find(before, "a").orElseThrow()));
        assertNull(pasted.get(0).base().mask());
        RoundTrip.of(Commands.addLayers("Paste", null, 2, pasted).apply(before));

        // pasted together, the copy is masked by the copy
        List<Layer> pair = Commands.freshCopies(((GroupLayer) before.layers().get(0)).children());
        assertEquals(pair.get(1).id(), pair.get(0).base().mask().layerId());
        RoundTrip.of(Commands.addLayers("Paste", null, 2, pair).apply(before));
    }

    @Test
    void aMaskIsOnlySetBetweenSiblings() {
        Animation before = doc(rect("a"), group("g", rect("x")));

        assertSame(before, Commands.setMask("a", new Mask("x", Mask.Mode.ALPHA)).apply(before));
        assertSame(before, Commands.setMask("a", new Mask("a", Mask.Mode.ALPHA)).apply(before));
        RoundTrip.of(Commands.setMask("a", new Mask("g", Mask.Mode.ALPHA)).apply(before));
    }

    @Test
    void keyframesMovedOrPastedOntoEachOtherStayUniqueAndNeverGetANegativeTime() {
        Animation before = doc(animated("a"));
        List<KeyframeRef> all = List.of(new KeyframeRef("a", OPACITY, "k1"), new KeyframeRef("a", OPACITY, "k2"),
                new KeyframeRef("a", OPACITY, "k3"));

        Animation onto = RoundTrip.of(Commands.moveKeyframes(all.subList(0, 1), 1).apply(before));
        assertEquals(2, keys(onto).size());
        Animation squashed = RoundTrip.of(Commands.moveKeyframes(all, -10).apply(before));
        assertEquals(List.of(0.0), keys(squashed).stream().map(Keyframe::time).toList());

        List<Commands.KeyframeCopy> copies = Commands.copyKeyframes(before, all);
        Animation pasted = RoundTrip.of(Commands.pasteKeyframes(copies, 1).apply(before));
        assertEquals(List.of(0.0, 1.0, 2.0, 3.0), keys(pasted).stream().map(Keyframe::time).toList());
        RoundTrip.of(Commands.pasteKeyframes(copies, -5).apply(before));
        RoundTrip.of(Commands.pasteKeyframes(copies, 1e9).apply(before));
    }

    @Test
    void aTimeThatIsNegativeOrNotFiniteNeverReachesAKeyframe() {
        Animation before = doc(animated("a"), rect("b"));

        Animation keyed = RoundTrip.of(Commands.setValueAt("b", OPACITY, -2, 0.5, true).apply(before));
        assertEquals(0.0, Layers.find(keyed, "b").orElseThrow().base().opacity().keyframes().get(0).time());
        Animation added = RoundTrip.of(Commands.addKeyframe("b", OPACITY, -1).apply(before));
        assertEquals(0.0, Layers.find(added, "b").orElseThrow().base().opacity().keyframes().get(0).time());
        RoundTrip.of(Commands.setValueAt("a", OPACITY, Double.NaN, 0.5, true).apply(before));
        RoundTrip.of(Commands.addKeyframe("a", OPACITY, Double.POSITIVE_INFINITY).apply(before));

        List<KeyframeRef> refs = List.of(new KeyframeRef("a", OPACITY, "k2"));
        assertSame(before, Commands.moveKeyframes(refs, Double.NaN).apply(before));
        assertSame(before, Commands.moveKeyframes(refs, Double.POSITIVE_INFINITY).apply(before));
        RoundTrip.of(Commands.pasteKeyframes(Commands.copyKeyframes(before, refs), Double.NaN).apply(before));
    }

    private static List<Keyframe<Double>> keys(Animation animation) {
        return Layers.find(animation, "a").orElseThrow().base().opacity().keyframes();
    }

    @Test
    void markersKeepUniqueNames() {
        Animation two = Markers.add(1).apply(Markers.add(0).apply(doc()));
        assertEquals(List.of("marker1", "marker2"), two.markers().stream().map(Marker::name).toList());

        assertSame(two, Markers.rename("marker2", "marker1").apply(two));
        assertSame(two, Markers.rename("marker2", " ").apply(two));
        RoundTrip.of(Markers.rename("marker2", "intro").apply(two));
        // the name of a removed marker is free again
        RoundTrip.of(Markers.add(2).apply(Markers.remove("marker1").apply(two)));
    }

    @Test
    void easingsFromTheEditorKeepTheirXWithinTheUnitRange() {
        Easing.CubicBezier base = new Easing.CubicBezier(0.25, 0.1, 0.25, 1);
        List<Easing.CubicBezier> made = List.of(
                EasingCurve.withHandle(base, 0, new Vec2(-3, 5)),
                EasingCurve.withHandle(base, 1, new Vec2(7, -5)),
                EasingCurve.withHandle(base, 0, new Vec2(Double.NaN, Double.NaN)),
                EasingCurve.parse("cubic-bezier(-1, 9, 2, -9)").orElseThrow(),
                EasingCurve.parse("1e308 1e308 -1e308 0").orElseThrow());
        assertTrue(EasingCurve.parse("NaN 0 1 1").isEmpty());
        assertTrue(EasingCurve.parse("0 0 Infinity 1").isEmpty());

        Animation animation = doc(animated("a"));
        for (Easing.CubicBezier easing : made) {
            assertTrue(easing.x1() >= 0 && easing.x1() <= 1 && easing.x2() >= 0 && easing.x2() <= 1, easing.toString());
            RoundTrip.of(Commands.setEasing(List.of(new KeyframeRef("a", OPACITY, "k1")), easing).apply(animation));
        }
    }

    /** A chain of groups around a rectangle that is nested {@code depth} levels deep. */
    public static Layer nested(String prefix, int depth) {
        Layer layer = rect(prefix + "-leaf");
        for (int i = depth - 1; i >= 1; i--) {
            layer = group(prefix + i, layer);
        }
        return layer;
    }

    @Test
    void nestingIsLimitedToWhatAFileCanHold() {
        Animation full = doc(nested("g", AnimationJson.MAX_LAYER_DEPTH));
        assertTrue(Commands.nestingObstacle(full).isEmpty());
        RoundTrip.of(full);

        Animation tooDeep = Commands.groupLayers(List.of("g1"), "outer", "Outer").apply(full);
        assertEquals("Layers cannot be nested deeper than 100 levels.", Commands.nestingObstacle(tooDeep).orElseThrow());
    }
}
