package com.dlsc.animatorfx.player.render;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.Evaluator;
import com.dlsc.animatorfx.player.FxThreadExtension;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.dlsc.animatorfx.player.TestDocs.at;
import static com.dlsc.animatorfx.player.TestDocs.base;
import static com.dlsc.animatorfx.player.TestDocs.doc;
import static com.dlsc.animatorfx.player.TestDocs.group;
import static com.dlsc.animatorfx.player.TestDocs.keys;
import static com.dlsc.animatorfx.player.TestDocs.rect;
import static com.dlsc.animatorfx.player.TestDocs.stroke;
import static com.dlsc.animatorfx.player.TestDocs.transformed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(FxThreadExtension.class)
class RendererTest {

    @Test
    void videoUsedAsAClipMaskHasRectangularGeometryWithoutAMediaPlayer() {
        VideoLayer video = new VideoLayer(base("video"), "clip", 320, 180, 0,
                Animatable.of(1.0), false, false);
        ShapeLayer shape = rect("shape", 100, 100).withBase(base("shape").withMask(new Mask("video", Mask.Mode.CLIP)));
        show(doc(video, shape), 0.5);
        Node mask = renderer.getLayerNode("shape").getClip();
        assertEquals(320, mask.getBoundsInLocal().getWidth(), 0.01);
        assertEquals(180, mask.getBoundsInLocal().getHeight(), 0.01);
    }

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final Asset IMAGE_ASSET = new Asset("img", Asset.Kind.IMAGE, "assets/img.png", "img.png");
    private static final Asset FONT_ASSET = new Asset("font", Asset.Kind.FONT, "assets/font.ttf", "font.ttf");

    private final Renderer renderer = new Renderer();

    private void show(Animation animation, double time) {
        show(new AnimationFile(animation, Map.of("img", PNG)), time);
    }

    private void show(AnimationFile file, double time) {
        renderer.setDocument(file);
        renderer.apply(Evaluator.sample(file.animation(), time));
    }

    private static TextLayer text(String id, String content) {
        return new TextLayer(base(id), content, "System", null, Animatable.of(20.0), TextLayer.Weight.NORMAL, false,
                TextLayer.Align.LEFT, Animatable.of(new Paint.Solid(Rgba.BLACK)), null);
    }

    private static ImageLayer image(String id) {
        return new ImageLayer(base(id), "img", 40, 30);
    }

    private static SlotLayer slot(String id, String name) {
        return new SlotLayer(base(id), name, 80, 40, Transform.IDENTITY);
    }

    @SuppressWarnings("unchecked")
    private static <L extends Layer> L masked(L layer, String maskId, Mask.Mode mode) {
        return (L) layer.withBase(layer.base().withMask(new Mask(maskId, mode)));
    }

    @SuppressWarnings("unchecked")
    private static <L extends Layer> L withEffects(L layer, Effect... effects) {
        return (L) layer.withBase(layer.base().withEffects(List.of(effects)));
    }

    private Path fillPath(String id) {
        return (Path) ((Group) renderer.getLayerNode(id)).getChildren().get(0);
    }

    private Path strokePath(String id) {
        return (Path) ((Group) renderer.getLayerNode(id)).getChildren().get(1);
    }

    @Test
    void rootIsEmptyWithoutADocument() {
        assertTrue(renderer.getRoot().getChildren().isEmpty());
        assertNull(renderer.getLayerNode("r"));
        assertNull(renderer.getSlotContainer("card"));
    }

    @Test
    void rootStartsWithTheBackgroundAndIsClippedToTheDocument() {
        show(doc(rect("r", 10, 10)).withBackground(Rgba.parse("#FF0000FF")), 0);

        Rectangle background = assertInstanceOf(Rectangle.class, renderer.getRoot().getChildren().get(0));
        assertEquals(200, background.getWidth());
        assertEquals(100, background.getHeight());
        assertEquals(Color.RED, background.getFill());
        Rectangle clip = assertInstanceOf(Rectangle.class, renderer.getRoot().getClip());
        assertEquals(200, clip.getWidth());
        assertEquals(100, clip.getHeight());
    }

