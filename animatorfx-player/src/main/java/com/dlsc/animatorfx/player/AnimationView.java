package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.AnimationPlayer.Status;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WritableValue;
import javafx.css.CssMetaData;
import javafx.css.SimpleStyleableBooleanProperty;
import javafx.css.SimpleStyleableObjectProperty;
import javafx.css.StyleConverter;
import javafx.css.StyleOrigin;
import javafx.css.Styleable;
import javafx.css.StyleableBooleanProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Transform;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shows an animation document. The view owns an {@link AnimationPlayer}; its playback properties
 * and methods are available on the view as well.
 * <pre>{@code
 * AnimationView view = new AnimationView(AnimationIO.read(url));
 * view.setCycleCount(AnimationPlayer.INDEFINITE);
 * view.play();
 * }</pre>
 * The document is scaled uniformly according to {@link #fitProperty() fit}, centred and clipped
 * to the view. The preferred size is the document size; the view can be resized freely.
 * <p>
 * While the view is not part of a showing window a playing player stands still and continues
 * when the view is showing again, unless it drives nodes bound to slots in place.
 * <p>
 * Audio layers and sound cues of the document sound during playback, see
 * {@link #mutedProperty() muted} and {@link #volumeProperty() volume}. A view that is not
 * showing is silent as its player stands still, again unless it drives nodes in place. A view
 * that leaves its scene releases its media resources and gets them back when it plays in a
 * scene again; otherwise call {@link #dispose()} when the view is no longer needed.
 * <p>
 * Style class {@code animation-view}; CSS properties {@code -fx-fit} (contain | cover | none)
 * and {@code -fx-auto-play}.
 */
public class AnimationView extends Control {

    private static final CssMetaData<AnimationView, Fit> FIT = new CssMetaData<>("-fx-fit",
            StyleConverter.getEnumConverter(Fit.class), Fit.CONTAIN) {
        @Override
        public boolean isSettable(AnimationView view) {
            return !view.fit.isBound();
        }

        @Override
        public StyleableProperty<Fit> getStyleableProperty(AnimationView view) {
            return view.fit;
        }
    };

    private static final CssMetaData<AnimationView, Boolean> AUTO_PLAY = new CssMetaData<>("-fx-auto-play",
            StyleConverter.getBooleanConverter(), Boolean.FALSE) {
        @Override
        public boolean isSettable(AnimationView view) {
            return !view.autoPlay.isBound();
        }

        @Override
        public StyleableProperty<Boolean> getStyleableProperty(AnimationView view) {
            return view.autoPlay;
        }
    };

    private static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA;

    static {
        List<CssMetaData<? extends Styleable, ?>> list = new ArrayList<>(Control.getClassCssMetaData());
        list.add(FIT);
        list.add(AUTO_PLAY);
        CSS_META_DATA = Collections.unmodifiableList(list);
    }

    private final AnimationPlayer player;
    private final AnimationElements elements = new AnimationElements(this::refreshElements);
    private boolean showing;

    private final ChangeListener<Boolean> showingListener = (property, was, is) -> updateShowing();
    private final ChangeListener<Window> windowListener = (property, before, now) -> {
        if (before != null) {
            before.showingProperty().removeListener(showingListener);
        }
        if (now != null) {
            now.showingProperty().addListener(showingListener);
        }
        updateShowing();
    };

    /**
     * Creates a view without an animation.
     */
    public AnimationView() {
        this(new AnimationPlayer());
    }

    /**
     * Creates a view that shows a document.
     */
    public AnimationView(AnimationFile file) {
        this();
        setAnimation(file);
    }

    @SuppressWarnings("unchecked")
    AnimationView(AnimationPlayer player) {
        this.player = player;
        getStyleClass().add("animation-view");
        ((StyleableProperty<Boolean>) (WritableValue<Boolean>) focusTraversableProperty())
                .applyStyle(null, Boolean.FALSE);

        player.setSuspended(true);
        sceneProperty().addListener((property, before, now) -> {
            if (before != null) {
                before.windowProperty().removeListener(windowListener);
            }
            if (now != null) {
                now.windowProperty().addListener(windowListener);
            }
            windowListener.changed(null, before == null ? null : before.getWindow(),
                    now == null ? null : now.getWindow());
            if (now == null && !player.hasInPlaceBindings()) {
                player.releaseMedia();
            }
        });
        player.animationProperty().addListener(observable -> {
            elements.setDocument(getAnimation());
            requestLayout();
            autoPlayIfShowing();
        });
        fitProperty().addListener(observable -> requestLayout());
        autoPlayProperty().addListener(observable -> autoPlayIfShowing());
        skinProperty().addListener(observable -> refreshElements());
    }

    private void updateShowing() {
        Scene scene = getScene();
        boolean now = scene != null && scene.getWindow() != null && scene.getWindow().isShowing();
        if (now != showing) {
            showing = now;
            player.setSuspended(!now);
            autoPlayIfShowing();
        }
    }

    private void autoPlayIfShowing() {
        if (showing && isAutoPlay() && getAnimation() != null && getStatus() == Status.STOPPED) {
            play();
        }
    }

    /**
     * The player that drives this view.
     */
    public final AnimationPlayer getPlayer() {
        return player;
    }

    /**
     * The document to show; the player's {@link AnimationPlayer#animationProperty() animation}.
     *
     * @return the animation property
     */
    public final ObjectProperty<AnimationFile> animationProperty() {
        return player.animationProperty();
    }

    public final AnimationFile getAnimation() {
        return player.getAnimation();
    }

    public final void setAnimation(AnimationFile value) {
        player.setAnimation(value);
    }

    /**
     * The playback position in seconds; the player's
     * {@link AnimationPlayer#currentTimeProperty() current time}.
     *
     * @return the current time property
     */
    public final DoubleProperty currentTimeProperty() {
        return player.currentTimeProperty();
    }

    public final double getCurrentTime() {
        return player.getCurrentTime();
    }

    public final void setCurrentTime(double value) {
        player.setCurrentTime(value);
    }

    /**
     * The playback speed; the player's {@link AnimationPlayer#rateProperty() rate}.
     *
     * @return the rate property
     */
    public final DoubleProperty rateProperty() {
        return player.rateProperty();
    }

    public final double getRate() {
        return player.getRate();
    }

    public final void setRate(double value) {
        player.setRate(value);
    }

    /**
     * How often the animation is played, or {@link AnimationPlayer#INDEFINITE}; the player's
     * {@link AnimationPlayer#cycleCountProperty() cycle count}.
     *
     * @return the cycle count property
     */
    public final IntegerProperty cycleCountProperty() {
        return player.cycleCountProperty();
    }

    public final int getCycleCount() {
        return player.getCycleCount();
    }

    public final void setCycleCount(int value) {
        player.setCycleCount(value);
    }

    /**
     * Whether every second cycle runs backwards; the player's
     * {@link AnimationPlayer#autoReverseProperty() auto reverse}.
     *
     * @return the auto reverse property
     */
    public final BooleanProperty autoReverseProperty() {
        return player.autoReverseProperty();
    }

    public final boolean isAutoReverse() {
        return player.isAutoReverse();
    }

    public final void setAutoReverse(boolean value) {
        player.setAutoReverse(value);
    }

    /**
     * The playback state; the player's {@link AnimationPlayer#statusProperty() status}.
     *
     * @return the status property
     */
    public final ReadOnlyObjectProperty<Status> statusProperty() {
        return player.statusProperty();
    }

    public final Status getStatus() {
        return player.getStatus();
    }

    /**
     * Whether the sound of the animation is switched off; the player's
     * {@link AnimationPlayer#mutedProperty() muted}. Sound needs the {@code javafx.media} module;
     * where audio is not available the animation plays silently.
     *
     * @return the muted property
     */
    public final BooleanProperty mutedProperty() {
        return player.mutedProperty();
    }

    public final boolean isMuted() {
        return player.isMuted();
    }

    public final void setMuted(boolean value) {
        player.setMuted(value);
    }

    /**
     * The volume of the animation's sound, {@code 0..1}; the player's
     * {@link AnimationPlayer#volumeProperty() volume}.
     *
     * @return the volume property
     */
    public final DoubleProperty volumeProperty() {
        return player.volumeProperty();
    }

    public final double getVolume() {
        return player.getVolume();
    }

    public final void setVolume(double value) {
        player.setVolume(value);
    }

    private final StyleableObjectProperty<Fit> fit = new SimpleStyleableObjectProperty<>(FIT, this, "fit",
            Fit.CONTAIN) {
        @Override
        public void applyStyle(StyleOrigin origin, Fit value) {
            // CSS reads the keyword "none" as "no value" and passes null
            super.applyStyle(origin, value == null ? Fit.NONE : value);
        }
    };

    /**
     * How the document is scaled to the view. The default is {@link Fit#CONTAIN}. CSS property
     * {@code -fx-fit}.
     *
     * @return the fit property
     */
    public final ObjectProperty<Fit> fitProperty() {
        return fit;
    }

    public final Fit getFit() {
        return fit.get();
    }

    public final void setFit(Fit value) {
        fit.set(value);
    }

    private final StyleableBooleanProperty autoPlay = new SimpleStyleableBooleanProperty(AUTO_PLAY, this,
            "autoPlay", false);

    /**
     * Whether a stopped view starts playing by itself as soon as it has an animation and is part
     * of a showing window. The default is {@code false}. CSS property {@code -fx-auto-play}.
     *
     * @return the auto play property
     */
    public final BooleanProperty autoPlayProperty() {
        return autoPlay;
    }

    public final boolean isAutoPlay() {
        return autoPlay.get();
    }

    public final void setAutoPlay(boolean value) {
        autoPlay.set(value);
    }

    /**
     * Starts or resumes playback, see {@link AnimationPlayer#play()}.
     */
    public void play() {
        player.play();
    }

    /**
     * Interrupts playback, see {@link AnimationPlayer#pause()}.
     */
    public void pause() {
        player.pause();
    }

    /**
     * Ends playback and goes back to the start, see {@link AnimationPlayer#stop()}.
     */
    public void stop() {
        player.stop();
    }

    /**
     * Moves to a time in seconds, see {@link AnimationPlayer#seek(double)}.
     */
    public void seek(double seconds) {
        player.seek(seconds);
    }

    /**
     * Moves to a time and, while not playing, lets the audio at that time sound briefly; see
     * {@link AnimationPlayer#scrub(double)}.
     */
    public void scrub(double seconds) {
        player.scrub(seconds);
    }

    /**
     * Stops all sound and releases the media resources and temporary files of the animation,
     * see {@link AnimationPlayer#dispose()}. Call this when a view that played sound is no
     * longer needed and may still be in a scene; a view that leaves its scene releases them by
     * itself unless it drives nodes in place.
     */
    public void dispose() {
        player.dispose();
    }

    /**
     * Plays the range of a marker, see {@link AnimationPlayer#playMarker(String)}.
     *
     * @throws IllegalArgumentException if the animation has no marker of that name
     */
    public void playMarker(String name) {
        player.playMarker(name);
    }

    /**
     * Binds a node to a slot embedded: the node becomes the content of the slot inside this view
     * and gets the slot's transform, opacity, mask and effects. A resizable node is resized to
     * the slot's size. An earlier binding of the slot is released first.
     *
     * @throws IllegalStateException    if no animation is set
     * @throws IllegalArgumentException if the animation has no slot of that name
     */
    public void bind(String slotName, Node node) {
        player.bindEmbedded(slotName, node);
    }

    /**
     * Binds every unbound slot in place to the node below {@code root} (or {@code root} itself)
     * whose id is the slot name. Slots without such a node stay unbound.
     *
     * @see AnimationPlayer#bindInPlace(String, Node)
     */
    public void autoBind(Node root) {
        autoBind(root, Map.of());
    }

    /**
     * Like {@link #autoBind(Node)}, but a slot name is first looked up in the namespace of an
     * FXML document ({@code FXMLLoader.getNamespace()}), which knows nodes by their
     * {@code fx:id}.
     */
    public void autoBind(Node root, Map<String, Object> fxmlNamespace) {
        for (String slotName : List.copyOf(player.slotNames())) {
            if (player.getBoundSlots().contains(slotName)) {
                continue;
            }
            Node node = fxmlNamespace.get(slotName) instanceof Node named ? named : root.lookup("#" + slotName);
            if (node != null) {
                player.bindInPlace(slotName, node);
            }
        }
    }

    /**
     * Releases the binding of a slot, embedded or in place, see
     * {@link AnimationPlayer#unbind(String)}.
     */
    public void unbind(String slotName) {
        player.unbind(slotName);
    }

    /**
     * The node that renders a layer, for tooling. {@code null} for an unknown layer id and as
     * long as the view has no skin; the skin is created by the first CSS pass
     * ({@link #applyCss()}) once the view is in a scene. The node is replaced when the structure
     * of the document changes.
     */
    public Node getLayerNode(String layerId) {
        return getSkin() instanceof AnimationViewSkin skin ? skin.getLayerNode(layerId) : null;
    }

    /**
     * A live handle to a visual layer's unique {@code exportName}, including layers inside
     * groups. Repeated lookup returns the same handle for this view. Configure the handle
     * before CSS creates its node; interactions and overrides follow later node replacements.
     * Audio layers are not visual elements. Mask layers expose clip nodes, not visible content.
     *
     * @param exportName the exact export name, not the display name or internal layer id
     * @return the live element handle
     * @throws IllegalStateException if no animation is set
     * @throws IllegalArgumentException if the current document has no visual export of that name
     */
    public AnimationElement getElement(String exportName) {
        elements.setDocument(getAnimation());
        return elements.get(exportName);
    }

    AnimationElements elements() {
        return elements;
    }

    private void refreshElements() {
        if (getSkin() instanceof AnimationViewSkin skin) {
            skin.refreshElements();
        } else {
            elements.handles().forEach(element -> element.attach(null));
        }
    }

    /**
     * The transform from document coordinates to the local coordinates of this view for its
     * current size: the uniform scale of the {@link #fitProperty() fit} and the centring. The
     * result is a new {@link Affine} that does not follow later changes.
     */
    public Transform getContentTransform() {
        Insets insets = getInsets();
        double width = Math.max(0, getWidth() - insets.getLeft() - insets.getRight());
        double height = Math.max(0, getHeight() - insets.getTop() - insets.getBottom());
        AnimationFile file = getAnimation();
        Animation document = file == null ? null : file.animation();
        if (document == null || !(document.width() > 0) || !(document.height() > 0)) {
            return new Affine(1, 0, insets.getLeft(), 0, 1, insets.getTop());
        }
        double scaleX = width / document.width();
        double scaleY = height / document.height();
        Fit mode = getFit() == null ? Fit.CONTAIN : getFit();
        double scale = switch (mode) {
            case CONTAIN -> Math.min(scaleX, scaleY);
            case COVER -> Math.max(scaleX, scaleY);
            case NONE -> 1;
        };
        return new Affine(
                scale, 0, insets.getLeft() + (width - document.width() * scale) / 2,
                0, scale, insets.getTop() + (height - document.height() * scale) / 2);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AnimationViewSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return AnimationView.class.getResource("animation-view.css").toExternalForm();
    }

    @Override
    protected Boolean getInitialFocusTraversable() {
        return Boolean.FALSE;
    }

    /**
     * The CSS meta data of this class.
     */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return CSS_META_DATA;
    }

    @Override
    protected List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return getClassCssMetaData();
    }
}
