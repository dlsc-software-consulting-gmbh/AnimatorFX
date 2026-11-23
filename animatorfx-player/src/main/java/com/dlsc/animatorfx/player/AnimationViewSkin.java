package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.render.Renderer;
import javafx.beans.InvalidationListener;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.SkinBase;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

/**
 * The skin of {@link AnimationView}: renders the frames of the view's player with a
 * {@link Renderer}, scaled and centred by the view's content transform and clipped to the view.
 */
public class AnimationViewSkin extends SkinBase<AnimationView> {

    private final Renderer renderer;
    private final Affine contentTransform = new Affine();
    private final Rectangle clip = new Rectangle();
    private final Group content;
    private final AnimationPlayer player;
    private final InvalidationListener playerListener = observable -> sync();
    private final Runnable bindingsListener = this::attachEmbeddedNodes;
    private AnimationFile shown;

    /**
     * Creates the skin for a view.
     */
    public AnimationViewSkin(AnimationView view) {
        super(view);
        player = view.getPlayer();
        renderer = new Renderer(player.video());
        content = new Group(renderer.getRoot());
        player.resumeVideo();
        registerChangeListener(view.skinProperty(), observable -> {
            if (view.getSkin() == this) player.resumeVideo();
        });
        renderer.getRoot().getTransforms().add(contentTransform);
        content.setClip(clip);
        // the view's size does not depend on what the content happens to cover
        content.setManaged(false);
        getChildren().add(content);

        player.frameProperty().addListener(playerListener);
        player.animationProperty().addListener(playerListener);
        player.setBindingsListener(bindingsListener);
        sync();
    }

    /** Shows the player's document and frame; the document is always set before its frames. */
    private void sync() {
        AnimationFile file = player.getAnimation();
        if (file != shown) {
            shown = file;
            renderer.setDocument(file);
            attachEmbeddedNodes();
            getSkinnable().requestLayout();
        }
        Frame frame = player.getFrame();
        if (frame != null) {
            renderer.apply(frame);
        }
    }

    private void attachEmbeddedNodes() {
        player.forEachEmbedded((slot, binding) ->
                binding.attach(renderer.getSlotContainer(slot.slotName()), slot.width(), slot.height()));
    }

    Node getLayerNode(String layerId) {
        return renderer.getLayerNode(layerId);
    }

    @Override
    public void dispose() {
        player.frameProperty().removeListener(playerListener);
        player.animationProperty().removeListener(playerListener);
        player.removeBindingsListener(bindingsListener);
        getChildren().remove(content);
        renderer.dispose();
        player.video().release();
        super.dispose();
    }

    @Override
    protected void layoutChildren(double x, double y, double width, double height) {
        clip.setX(x);
        clip.setY(y);
        clip.setWidth(width);
        clip.setHeight(height);
        contentTransform.setToTransform(getSkinnable().getContentTransform());
    }

    private double documentWidth() {
        return shown == null ? 0 : shown.animation().width();
    }

    private double documentHeight() {
        return shown == null ? 0 : shown.animation().height();
    }

    @Override
    protected double computeMinWidth(double height, double top, double right, double bottom, double left) {
        return left + right;
    }

    @Override
    protected double computeMinHeight(double width, double top, double right, double bottom, double left) {
        return top + bottom;
    }

    @Override
    protected double computePrefWidth(double height, double top, double right, double bottom, double left) {
        return left + documentWidth() + right;
    }

    @Override
    protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
        return top + documentHeight() + bottom;
    }

    @Override
    protected double computeMaxWidth(double height, double top, double right, double bottom, double left) {
        return Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width, double top, double right, double bottom, double left) {
        return Double.MAX_VALUE;
    }
}