    @Test
    void everyLayerTypeHasItsNodeType() {
        show(doc(rect("shape", 10, 10), text("text", "Hi"), image("image"), group("group", rect("child", 5, 5)),
                slot("slot", "card")).withAssets(List.of(IMAGE_ASSET)), 0);

        Group shape = assertInstanceOf(Group.class, renderer.getLayerNode("shape"));
        assertInstanceOf(Path.class, shape.getChildren().get(0));
        assertInstanceOf(Text.class, renderer.getLayerNode("text"));
        assertInstanceOf(ImageView.class, renderer.getLayerNode("image"));
        Group group = assertInstanceOf(Group.class, renderer.getLayerNode("group"));
        assertEquals(List.of(renderer.getLayerNode("child")), group.getChildren());
        assertInstanceOf(Group.class, renderer.getLayerNode("slot"));
    }

    @Test
    void zOrderFollowsLayerOrder() {
        show(doc(rect("bottom", 10, 10), rect("middle", 10, 10), rect("top", 10, 10)), 0);

        List<Node> children = renderer.getRoot().getChildren();
        assertEquals(4, children.size());
        assertSame(renderer.getLayerNode("bottom"), children.get(1));
        assertSame(renderer.getLayerNode("middle"), children.get(2));
        assertSame(renderer.getLayerNode("top"), children.get(3));
    }

    @Test
    void applyMovesANode() {
        Animatable<Vec2> position = keys(0, new Vec2(0, 0), 2, new Vec2(100, 40));
        Animation animation = doc(transformed(rect("r", 10, 10), Transform.IDENTITY.withPosition(position)));
        show(animation, 0);
        assertEquals(0, renderer.getLayerNode("r").getBoundsInParent().getMinX(), 1e-9);

        renderer.apply(Evaluator.sample(animation, 1));

        Bounds bounds = renderer.getLayerNode("r").getBoundsInParent();
        assertEquals(50, bounds.getMinX(), 1e-9);
        assertEquals(20, bounds.getMinY(), 1e-9);
        assertEquals(10, bounds.getWidth(), 1e-9);
    }

    @Test
    void layerBoundsAreTheGeometryInLayerCoordinates() {
        show(doc(at(rect("r", 30, 20), 50, 50)), 0);

        Bounds local = renderer.getLayerNode("r").getBoundsInLocal();
        assertEquals(0, local.getMinX(), 1e-9);
        assertEquals(0, local.getMinY(), 1e-9);
        assertEquals(30, local.getWidth(), 1e-9);
        assertEquals(20, local.getHeight(), 1e-9);
    }

    @Test
    void rotationAndScaleGoThroughTheLayerMatrix() {
        Transform transform = Transform.at(50, 50).withRotation(Animatable.of(90.0))
                .withScale(Animatable.of(new Vec2(2, 1)));
        show(doc(transformed(rect("r", 10, 10), transform)), 0);

        Bounds bounds = renderer.getLayerNode("r").getBoundsInParent();
        assertEquals(40, bounds.getMinX(), 1e-9);
        assertEquals(50, bounds.getMinY(), 1e-9);
        assertEquals(10, bounds.getWidth(), 1e-9);
        assertEquals(20, bounds.getHeight(), 1e-9);
    }

    @Test
    void opacityAndVisibilityAreApplied() {
        ShapeLayer layer = rect("r", 10, 10);
        Animation animation = doc(layer.withBase(layer.base().withOpacity(keys(0, 0.0, 2, 1.0)).withOutPoint(1.5)));
        show(animation, 1);
        assertEquals(0.5, renderer.getLayerNode("r").getOpacity(), 1e-9);
        assertTrue(renderer.getLayerNode("r").isVisible());

        renderer.apply(Evaluator.sample(animation, 1.75));

        assertFalse(renderer.getLayerNode("r").isVisible());
    }

    @Test
    void shapeFillAndStrokeAreMapped() {
        Stroke stroke = new Stroke(Animatable.of(new Paint.Solid(Rgba.parse("#0000FFFF"))), Animatable.of(3.0),
                Stroke.Cap.ROUND, Stroke.Join.BEVEL, 4, List.of(5.0, 2.0), Animatable.of(1.5));
        show(doc(rect("r", 10, 10).withFill(Animatable.of(new Paint.Solid(Rgba.parse("#FF0000FF"))))
                .withStroke(stroke)), 0);

        Path path = fillPath("r");
        assertEquals(1, ((Group) renderer.getLayerNode("r")).getChildren().size());
        assertEquals(Color.RED, path.getFill());
        assertEquals(Color.BLUE, path.getStroke());
        assertEquals(3, path.getStrokeWidth());
        assertEquals(StrokeLineCap.ROUND, path.getStrokeLineCap());
        assertEquals(StrokeLineJoin.BEVEL, path.getStrokeLineJoin());
        assertEquals(4, path.getStrokeMiterLimit());
        assertEquals(List.of(5.0, 2.0), path.getStrokeDashArray());
        assertEquals(1.5, path.getStrokeDashOffset());
    }

