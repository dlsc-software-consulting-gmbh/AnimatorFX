package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.internal.Clock;
import com.dlsc.animatorfx.player.internal.SlotBinding;
import com.dlsc.animatorfx.player.internal.audio.AudioEngine;
import com.dlsc.animatorfx.player.internal.audio.AudioOutput;
import com.dlsc.animatorfx.player.internal.audio.AudioSchedule;
import com.dlsc.animatorfx.player.internal.audio.MediaAudioOutput;
import com.dlsc.animatorfx.player.internal.video.MediaVideoOutput;
import com.dlsc.animatorfx.player.internal.video.VideoEngine;
import com.dlsc.animatorfx.player.internal.video.VideoOutput;
import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Plays an animation document without showing it: it owns the clock, samples the document and
 * drives the nodes bound to slots in place. An {@link AnimationView} owns a player to show the
 * document; a player on its own is enough when an animation only moves nodes of the application
 * ({@link #bindInPlace(String, Node)}).
 * <p>
 * Playback runs over the <em>active range</em>: the whole document, or the range of a marker
 * after {@link #playMarker(String)}. One traversal of the range is a cycle.
 * <p>
 * Audio layers and sound cues of the document sound while the player is playing forward; see
 * {@link #mutedProperty()} and {@link #volumeProperty()}. Sound needs the {@code javafx.media}
 * module; where audio is not available the animation plays silently. Call {@link #dispose()}
 * when a player that played sound is no longer needed.
 * <p>
 * Use on the JavaFX application thread only.
 */
public class AnimationPlayer {

    /** A cycle count that never ends. */
    public static final int INDEFINITE = -1;

    /** The playback state of a player. */
    public enum Status {
        /** Not playing; {@link #play()} starts a new run. */
        STOPPED,
        /** Time advances with the clock. */
        PLAYING,
        /** Playback is interrupted; {@link #play()} resumes it. */
        PAUSED
    }

    private static final String LOGGER_NAME = "com.dlsc.animatorfx.player";

    /** Guards a tick against a range that is tiny compared to the elapsed time. */
    private static final int MAX_CYCLES_PER_TICK = 1000;

    /** One {@code AnimationTimer}, created when first needed so a player can exist without a toolkit. */
    private static final class TimerClock implements Clock {

        private AnimationTimer timer;
        private DoubleConsumer tick;
        private long last;

        @Override
        public void start(DoubleConsumer tick) {
            this.tick = tick;
            last = -1;
            if (timer == null) {
                timer = new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        pulse(now);
                    }
                };
            }
            timer.start();
        }

        private void pulse(long now) {
            long before = last;
            last = now;
            if (before >= 0) {
                this.tick.accept((now - before) / 1e9);
            }
        }

        @Override
        public void stop() {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    private final Clock clock;
    private final System.Logger logger;

    /** The slots of the document by slot name. */
    private final Map<String, SlotLayer> slots = new LinkedHashMap<>();
    private final Map<String, SlotBinding> bindings = new LinkedHashMap<>();
    private final Set<String> warnedSlots = new HashSet<>();

    private double rangeStart;
    private double rangeEnd;
    private int completedCycles;
    /** Whether the current cycle runs against the sign of the rate (auto reverse). */
    private boolean reversed;
    /** Whether the markers starting exactly at the current time have fired already. */
    private boolean markersAtTimeFired;
    /** The same for the sound cues exactly at the current time; forward playback only. */
    private boolean cuesAtTimeFired;
    private boolean ticking;
    private boolean clockRunning;
    private boolean suspended;
    /** Changes with every control call, so a tick notices that a handler took over. */
    private int epoch;
    private Runnable bindingsListener;
    private Runnable stateMachineCompletion;

    private Function<AnimationFile, AudioOutput> audioOutputFactory;
    /** The sound of the current animation; {@code null} until needed and for a document without sound. */
    private AudioEngine audio;
    /** Whether the current animation has audio layers or sound cues. */
    private boolean hasSound;
    /** Set by {@link #dispose()}: no sound until playback is started again or the animation is replaced. */
    private boolean audioReleased;
    private final VideoEngine video;

    /**
     * Creates a player without an animation.
     */
    public AnimationPlayer() {
        this(new TimerClock());
    }

    /**
     * Creates a player for a document.
     */
    public AnimationPlayer(AnimationFile file) {
        this();
        setAnimation(file);
    }

    AnimationPlayer(Clock clock) {
        this(clock, System.getLogger(LOGGER_NAME));
    }

    AnimationPlayer(Clock clock, System.Logger logger) {
        this.clock = clock;
        this.logger = logger;
        video = new VideoEngine(() -> new MediaVideoOutput(logger, (id, error) -> {}, false), logger);
        rate.addListener(observable -> syncVideo());
        volume.addListener(observable -> syncVideo());
    }

    /** A player whose sound goes to the given output, whatever the animation. */
    AnimationPlayer(Clock clock, System.Logger logger, AudioOutput output) {
        this(clock, logger);
        audioOutputFactory = file -> output;
    }

    private final ObjectProperty<AnimationFile> animation = new SimpleObjectProperty<>(this, "animation") {
        @Override
        protected void invalidated() {
            animationChanged(get());
        }
    };

    /**
     * The document to play; {@code null} for none. Replacing the document keeps the current time
     * (limited to the new duration) and the status, so a paused player shows the new document at
     * the same time. Slot bindings whose slot name still exists are kept, the others are released.
     *
     * @return the animation property
     */
    public final ObjectProperty<AnimationFile> animationProperty() {
        return animation;
    }

    public final AnimationFile getAnimation() {
        return animation.get();
    }

    public final void setAnimation(AnimationFile value) {
        animation.set(value);
    }

    private final DoubleProperty currentTime = new SimpleDoubleProperty(this, "currentTime") {
        @Override
        protected void invalidated() {
            double time = get();
            double clamped = clamp(time);
            if (clamped != time && !isBound()) {
                set(clamped);
                return;
            }
            if (!ticking) {
                markersAtTimeFired = false;
                cuesAtTimeFired = false;
            }
            refresh();
        }
    };

    /**
     * The playback position in seconds. Setting it behaves like {@link #seek(double)}.
     *
     * @return the current time property
     */
    public final DoubleProperty currentTimeProperty() {
        return currentTime;
    }

    public final double getCurrentTime() {
        return currentTime.get();
    }

    public final void setCurrentTime(double value) {
        currentTime.set(value);
    }

    private final DoubleProperty rate = new SimpleDoubleProperty(this, "rate", 1);

    /**
     * The playback speed: 1 is normal speed, negative values play backwards, 0 holds the time
     * without changing the status.
     *
     * @return the rate property
     */
    public final DoubleProperty rateProperty() {
        return rate;
    }

    public final double getRate() {
        return rate.get();
    }

    public final void setRate(double value) {
        rate.set(value);
    }

    private final IntegerProperty cycleCount = new SimpleIntegerProperty(this, "cycleCount", 1);

    /**
     * How often the active range is traversed before playback finishes, or {@link #INDEFINITE}.
     * Values below 1 count as 1.
     *
     * @return the cycle count property
     */
    public final IntegerProperty cycleCountProperty() {
        return cycleCount;
    }

    public final int getCycleCount() {
        return cycleCount.get();
    }

    public final void setCycleCount(int value) {
        cycleCount.set(value);
    }

    private final BooleanProperty autoReverse = new SimpleBooleanProperty(this, "autoReverse");

    /**
     * Whether every second cycle runs in the opposite direction instead of starting over.
     *
     * @return the auto reverse property
     */
    public final BooleanProperty autoReverseProperty() {
        return autoReverse;
    }

    public final boolean isAutoReverse() {
        return autoReverse.get();
    }

    public final void setAutoReverse(boolean value) {
        autoReverse.set(value);
    }

    private final BooleanProperty muted = new SimpleBooleanProperty(this, "muted") {
        @Override
        protected void invalidated() {
            if (get() && audio != null) {
                audio.stopCues();
            }
            syncVideo();
        }
    };

    /**
     * Whether the sound of the animation is switched off: audio layers are silent and sound
     * cues are skipped. Muting cuts the cues that are sounding at once; for the audio layers a
     * change while playing takes effect with the next frame.
     *
     * @return the muted property
     */
    public final BooleanProperty mutedProperty() {
        return muted;
    }

    public final boolean isMuted() {
        return muted.get();
    }

    public final void setMuted(boolean value) {
        muted.set(value);
    }

    private final DoubleProperty volume = new SimpleDoubleProperty(this, "volume", 1);

    /**
     * The volume of the animation's sound, {@code 0..1}; every audio layer and sound cue is
     * scaled by it. Values outside the range are limited to it when applied. A change while
     * playing takes effect with the next frame; a cue that already sounds keeps its volume.
     *
     * @return the volume property
     */
    public final DoubleProperty volumeProperty() {
        return volume;
    }

    public final double getVolume() {
        return volume.get();
    }

    public final void setVolume(double value) {
        volume.set(value);
    }

    private final ReadOnlyObjectWrapper<Status> status = new ReadOnlyObjectWrapper<>(this, "status", Status.STOPPED);

    /**
     * The playback state.
     *
     * @return the status property
     */
    public final ReadOnlyObjectProperty<Status> statusProperty() {
        return status.getReadOnlyProperty();
    }

    public final Status getStatus() {
        return status.get();
    }

    private final ReadOnlyStringWrapper activeMarker = new ReadOnlyStringWrapper(this, "activeMarker");

    /**
     * The name of the marker whose range is being played by {@link #playMarker(String)};
     * {@code null} when the whole document is the active range.
     *
     * @return the active marker property
     */
    public final ReadOnlyStringProperty activeMarkerProperty() {
        return activeMarker.getReadOnlyProperty();
    }

    public final String getActiveMarker() {
        return activeMarker.get();
    }

    private final ObjectProperty<EventHandler<ActionEvent>> onFinished = new SimpleObjectProperty<>(this, "onFinished");

    /**
     * Called when playback reaches its natural end, that is after the last cycle. The player is
     * {@link Status#STOPPED} by then and stays on the last frame.
     *
     * @return the on finished property
     */
    public final ObjectProperty<EventHandler<ActionEvent>> onFinishedProperty() {
        return onFinished;
    }

    public final EventHandler<ActionEvent> getOnFinished() {
        return onFinished.get();
    }

    public final void setOnFinished(EventHandler<ActionEvent> value) {
        onFinished.set(value);
    }

    private final ObjectProperty<Consumer<Marker>> onMarkerReached = new SimpleObjectProperty<>(this, "onMarkerReached");

    /**
     * Called for every marker whose start playback crosses, in the order of playback. A marker
     * that starts exactly where playback begins counts as crossed. Seeking does not call it.
     *
     * @return the on marker reached property
     */
    public final ObjectProperty<Consumer<Marker>> onMarkerReachedProperty() {
        return onMarkerReached;
    }

    public final Consumer<Marker> getOnMarkerReached() {
        return onMarkerReached.get();
    }

    public final void setOnMarkerReached(Consumer<Marker> value) {
        onMarkerReached.set(value);
    }

    private final ReadOnlyObjectWrapper<Frame> frame = new ReadOnlyObjectWrapper<>(this, "frame");

    /**
     * The document sampled at the current time; {@code null} without an animation. It is up to
     * date whenever the current time or the animation changes.
     *
     * @return the frame property
     */
    public final ReadOnlyObjectProperty<Frame> frameProperty() {
        return frame.getReadOnlyProperty();
    }

    public final Frame getFrame() {
        return frame.get();
    }

    /**
     * Starts or resumes playback. A paused player continues where it is. A stopped player starts
     * a new run from the current time, or from the start of the range when it is at the end (from
     * the end when the rate is negative and it is at the start). Does nothing without an
     * animation; an empty range finishes at once.
     */
    public void play() {
        audioReleased = false;
        resumeVideo();
        Status current = getStatus();
        if (getAnimation() == null || current == Status.PLAYING) {
            return;
        }
        epoch++;
        if (current == Status.STOPPED) {
            completedCycles = 0;
            reversed = false;
            double time = clamp(getCurrentTime());
            if (getRate() < 0) {
                if (time <= rangeStart) {
                    time = rangeEnd;
                }
            } else if (time >= rangeEnd) {
                time = rangeStart;
            }
            jumpTo(time);
            if (!(rangeEnd > rangeStart)) {
                finish();
                return;
            }
        }
        status.set(Status.PLAYING);
        updateClock();
    }

    /**
     * Interrupts playback and keeps the current time. The audio layers fall silent; a sound cue
     * that already sounds plays to its end, as it does when playback finishes.
     */
    public void pause() {
        if (getStatus() == Status.PLAYING) {
            epoch++;
            status.set(Status.PAUSED);
            updateClock();
        }
    }

    /**
     * Ends playback and goes back to the start of the active range: to time 0, or to the start
     * of the marker being played, which then no longer is the active range. All sound stops,
     * sound cues that are sounding included.
     */
    public void stop() {
        epoch++;
        completedCycles = 0;
        reversed = false;
        jumpTo(rangeStart);
        activeMarker.set(null);
        updateRange();
        status.set(Status.STOPPED);
        updateClock();
        if (audio != null) {
            audio.stopCues();
        }
    }

    /**
     * Moves to a time in seconds, limited to the active range. The status does not change.
     * Markers and sound cues that are jumped over do not fire. A cue exactly at the new time
     * does not fire either; cues crossed by subsequent forward playback still do.
     */
    public void seek(double seconds) {
        epoch++;
        jumpTo(clamp(seconds));
        cuesAtTimeFired = true;
    }

    /**
     * Moves to a time like {@link #seek(double)} and, unless the player is playing or
     * {@link #mutedProperty() muted}, lets the audio layers at that time sound for about a tenth
     * of a second. Sound cues do not fire while not playing; while playing this is
     * {@link #seek(double)}. For tooling: dragging a playhead.
     */
    public void scrub(double seconds) {
        seek(seconds);
        if (getStatus() != Status.PLAYING && !isMuted()) {
            AudioEngine engine = audio();
            if (engine != null) {
                engine.scrub(getFrame(), masterVolume());
            }
        }
    }

    /**
     * Stops all sound, sound cues included, and releases the media resources and temporary
     * files of the current animation. From then on the player is silent and creates no media,
     * even if it goes on playing, until {@link #play()} or {@link #playMarker(String)} is called
     * or the animation is replaced. Replacing the animation releases the sound of the previous
     * one by itself. Call this when a player that played sound is no longer needed.
     */
    public void dispose() {
        releaseMedia();
        audioReleased = true;
    }

    /** Releases the sound of the current animation; it comes back when it is next needed. */
    void releaseAudio() {
        if (audio != null) {
            audio.dispose();
            audio = null;
        }
    }

    void releaseMedia() {
        releaseAudio();
        video.release();
    }

    VideoEngine video() {
        return video;
    }

    void resumeVideo() {
        if (!suspended || hasInPlaceBindings()) video.resume();
    }

    /** For tooling: replaces video decoding; null restores JavaFX media. */
    public void setVideoOutputFactory(java.util.function.Supplier<VideoOutput> factory) {
        video.setOutputFactory(factory != null ? factory
                : () -> new MediaVideoOutput(logger, (id, error) -> {}, false));
        if (suspended && !hasInPlaceBindings()) video.release();
    }

    /**
     * For tooling and tests, not for applications: replaces what makes the sound of this player
     * audible. The factory is asked once per animation, when sound is first needed, and never for
     * an animation without audio layers and sound cues; it may return {@code null} for silence.
     * {@code null} as the factory restores JavaFX media. The output of the current animation is
     * disposed; the new factory is asked when sound is next needed.
     */
    public void setAudioOutputFactory(Function<AnimationFile, AudioOutput> factory) {
        releaseAudio();
        audioOutputFactory = factory;
    }

    /** The sound of the current animation, or {@code null} if it has none. */
    private AudioEngine audio() {
        if (audio == null && hasSound && !audioReleased) {
            Function<AnimationFile, AudioOutput> factory = audioOutputFactory != null ? audioOutputFactory
                    : document -> new MediaAudioOutput(document, logger);
            audio = new AudioEngine(getAnimation(), factory, logger);
        }
        return audio;
    }

    private double masterVolume() {
        double value = getVolume();
        return Double.isNaN(value) ? 1 : Math.max(0, Math.min(1, value));
    }

    /**
     * Plays the range of a marker from its start (from its end when the rate is negative),
     * respecting cycle count and auto reverse.
     *
     * @throws IllegalArgumentException if the animation has no marker of that name
     */
    public void playMarker(String name) {
        AnimationFile file = getAnimation();
        if (file == null || file.animation().marker(name).isEmpty()) {
            throw new IllegalArgumentException("Unknown marker: " + name);
        }
        int playEpoch = ++epoch;
        audioReleased = false;
        resumeVideo();
        activeMarker.set(name);
        if (epoch != playEpoch) return;
        updateRange();
        completedCycles = 0;
        reversed = false;
        jumpTo(getRate() < 0 ? rangeEnd : rangeStart);
        if (epoch != playEpoch) return;
        if (!(rangeEnd > rangeStart)) {
            finish();
            return;
        }
        status.set(Status.PLAYING);
        updateClock();
    }

    /**
     * Binds a node to a slot in place: the node stays where it is in the scene graph and the
     * player drives its {@code translateX/Y}, {@code scaleX/Y}, {@code rotate}, {@code opacity},
     * {@code visible} and {@code effect} relative to the slot's rest transform. An earlier
     * binding of the slot is released first. A player with in-place bindings keeps running
     * while it plays, even when no view is showing, so {@link #stop()} it or
     * {@link #unbind(String) unbind} the slots when the animation is no longer needed.
     *
     * @throws IllegalStateException    if no animation is set
     * @throws IllegalArgumentException if the animation has no slot of that name
     */
    public void bindInPlace(String slotName, Node node) {
        bind(slotName, node, false);
    }

    void bindEmbedded(String slotName, Node node) {
        bind(slotName, node, true);
    }

    private void bind(String slotName, Node node, boolean embedded) {
        Objects.requireNonNull(node, "node");
        if (getAnimation() == null) {
            throw new IllegalStateException("No animation set");
        }
        SlotLayer slot = slots.get(slotName);
        if (slot == null) {
            throw new IllegalArgumentException("Unknown slot: " + slotName);
        }
        SlotBinding previous = bindings.remove(slotName);
        if (previous != null) {
            previous.release();
        }
        SlotBinding binding = embedded ? SlotBinding.embedded(node) : SlotBinding.inPlace(node);
        bindings.put(slotName, binding);
        apply(binding, slot, getFrame());
        bindingsChanged();
    }

    /**
     * Releases the binding of a slot: an in-place node gets back the values it had when it was
     * bound, an embedded node leaves the view. Does nothing if the slot is not bound.
     */
    public void unbind(String slotName) {
        SlotBinding binding = bindings.remove(slotName);
        if (binding != null) {
            binding.release();
            bindingsChanged();
        }
    }

    /**
     * The names of the slots that are bound, embedded or in place.
     */
    public Set<String> getBoundSlots() {
        return Set.copyOf(bindings.keySet());
    }

    /** The slot names of the animation. */
    Set<String> slotNames() {
        return slots.keySet();
    }

    void forEachEmbedded(BiConsumer<SlotLayer, SlotBinding> action) {
        bindings.forEach((name, binding) -> {
            if (binding.isEmbedded()) {
                action.accept(slots.get(name), binding);
            }
        });
    }

    /** The listener is called after a slot was bound or unbound. */
    void setBindingsListener(Runnable listener) {
        bindingsListener = listener;
    }

    void removeBindingsListener(Runnable listener) {
        if (bindingsListener == listener) {
            bindingsListener = null;
        }
    }

    /**
     * A suspended player keeps its status but its clock stands still, unless it drives nodes in
     * place. The view suspends its player while it is not showing.
     */
    void setSuspended(boolean suspended) {
        this.suspended = suspended;
        if (suspended && !hasInPlaceBindings()) video.release();
        else video.resume();
        updateClock();
    }

    private void bindingsChanged() {
        updateClock();
        if (bindingsListener != null) {
            bindingsListener.run();
        }
    }

    private void animationChanged(AnimationFile file) {
        epoch++;
        releaseAudio();
        audioReleased = false;
        video.setDocument(file);
        resumeVideo();
        hasSound = file != null && AudioEngine.hasAudio(file.animation());
        slots.clear();
        if (file != null) {
            collectSlots(file.animation().layers());
        }
        warnedSlots.retainAll(slots.keySet());
        boolean released = false;
        for (Iterator<Map.Entry<String, SlotBinding>> it = bindings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, SlotBinding> entry = it.next();
            if (!slots.containsKey(entry.getKey())) {
                entry.getValue().release();
                it.remove();
                released = true;
            }
        }
        String marker = getActiveMarker();
        if (marker != null && (file == null || file.animation().marker(marker).isEmpty())) {
            activeMarker.set(null);
        }
        updateRange();
        if (file == null) {
            stop();
        } else if (!currentTime.isBound()) {
            currentTime.set(clamp(getCurrentTime()));
        }
        refresh();
        if (released) {
            bindingsChanged();
        }
    }

    private void collectSlots(List<Layer> layers) {
        for (Layer layer : layers) {
            if (layer instanceof SlotLayer slot) {
                slots.putIfAbsent(slot.slotName(), slot);
            } else if (layer instanceof GroupLayer group) {
                collectSlots(group.children());
            }
        }
    }

    private void updateRange() {
        AnimationFile file = getAnimation();
        double duration = file == null ? 0 : Math.max(0, file.animation().duration());
        Marker marker = file == null || getActiveMarker() == null ? null
                : file.animation().marker(getActiveMarker()).orElse(null);
        if (marker == null) {
            rangeStart = 0;
            rangeEnd = duration;
        } else {
            rangeStart = Math.max(0, Math.min(duration, Math.min(marker.start(), marker.end())));
            rangeEnd = Math.max(0, Math.min(duration, Math.max(marker.start(), marker.end())));
        }
    }

    private double clamp(double time) {
        // also maps NaN to the start
        return time >= rangeEnd ? rangeEnd : time > rangeStart ? time : rangeStart;
    }

    /** Sets the time other than by playing through to it. */
    private void jumpTo(double time) {
        if (!currentTime.isBound()) {
            currentTime.set(time);
        }
        markersAtTimeFired = false;
        cuesAtTimeFired = false;
    }

    private void refresh() {
        AnimationFile file = getAnimation();
        Frame sampled = file == null ? null : Evaluator.sample(file.animation(), getCurrentTime());
        frame.set(sampled);
        syncVideo();
        bindings.forEach((name, binding) -> apply(binding, slots.get(name), sampled));
    }

    private void syncVideo() {
        if (video != null) {
            video.sync(getFrame(), getStatus() == Status.PLAYING && (!suspended || hasInPlaceBindings())
                    && !currentTime.isBound(), getRate(), reversed, isMuted(), masterVolume());
        }
    }

    private static void apply(SlotBinding binding, SlotLayer slot, Frame frame) {
        LayerState state = frame == null ? null : frame.get(slot.id());
        if (state != null) {
            binding.apply(slot, state);
        }
    }

    private void updateClock() {
        boolean run = getStatus() == Status.PLAYING && (!suspended || hasInPlaceBindings());
        if (run != clockRunning) {
            clockRunning = run;
            if (run) {
                clock.start(this::tick);
            } else {
                clock.stop();
            }
        }
        if (!run && audio != null) {
            audio.silence();
        }
        syncVideo();
    }

    boolean hasInPlaceBindings() {
        for (SlotBinding binding : bindings.values()) {
            if (!binding.isEmbedded()) {
                return true;
            }
        }
        return false;
    }

    private void tick(double elapsed) {
        if (getStatus() != Status.PLAYING || !(elapsed > 0)) {
            return;
        }
        warnUnboundSlots();
        if (currentTime.isBound()) {
            if (audio != null) {
                audio.silence();
            }
            return;
        }
        if (!(rangeEnd > rangeStart)) {
            finish();
            return;
        }

        double speed = getRate();
        double time = clamp(getCurrentTime());
        double remaining = elapsed;
        boolean finished = false;
        List<Marker> reached = new ArrayList<>();
        AudioEngine sound = audio();
        List<SoundCue> cues = new ArrayList<>();
        for (int i = 0; i < MAX_CYCLES_PER_TICK && remaining > 0 && speed != 0 && !Double.isNaN(speed); i++) {
            double velocity = reversed ? -speed : speed;
            boolean forward = velocity > 0;
            double target = time + velocity * remaining;
            double boundary = forward ? rangeEnd : rangeStart;
            if (forward ? target < boundary : target > boundary) {
                collectCues(sound, time, target, speed, cues);
                collectMarkers(time, target, forward, reached);
                time = target;
                break;
            }
            collectCues(sound, time, boundary, speed, cues);
            collectMarkers(time, boundary, forward, reached);
            remaining -= (boundary - time) / velocity;
            time = boundary;
            completedCycles++;
            int cycles = getCycleCount();
            if (cycles != INDEFINITE && completedCycles >= Math.max(1, cycles)) {
                finished = true;
                break;
            }
            if (isAutoReverse()) {
                reversed = !reversed;
                if (!reversed) {
                    // forward again: the cues at the turning point are due once more
                    cuesAtTimeFired = false;
                }
            } else {
                time = forward ? rangeStart : rangeEnd;
                markersAtTimeFired = false;
                cuesAtTimeFired = false;
            }
        }

        // handlers and listeners may call back into the player; a control call ends this tick
        int current = epoch;
        ticking = true;
        try {
            currentTime.set(time);
        } finally {
            ticking = false;
        }
        if (sound != null && sound == audio && epoch == current) {
            if (!finished) {
                sound.playing(getFrame(), speed, reversed, isMuted(), masterVolume());
            }
            sound.cues(cues, isMuted(), masterVolume());
        }
        for (Marker marker : reached) {
            if (epoch != current) {
                return;
            }
            Consumer<Marker> handler = getOnMarkerReached();
            if (handler != null) {
                handler.accept(marker);
            }
        }
        if (finished && epoch == current) {
            finish();
        }
    }

    /**
     * Adds the markers whose start lies on the way from one time to another, the end included
     * and the beginning only if its markers have not fired yet.
     */
    private void collectMarkers(double from, double to, boolean forward, List<Marker> out) {
        int first = out.size();
        for (Marker marker : getAnimation().animation().markers()) {
            double start = marker.start();
            boolean passed = forward ? start > from && start <= to : start < from && start >= to;
            if (passed || (start == from && !markersAtTimeFired)) {
                out.add(marker);
            }
        }
        Comparator<Marker> order = Comparator.comparingDouble(Marker::start);
        out.subList(first, out.size()).sort(forward ? order : order.reversed());
        markersAtTimeFired = true;
    }

    /**
     * Adds the sound cues on the way from one time to another, the end included and the
     * beginning only if its cues have not fired yet. Only playback that runs forward at a
     * positive rate fires cues. Every crossing is retained, including multiple crossings in
     * one delayed tick.
     */
    private void collectCues(AudioEngine sound, double from, double to, double speed, List<SoundCue> out) {
        if (speed > 0 && !reversed) {
            if (sound != null) {
                out.addAll(AudioSchedule.crossed(getAnimation().animation(), from, to, !cuesAtTimeFired));
            }
            cuesAtTimeFired = true;
        }
    }

    private void finish() {
        epoch++;
        int finishedEpoch = epoch;
        completedCycles = 0;
        reversed = false;
        activeMarker.set(null);
        if (epoch != finishedEpoch) return;
        updateRange();
        status.set(Status.STOPPED);
        updateClock();
        EventHandler<ActionEvent> handler = getOnFinished();
        if (handler != null) {
            handler.handle(new ActionEvent(this, null));
        }
        if (epoch == finishedEpoch && stateMachineCompletion != null) {
            stateMachineCompletion.run();
        }
    }

    void attachStateMachine(Runnable completion) {
        if (stateMachineCompletion != null) {
            throw new IllegalStateException("A state machine is already attached");
        }
        stateMachineCompletion = completion;
    }

    void detachStateMachine(Runnable completion) {
        if (stateMachineCompletion == completion) {
            stateMachineCompletion = null;
        }
    }

    private void warnUnboundSlots() {
        if (warnedSlots.size() + bindings.size() >= slots.size()) {
            return;
        }
        for (String name : slots.keySet()) {
            if (!bindings.containsKey(name) && warnedSlots.add(name)) {
                logger.log(System.Logger.Level.WARNING, "Slot ''{0}'' is not bound to a node and is skipped", name);
            }
        }
    }
}
