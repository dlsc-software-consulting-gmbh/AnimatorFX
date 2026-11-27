package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineControllerTest {

    private final ManualClock clock = new ManualClock();
    private final AnimationPlayer player = new AnimationPlayer(clock);
    private final StateMachine machine = new StateMachine("button", "idle",
            List.of(new StateMachine.State("idle", "idle", true, 0, 0),
                    new StateMachine.State("loading", "loading", false, 1, 0),
                    new StateMachine.State("success", "success", false, 2, 0)),
            List.of(new StateMachine.Transition("idle", "loading", "click"),
                    new StateMachine.Transition("loading", "success", null),
                    new StateMachine.Transition("loading", "idle", "cancel")));

    StateMachineControllerTest() {
        Animation animation = Animation.create("button", 100, 100, 3).withMarkers(List.of(
                new Marker("idle", 0, 1), new Marker("loading", 1, 2), new Marker("success", 2, 3)));
        player.setAnimation(new AnimationFile(animation, Map.of()));
    }

    @Test
    void eventsInterruptImmediatelyAndUnknownEventsAreIgnored() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        clock.tick(0.5);
        assertEquals("idle", controller.getState());
        assertFalse(controller.send("unknown"));
        assertTrue(controller.send("click"));
        assertEquals("loading", controller.getState());
        assertEquals("loading", player.getActiveMarker());
        assertEquals(1, player.getCurrentTime());
        assertTrue(controller.send("cancel"));
        assertEquals("idle", controller.getState());
        assertEquals(0, player.getCurrentTime());
    }

    @Test
    void completionTransitionsCoexistWithApplicationHandler() {
        int[] finished = {0};
        player.setOnFinished(event -> finished[0]++);
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        clock.tick(2);
        assertEquals(0, finished[0]);
        controller.send("click");
        clock.tick(1);
        assertEquals(1, finished[0]);
        assertEquals("success", controller.getState());
        assertEquals("success", player.getActiveMarker());
        clock.tick(1);
        assertEquals(2, finished[0]);
        assertEquals("success", controller.getState());
    }

    @Test
    void stoppingDetachesAndPreventsEvents() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        controller.send("click");
        controller.stop();
        assertFalse(controller.send("cancel"));
        player.playMarker("loading");
        clock.tick(1);
        assertEquals("loading", controller.getState());
    }

    @Test
    void missingMarkerIsRejectedBeforePlaybackStarts() {
        player.setAnimation(new AnimationFile(Animation.create("empty", 100, 100, 3), Map.of()));
        StateMachineController controller = new StateMachineController(player, machine);
        assertThrows(IllegalArgumentException.class, controller::start);
        assertEquals(AnimationPlayer.Status.STOPPED, player.getStatus());
    }

    @Test
    void stateListenerCanSendAnEventDuringEntry() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.stateProperty().addListener((observable, oldState, newState) -> {
            if ("loading".equals(newState)) {
                controller.send("cancel");
            }
        });
        controller.start();
        controller.send("click");
        assertEquals("idle", controller.getState());
        assertEquals("idle", player.getActiveMarker());
    }

    @Test
    void replacingAnimationDetachesController() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        player.setAnimation(new AnimationFile(Animation.create("other", 100, 100, 3), Map.of()));
        assertFalse(controller.send("click"));
    }

    @Test
    void applicationControlInFinishedHandlerTakesPrecedence() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        controller.send("click");
        player.setOnFinished(event -> player.playMarker("idle"));
        clock.tick(1);
        assertEquals("loading", controller.getState());
        assertEquals("idle", player.getActiveMarker());
    }

    @Test
    void applicationControlInStatusListenerTakesPrecedence() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        controller.send("click");
        player.statusProperty().addListener((observable, oldStatus, status) -> {
            if (status == AnimationPlayer.Status.STOPPED) {
                player.playMarker("idle");
            }
        });
        clock.tick(1);
        assertEquals("loading", controller.getState());
        assertEquals("idle", player.getActiveMarker());
    }

    @Test
    void eventFromMarkerCompletionListenerKeepsTheNewStatePlaying() {
        StateMachineController controller = new StateMachineController(player, machine);
        controller.start();
        controller.send("click");
        player.activeMarkerProperty().addListener((observable, oldMarker, newMarker) -> {
            if ("loading".equals(oldMarker) && newMarker == null) {
                controller.send("cancel");
            }
        });
        clock.tick(1);
        assertEquals("idle", controller.getState());
        assertEquals("idle", player.getActiveMarker());
        assertEquals(AnimationPlayer.Status.PLAYING, player.getStatus());
        clock.tick(0.5);
        assertEquals(0.5, player.getCurrentTime());
    }

    @Test
    void stoppingFromMarkerListenerDoesNotRestartPlayback() {
        StateMachineController controller = new StateMachineController(player, machine);
        player.activeMarkerProperty().addListener((observable, oldMarker, newMarker) -> {
            if ("idle".equals(newMarker)) {
                controller.stop();
            }
        });
        controller.start();
        assertFalse(controller.send("click"));
        assertEquals(AnimationPlayer.Status.STOPPED, player.getStatus());
        assertNull(player.getActiveMarker());
    }

    @Test
    void failedStartWithBoundPlaybackPropertiesDoesNotLeaveControllerAttached() {
        StateMachineController controller = new StateMachineController(player, machine);
        player.cycleCountProperty().bind(new SimpleIntegerProperty(1));
        assertThrows(RuntimeException.class, controller::start);
        player.cycleCountProperty().unbind();
        player.autoReverseProperty().bind(new SimpleBooleanProperty(false));
        assertThrows(RuntimeException.class, controller::start);
        player.autoReverseProperty().unbind();

        StateMachineController replacement = new StateMachineController(player, machine);
        replacement.start();
        assertEquals("idle", replacement.getState());
    }
}