    @Test
    void unstrokedUnfilledShapeHasNoPaints() {
        show(doc(rect("r", 10, 10).withFill(Animatable.of(new Paint.None()))), 0);

        assertNull(fillPath("r").getFill());
        assertNull(fillPath("r").getStroke());
    }

    @Test
    void trimmedShapeWithFillRendersFillAndStrokeAsTwoPaths() {
        Trim trim = new Trim(Animatable.of(0.0), Animatable.of(0.5), Animatable.of(0.0));
        show(doc(rect("r", 100, 50).withStroke(stroke(2)).withTrim(trim)), 0);

        Path fill = fillPath("r");
        Path stroke = strokePath("r");
        assertEquals(Color.WHITE, fill.getFill());
        assertNull(fill.getStroke());
        assertNull(stroke.getFill());
        assertEquals(Color.BLACK, stroke.getStroke());
        assertEquals(2, stroke.getStrokeWidth());
        // rectangle: move + 3 lines + close; half of its outline: move + 2 lines
        assertEquals(5, fill.getElements().size());
        assertEquals(3, stroke.getElements().size());
    }

    @Test
    void fullyVisibleTrimStrokesTheFillPath() {
        Trim trim = new Trim(Animatable.of(0.0), keys(0, 1.0, 2, 0.5), Animatable.of(0.0));
        Animation animation = doc(rect("r", 100, 50).withStroke(stroke(2)).withTrim(trim));
        show(animation, 0);
        assertEquals(Color.BLACK, fillPath("r").getStroke());
        assertFalse(strokePath("r").isVisible());

        renderer.apply(Evaluator.sample(animation, 2));

        assertNull(fillPath("r").getStroke());
        assertTrue(strokePath("r").isVisible());
        assertEquals(Color.BLACK, strokePath("r").getStroke());
    }

    @Test
    void textIsMappedWithItsTopLeftAtTheLayerOrigin() {
        TextLayer layer = text("t", "Hello").withFontWeight(TextLayer.Weight.BOLD).withItalic(true)
                .withAlignment(TextLayer.Align.CENTER)
                .withFill(Animatable.of(new Paint.Solid(Rgba.parse("#00FF00FF"))));
        show(doc(layer), 0);

        Text text = (Text) renderer.getLayerNode("t");
        assertEquals("Hello", text.getText());
        assertEquals(VPos.TOP, text.getTextOrigin());
        assertEquals(20, text.getFont().getSize());
        assertEquals(TextAlignment.CENTER, text.getTextAlignment());
        assertEquals(Color.LIME, text.getFill());
        assertNull(text.getStroke());
        assertEquals(0, text.getBoundsInLocal().getMinY(), 0.5);
    }

    @Test
    void textIsAlignedAboutTheLayerOrigin() {
        Animation animation = doc(at(text("l", "Hello"), 100, 50),
                at(text("c", "Hello").withAlignment(TextLayer.Align.CENTER), 100, 50),
                at(text("r", "Hello").withAlignment(TextLayer.Align.RIGHT).withFontSize(keys(0, 20.0, 2, 60.0)), 100, 50));
        show(animation, 0);

        Bounds left = renderer.getLayerNode("l").getBoundsInParent();
        Bounds centre = renderer.getLayerNode("c").getBoundsInParent();
        Bounds right = renderer.getLayerNode("r").getBoundsInParent();
        double width = left.getWidth();
        assertTrue(width > 30, "a real width: " + width);
        // left: starts at the origin; centre: centred on it; right: ends at it; the top stays the origin
        assertEquals(100, left.getMinX(), 1);
        assertEquals(100, centre.getCenterX(), 1);
        assertEquals(width, centre.getWidth(), 0.01);
        assertEquals(100, right.getMaxX(), 1);
        assertEquals(100 - width, right.getMinX(), 1);
        for (Bounds bounds : List.of(left, centre, right)) {
            assertEquals(50, bounds.getMinY(), 0.5);
        }

        // the alignment follows the font size
        renderer.apply(Evaluator.sample(animation, 2));
        Bounds larger = renderer.getLayerNode("r").getBoundsInParent();
        assertEquals(100, larger.getMaxX(), 2);
        assertEquals(3 * width, larger.getWidth(), 3);

        // and the text
        show(doc(at(text("c", "Hello, hello").withAlignment(TextLayer.Align.CENTER), 100, 50)), 0);
        Bounds longer = renderer.getLayerNode("c").getBoundsInParent();
        assertTrue(longer.getWidth() > 1.5 * width);
        assertEquals(100, longer.getCenterX(), 1);
    }

    @Test
    void animatedFontSizeUpdatesTheFont() {
        Animation animation = doc(text("t", "Hello").withFontSize(keys(0, 10.0, 2, 30.0)));
        show(animation, 0);

        renderer.apply(Evaluator.sample(animation, 1));

        assertEquals(20, ((Text) renderer.getLayerNode("t")).getFont().getSize());
    }

    @Test
    void imageIsDrawnAtTheLayerOriginWithTheLayerSize() {
        show(doc(image("i")).withAssets(List.of(IMAGE_ASSET)), 0);

        ImageView view = (ImageView) renderer.getLayerNode("i");
        assertNotNull(view.getImage());
        assertFalse(view.getImage().isError());
        assertEquals(40, view.getBoundsInLocal().getWidth(), 1e-9);
        assertEquals(30, view.getBoundsInLocal().getHeight(), 1e-9);
        assertEquals(0, view.getBoundsInLocal().getMinX(), 1e-9);
    }

    @Test
    void anImageWithoutWidthOrHeightDrawsNothing() {
        // a fit size of 0 means "the size of the image" to an ImageView
        show(doc(image("flat").withHeight(0), image("thin").withWidth(0), image("back").withWidth(-5))
                .withAssets(List.of(IMAGE_ASSET)), 0);

        for (String id : List.of("flat", "thin", "back")) {
            Bounds bounds = renderer.getLayerNode(id).getBoundsInLocal();
            assertEquals(0, bounds.getWidth() * bounds.getHeight(), id);
            assertNull(((ImageView) renderer.getLayerNode(id)).getImage(), id);
        }

        show(doc(image("flat").withHeight(12)).withAssets(List.of(IMAGE_ASSET)), 0);
        assertEquals(12, renderer.getLayerNode("flat").getBoundsInLocal().getHeight(), 1e-9);
    }

    @Test
    void missingOrBrokenAssetsDoNotStopRendering() {
        Animation animation = doc(image("i"), text("t", "Hi").withFontAssetId("font"), rect("r", 10, 10))
                .withAssets(List.of(IMAGE_ASSET, FONT_ASSET));

        show(new AnimationFile(animation, Map.of("font", new byte[]{1, 2, 3})), 0);

        assertNull(((ImageView) renderer.getLayerNode("i")).getImage());
        assertEquals(20, ((Text) renderer.getLayerNode("t")).getFont().getSize());
        assertNotNull(renderer.getLayerNode("r"));
    }

    @Test
    void fontAssetSuppliesTheFontFamily() throws Exception {
        java.nio.file.Path ttf = java.nio.file.Path.of("/System/Library/Fonts/Supplemental/Courier New.ttf");
        Assumptions.assumeTrue(java.nio.file.Files.isReadable(ttf), "needs a TrueType font file");
        Animation animation = doc(text("t", "Hi").withFontFamily("System").withFontAssetId("font"))
                .withAssets(List.of(FONT_ASSET));

        show(new AnimationFile(animation, Map.of("font", java.nio.file.Files.readAllBytes(ttf))), 0);

        assertEquals("Courier New", ((Text) renderer.getLayerNode("t")).getFont().getFamily());
    }

    @Test
    void replacedAssetContentIsLoadedIntoTheSameNode() {
        Animation animation = doc(image("i")).withAssets(List.of(IMAGE_ASSET));
        show(new AnimationFile(animation, Map.of()), 0);
        Node view = renderer.getLayerNode("i");
        assertNull(((ImageView) view).getImage());

        show(new AnimationFile(animation, Map.of("img", PNG)), 0);

        assertSame(view, renderer.getLayerNode("i"));
        assertNotNull(((ImageView) view).getImage());
    }

    @Test
    void clipMaskBecomesTheClipAndLeavesTheVisibleTree() {
        show(doc(rect("mask", 20, 20), masked(rect("content", 50, 50), "mask", Mask.Mode.CLIP)), 0);

        Node content = renderer.getLayerNode("content");
        Node mask = renderer.getLayerNode("mask");
        assertNotNull(mask);
        assertSame(mask, content.getClip());
        assertNull(mask.getParent());
        assertEquals(2, renderer.getRoot().getChildren().size());
        assertSame(content, renderer.getRoot().getChildren().get(1));
        assertEquals(20, content.getBoundsInParent().getWidth(), 1e-9);
    }

    @Test
    void clipMaskUsesGeometryOnly() {
        ShapeLayer mask = rect("mask", 20, 20).withFill(Animatable.of(new Paint.None())).withStroke(stroke(4));
        mask = withEffects(mask, new Effect.Blur(Animatable.of(5.0)));
        mask = mask.withBase(mask.base().withOpacity(Animatable.of(0.25)));
        show(doc(mask, masked(rect("content", 50, 50), "mask", Mask.Mode.CLIP)), 0);

        Node clip = renderer.getLayerNode("content").getClip();
        Path path = (Path) ((Group) clip).getChildren().get(0);
        assertEquals(1, clip.getOpacity());
        assertNull(clip.getEffect());
        assertEquals(Color.BLACK, path.getFill());
        assertNull(path.getStroke());
    }

    @Test
    void alphaMaskKeepsItsRenderedLook() {
        ShapeLayer mask = rect("mask", 20, 20);
        mask = mask.withBase(mask.base().withOpacity(Animatable.of(0.25)));
        show(doc(mask, masked(rect("content", 50, 50), "mask", Mask.Mode.ALPHA)), 0);

        Node clip = renderer.getLayerNode("content").getClip();
        assertEquals(0.25, clip.getOpacity());
        assertEquals(Color.WHITE, ((Path) ((Group) clip).getChildren().get(0)).getFill());
    }

    @Test
    void maskIsPlacedInTheParentSpaceOfTheMaskedLayer() {
        Animatable<Vec2> position = keys(0, new Vec2(30, 0), 2, new Vec2(50, 0));
        Animation animation = doc(at(rect("mask", 20, 20), 40, 10),
                masked(transformed(rect("content", 50, 50), Transform.IDENTITY.withPosition(position)), "mask",
                        Mask.Mode.CLIP));
        show(animation, 0);

        // the mask stays at document x 40..60 while the content moves under it
        Bounds bounds = renderer.getLayerNode("content").getBoundsInParent();
        assertEquals(40, bounds.getMinX(), 1e-9);
        assertEquals(10, bounds.getMinY(), 1e-9);
        assertEquals(20, bounds.getWidth(), 1e-9);

        renderer.apply(Evaluator.sample(animation, 2));

        bounds = renderer.getLayerNode("content").getBoundsInParent();
        assertEquals(50, bounds.getMinX(), 1e-9);
        assertEquals(10, bounds.getWidth(), 1e-9);
    }

    @Test
    void oneMaskLayerCanMaskSeveralLayers() {
        show(doc(rect("mask", 20, 20), masked(rect("a", 50, 50), "mask", Mask.Mode.CLIP),
                masked(rect("b", 50, 50), "mask", Mask.Mode.CLIP)), 0);

        assertNotNull(renderer.getLayerNode("a").getClip());
        assertNotNull(renderer.getLayerNode("b").getClip());
        assertNotSame(renderer.getLayerNode("a").getClip(), renderer.getLayerNode("b").getClip());
        assertEquals(20, renderer.getLayerNode("b").getBoundsInParent().getWidth(), 1e-9);
    }

    @Test
    void unknownOrCyclicMasksAreIgnored() {
        show(doc(masked(rect("a", 10, 10), "nope", Mask.Mode.CLIP), masked(rect("self", 10, 10), "self",
                        Mask.Mode.CLIP), masked(rect("x", 10, 10), "y", Mask.Mode.CLIP),
                masked(rect("y", 10, 10), "x", Mask.Mode.CLIP), masked(rect("z", 10, 10), "x", Mask.Mode.CLIP)), 0);

        assertNull(renderer.getLayerNode("a").getClip());
        assertNotNull(renderer.getLayerNode("z").getClip());
    }

    @Test
    void effectsAreChainedInListOrder() {
        show(doc(withEffects(rect("r", 10, 10), new Effect.Blur(Animatable.of(3.0)),
                new Effect.DropShadow(Animatable.of(Rgba.parse("#FF0000FF")), Animatable.of(6.0), Animatable.of(1.0),
                        Animatable.of(2.0), Animatable.of(0.5)))), 0);

        DropShadow shadow = assertInstanceOf(DropShadow.class, renderer.getLayerNode("r").getEffect());
        assertEquals(6, shadow.getRadius());
        assertEquals(1, shadow.getOffsetX());
        assertEquals(2, shadow.getOffsetY());
        assertEquals(0.5, shadow.getSpread());
        assertEquals(Color.RED, shadow.getColor());
        GaussianBlur blur = assertInstanceOf(GaussianBlur.class, shadow.getInput());
        assertEquals(3, blur.getRadius());
        assertNull(blur.getInput());
    }

    @Test
    void animatedEffectValuesUpdateTheSameEffectInstances() {
        Animation animation = doc(withEffects(rect("r", 10, 10), new Effect.Glow(keys(0, 0.0, 2, 1.0))));
        show(animation, 0);
        Glow glow = assertInstanceOf(Glow.class, renderer.getLayerNode("r").getEffect());
        assertEquals(0, glow.getLevel());

        renderer.apply(Evaluator.sample(animation, 1));

        assertSame(glow, renderer.getLayerNode("r").getEffect());
        assertEquals(0.5, glow.getLevel());
    }

    @Test
    void changedValuesKeepTheNodes() {
        Animation animation = doc(at(rect("r", 10, 10), 5, 5), text("t", "Hi"), group("g", rect("child", 5, 5)));
        show(animation, 0);
        Node shape = renderer.getLayerNode("r");
        Node text = renderer.getLayerNode("t");
        Node child = renderer.getLayerNode("child");

        Animation edited = doc(
                at(rect("r", 30, 10).withStroke(stroke(2)).withTrim(new Trim(Animatable.of(0.0), Animatable.of(0.5),
                        Animatable.of(0.0))), 60, 5),
                withEffects(text("t", "Changed").withFontSize(Animatable.of(40.0)), new Effect.Glow(Animatable.of(1.0))),
                group("g", rect("child", 5, 5).withBase(base("child").withName("renamed").withOutPoint(1))));
        show(edited, 0);

        assertSame(shape, renderer.getLayerNode("r"));
        assertSame(text, renderer.getLayerNode("t"));
        assertSame(child, renderer.getLayerNode("child"));
        assertEquals(60, shape.getBoundsInParent().getMinX(), 1.01);
        assertEquals(30, fillPath("r").getBoundsInLocal().getWidth(), 1e-9);
        assertEquals("Changed", ((Text) text).getText());
        assertEquals(40, ((Text) text).getFont().getSize());
        assertInstanceOf(Glow.class, text.getEffect());
    }

    @Test
    void changedStructureRebuilds() {
        show(doc(rect("a", 10, 10), rect("b", 10, 10)), 0);

        show(doc(rect("b", 10, 10), rect("a", 10, 10), rect("c", 10, 10)), 0);

        List<Node> children = renderer.getRoot().getChildren();
        assertEquals(4, children.size());
        assertSame(renderer.getLayerNode("b"), children.get(1));
        assertSame(renderer.getLayerNode("a"), children.get(2));
        assertSame(renderer.getLayerNode("c"), children.get(3));

        show(doc(rect("c", 10, 10)), 0);

        assertEquals(2, renderer.getRoot().getChildren().size());
        assertNull(renderer.getLayerNode("a"));
    }

    @Test
    void changedLayerTypeRebuilds() {
        show(doc(rect("a", 10, 10)), 0);

        show(doc(text("a", "Hi")), 0);

        assertInstanceOf(Text.class, renderer.getLayerNode("a"));
    }

    @Test
    void onlyChangedValuesAreWritten() {
        Animatable<Vec2> position = keys(0, new Vec2(0, 0), 2, new Vec2(100, 40));
        Animation animation = doc(transformed(rect("r", 10, 10), Transform.IDENTITY.withPosition(position)));
        show(animation, 0);
        int[] changes = {0};
        fillPath("r").getElements().addListener((ListChangeListener<PathElement>) change -> changes[0]++);
        fillPath("r").fillProperty().addListener(observable -> changes[0]++);
        renderer.getLayerNode("r").opacityProperty().addListener(observable -> changes[0]++);

        renderer.apply(Evaluator.sample(animation, 1));
        renderer.apply(Evaluator.sample(animation, 1));

        assertEquals(0, changes[0]);
        assertEquals(50, renderer.getLayerNode("r").getBoundsInParent().getMinX(), 1e-9);
    }

    @Test
    void animatedGeometryReusesThePathElements() {
        Geometry geometry = new Geometry.Rect(keys(0, new Vec2(10, 10), 2, new Vec2(30, 10)), Animatable.of(0.0));
        Animation animation = doc(rect("r", 1, 1).withGeometry(geometry));
        show(animation, 0);
        List<PathElement> before = List.copyOf(fillPath("r").getElements());

        renderer.apply(Evaluator.sample(animation, 1));

        assertEquals(20, renderer.getLayerNode("r").getBoundsInLocal().getWidth(), 1e-9);
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), fillPath("r").getElements().get(i));
        }
    }

    @Test
    void slotContainerIsTheSlotLayersNode() {
        show(doc(at(slot("s", "card"), 20, 10)), 0);

        Group container = renderer.getSlotContainer("card");
        assertNotNull(container);
        assertSame(renderer.getLayerNode("s"), container);
        assertTrue(container.getChildren().isEmpty());
        assertNull(renderer.getSlotContainer("other"));

        container.getChildren().add(new Rectangle(80, 40));
        assertEquals(20, container.getBoundsInParent().getMinX(), 1e-9);
    }

    @Test
    void slotContentSurvivesAValueChange() {
        show(doc(slot("s", "card")), 0);
        Rectangle embedded = new Rectangle(80, 40);
        renderer.getSlotContainer("card").getChildren().add(embedded);

        show(doc(at(slot("s", "card"), 20, 10)), 0);

        assertEquals(List.of(embedded), renderer.getSlotContainer("card").getChildren());
    }

    @Test
    void frameOfAnotherDocumentLeavesUnknownLayersAlone() {
        show(doc(at(rect("r", 10, 10), 5, 5)), 0);

        renderer.apply(Evaluator.sample(doc(rect("other", 10, 10)), 0));

        assertEquals(5, renderer.getLayerNode("r").getBoundsInParent().getMinX(), 1e-9);
    }

    @Test
    void clearingTheDocumentEmptiesTheRoot() {
        show(doc(rect("r", 10, 10)), 0);

        renderer.setDocument(null);

        assertTrue(renderer.getRoot().getChildren().isEmpty());
        assertNull(renderer.getLayerNode("r"));
    }

    private static AudioLayer audio(String id) {
        return new AudioLayer(base(id).withTransform(Transform.at(50, 60)), "snd", 0, Animatable.of(1.0),
                Animatable.of(0.0), false, false);
    }

    @Test
    void audioLayerHasAnEmptyInvisibleNode() {
        show(doc(rect("r", 10, 10), audio("snd")), 0);

        Group node = assertInstanceOf(Group.class, renderer.getLayerNode("snd"));
        assertTrue(node.getChildren().isEmpty());
        assertFalse(node.isVisible());
        assertTrue(node.getBoundsInParent().isEmpty());
        assertTrue(renderer.getLayerNode("r").isVisible());
    }

    @Test
    void audioLayerNeitherMasksNorIsMasked() {
        show(doc(masked(rect("r", 10, 10), "snd", Mask.Mode.ALPHA), masked(audio("snd"), "m", Mask.Mode.CLIP),
                rect("m", 5, 5)), 0);

        assertNull(renderer.getLayerNode("r").getClip());
        assertNull(renderer.getLayerNode("snd").getClip());
        assertTrue(renderer.getRoot().getChildren().contains(renderer.getLayerNode("m")), "m is rendered itself");
        assertTrue(renderer.getLayerNode("r").isVisible());
    }
}
