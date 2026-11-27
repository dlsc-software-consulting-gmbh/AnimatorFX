package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.AnimationView;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample window under the headless toolkit.
 */
class SampleAppTest {

    private Stage stage;
    private SampleBrowser browser;

    /**
     * The application starts with its sound off, however this test is launched. The build sets
     * the property as well, but a run from an IDE does not go through the build.
     */
    @BeforeAll
    static void startSilent() {
        System.setProperty(SampleApp.MUTED_PROPERTY, "true");
    }

    @BeforeEach
    void show() throws Exception {
        Fx.run(() -> {
            stage = new Stage();
            new SampleApp().start(stage);
            browser = (SampleBrowser) stage.getScene().getRoot();
        });
    }

    @AfterEach
    void close() throws Exception {
        Fx.run(() -> {
            browser.getInPlaceDemo().getPlayer().stop();
            stage.close();
        });
    }

    @Test
    void theWindowShowsTheFirstSample() throws Exception {
        Fx.run(() -> {
            assertTrue(stage.isShowing());
            assertEquals(Samples.SHOWCASE, browser.getList().getItems());
            assertEquals("loader", browser.getList().getSelectionModel().getSelectedItem());
            assertSame(browser.getShowcase("loader"), browser.getCenter());
        });
    }

    @Test
    void stateMachineDemoShowsEventsInterruptionsAndAutomaticTransitions() throws Exception {
        Stage dialog = Fx.call(() -> {
            lookup(browser, "#open-state-machine-demo", Button.class).fire();
            return Window.getWindows().stream().filter(window -> window instanceof Stage
                    && window != stage && ((Stage) window).getOwner() == stage)
                    .map(Stage.class::cast).findFirst().orElseThrow();
        });
        AnimationView view = Fx.call(() -> lookup(dialog.getScene().getRoot(),
                "#state-machine-view", AnimationView.class));
        try {
            Fx.run(() -> {
                Parent root = dialog.getScene().getRoot();
                assertEquals(Samples.load("download-workflow").animation(), view.getAnimation().animation());
                assertEquals("download", view.getAnimation().animation().stateMachines().getFirst().name());
                Label state = lookup(root, "#machine-state", Label.class);
                assertEquals("State: ready", state.getText());
                assertTrue(lookup(root, "#machine-pause", Button.class).isDisabled());
                assertFalse(lookup(root, "#machine-start", Button.class).isDisabled());
                assertEquals(AnimationPlayer.INDEFINITE, view.getCycleCount());
                lookup(root, "#machine-start", Button.class).fire();
                assertEquals("State: connecting", state.getText());
                assertEquals("connecting", view.getPlayer().getActiveMarker());
                assertEquals("Last transition: ready --start--> connecting",
                        lookup(root, "#machine-transition", Label.class).getText());
                lookup(root, "#machine-fail", Button.class).fire();
                assertEquals("State: failed", state.getText());
                lookup(root, "#machine-retry", Button.class).fire();
                assertEquals("State: connecting", state.getText());
                view.setRate(8);
            });
            await(() -> "State: downloading".equals(lookup(dialog.getScene().getRoot(),
                    "#machine-state", Label.class).getText()));
            Fx.run(() -> {
                Parent root = dialog.getScene().getRoot();
                assertEquals("Last transition: connecting --finished--> downloading",
                        lookup(root, "#machine-transition", Label.class).getText());
                assertFalse(lookup(root, "#machine-pause", Button.class).isDisabled());
                lookup(root, "#machine-pause", Button.class).fire();
                assertEquals("State: paused", lookup(root, "#machine-state", Label.class).getText());
                assertEquals("paused", view.getPlayer().getActiveMarker());
                lookup(root, "#machine-resume", Button.class).fire();
                assertEquals("State: downloading", lookup(root, "#machine-state", Label.class).getText());
                assertEquals("Last transition: paused --resume--> downloading",
                        lookup(root, "#machine-transition", Label.class).getText());
                view.setRate(16);
            });
            await(() -> "State: complete".equals(lookup(dialog.getScene().getRoot(),
                    "#machine-state", Label.class).getText()));
            Fx.run(() -> {
                assertEquals("complete", view.getPlayer().getActiveMarker());
                assertEquals(AnimationPlayer.INDEFINITE, view.getCycleCount());
                assertEquals(AnimationPlayer.Status.PLAYING, view.getStatus());
                assertEquals("Last transition: downloading --finished--> complete",
                        lookup(dialog.getScene().getRoot(), "#machine-transition", Label.class).getText());
                lookup(dialog.getScene().getRoot(), "#machine-reset", Button.class).fire();
                assertEquals("State: ready", lookup(dialog.getScene().getRoot(),
                        "#machine-state", Label.class).getText());
            });
        } finally {
            Fx.run(dialog::close);
        }
        Fx.run(() -> {
            assertEquals(AnimationPlayer.Status.STOPPED, view.getStatus());
            lookup(dialog.getScene().getRoot(), "#machine-start", Button.class).fire();
            assertEquals("State: ready", lookup(dialog.getScene().getRoot(),
                    "#machine-state", Label.class).getText());
        });
    }

    @Test
    void stateMachineDemoHoldsTheReadyStateUntilAnEvent() throws Exception {
        Stage dialog = Fx.call(() -> {
            lookup(browser, "#open-state-machine-demo", Button.class).fire();
            return Window.getWindows().stream().filter(window -> window instanceof Stage
                    && window != stage && ((Stage) window).getOwner() == stage)
                    .map(Stage.class::cast).findFirst().orElseThrow();
        });
        try {
            AnimationView view = Fx.call(() -> lookup(dialog.getScene().getRoot(),
                    "#state-machine-view", AnimationView.class));
            int[] wraps = {0};
            double[] highest = {Double.NEGATIVE_INFINITY};
            Fx.run(() -> {
                view.currentTimeProperty().addListener((observable, old, time) -> {
                    highest[0] = Math.max(highest[0], time.doubleValue());
                    if (time.doubleValue() < old.doubleValue()) wraps[0]++;
                });
                view.setRate(8);
            });
            await(() -> wraps[0] >= 3);
            Fx.run(() -> {
                assertEquals("State: ready", lookup(dialog.getScene().getRoot(), "#machine-state", Label.class).getText());
                assertEquals("ready", view.getPlayer().getActiveMarker());
                assertTrue(highest[0] < 1.2, "ready playback escaped its marker");
            });
        } finally {
            Fx.run(dialog::close);
        }
    }

    @Test
    void downloadWorkflowRendersDistinctImagesForEachState() throws Exception {
        var animation = Samples.load("download-workflow");
        AnimationView view = Fx.call(() -> new AnimationView(animation));
        Stage preview = Fx.call(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(view), 600, 360));
            window.show();
            return window;
        });
        Path screenshots = Fx.screenshots();
        if (screenshots != null) Files.createDirectories(screenshots);
        Set<Integer> images = new HashSet<>();
        try {
            for (var marker : animation.animation().markers()) {
                WritableImage image = Fx.call(() -> {
                    view.seek((marker.start() + marker.end()) / 2);
                    preview.getScene().getRoot().applyCss();
                    preview.getScene().getRoot().layout();
                    return Fx.snapshot(preview.getScene().getRoot(), Color.TRANSPARENT);
                });
                int hash = 1;
                for (int y = 0; y < (int) image.getHeight(); y += 3) {
                    for (int x = 0; x < (int) image.getWidth(); x += 3) {
                        hash = 31 * hash + image.getPixelReader().getArgb(x, y);
                    }
                }
                assertTrue(images.add(hash), "duplicate image for " + marker.name());
                if (screenshots != null) Fx.writePng(image, screenshots.resolve("download-" + marker.name() + ".png"));
            }
        } finally {
            Fx.run(() -> {
                view.dispose();
                preview.close();
            });
        }
    }

    @Test
    void liveElementsDemoRespondsToClicksAndRestoresAuthoredValues() throws Exception {
        Fx.run(() -> {
            Button open = lookup(browser, "#open-live-elements-demo", Button.class);
            assertTrue(open.localToScene(open.getBoundsInLocal()).getMaxX() <= browser.getWidth());
            CheckBox dark = lookup(browser, "#dark", CheckBox.class);
            assertTrue(dark.localToScene(dark.getBoundsInLocal()).getMaxX() <= browser.getWidth());
            open.fire();
            Stage dialog = Window.getWindows().stream().filter(window -> window instanceof Stage
                    && window != stage && ((Stage) window).getOwner() == stage)
                    .map(Stage.class::cast).findFirst().orElseThrow();
            try {
                Parent root = dialog.getScene().getRoot();
                root.applyCss();
                root.layout();
                AnimationView view = lookup(root, "#live-elements-view", AnimationView.class);
                var button = view.getElement("button");
                var label = view.getElement("label");
                var sparkle = view.getElement("sparkle");
                assertNotNull(button.getNode());
                button.getNode().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        MouseButton.PRIMARY, 1, false, false, false, false,
                        true, false, false, false, false, false, null));
                assertEquals("Clicked 1 time", label.getText());
                assertEquals("Clicked 1 time", ((Text) label.getNode()).getText());
                assertEquals(Color.CORAL, ((Shape) ((Group) button.getNode()).getChildren().get(0)).getFill());

                lookup(root, "#show-sparkle", CheckBox.class).fire();
                assertEquals(Boolean.FALSE, sparkle.getVisible());
                assertFalse(sparkle.getNode().isVisible());
                lookup(root, "#reset-elements", Button.class).fire();
                assertNull(label.getText());
                assertNull(button.getFill());
                assertNull(sparkle.getVisible());
                assertEquals("Click the circle", ((Text) label.getNode()).getText());
                assertEquals(Color.web("#6366F1"), ((Shape) ((Group) button.getNode()).getChildren().get(0)).getFill());
                assertTrue(sparkle.getNode().isVisible());
                assertTrue(lookup(root, "#show-sparkle", CheckBox.class).isSelected());
                button.getNode().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                        MouseButton.PRIMARY, 1, false, false, false, false,
                        true, false, false, false, false, false, null));
                assertEquals("Clicked 1 time", ((Text) label.getNode()).getText());
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    void loginDemoSupportsSimulatedRecoveryAndPasswordChange() throws Exception {
        Fx.run(() -> {
            lookup(browser, "#open-login-demo", Button.class).fire();
            Stage dialog = Window.getWindows().stream().filter(window -> window instanceof Stage
                    && window != stage && ((Stage) window).getOwner() == stage)
                    .map(Stage.class::cast).findFirst().orElseThrow();
            try {
                Parent root = dialog.getScene().getRoot();
                lookup(root, "#login-email", TextField.class).setText("demo@example.com");
                lookup(root, "#login-password", PasswordField.class).setText("wrong");
                lookup(root, "#sign-in", Button.class).fire();
                assertTrue(lookup(root, "#login-message", Label.class).getText().contains("Incorrect"));

                lookup(root, "#forgot-password", Button.class).fire();
                lookup(root, "#recovery-email", TextField.class).setText("other@example.com");
                lookup(root, "#send-recovery", Button.class).fire();
                assertTrue(lookup(root, "#login-message", Label.class).getText().contains("demo account"));
                lookup(root, "#recovery-email", TextField.class).setText("demo@example.com");
                lookup(root, "#send-recovery", Button.class).fire();
                lookup(root, "#new-password", PasswordField.class).setText("new-password");
                lookup(root, "#confirm-password", PasswordField.class).setText("different");
                lookup(root, "#reset-password", Button.class).fire();
                assertTrue(lookup(root, "#login-message", Label.class).getText().contains("match"));
                lookup(root, "#confirm-password", PasswordField.class).setText("new-password");
                lookup(root, "#reset-password", Button.class).fire();
                lookup(root, "#login-email", TextField.class).setText("demo@example.com");
                lookup(root, "#login-password", PasswordField.class).setText("animatorfx");
                lookup(root, "#sign-in", Button.class).fire();
                assertTrue(lookup(root, "#login-message", Label.class).getText().contains("Incorrect"));
                lookup(root, "#login-password", PasswordField.class).setText("new-password");
                lookup(root, "#sign-in", Button.class).fire();
                assertTrue(lookup(root, "#login-message", Label.class).getText().contains("Signed in"));
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    void loginDemoTransitionsFromIntroToLoopingAmbient() throws Exception {
        Stage dialog = Fx.call(() -> {
            lookup(browser, "#open-login-demo", Button.class).fire();
            return Window.getWindows().stream().filter(window -> window instanceof Stage
                    && window != stage && ((Stage) window).getOwner() == stage)
                    .map(Stage.class::cast).findFirst().orElseThrow();
        });
        try {
            AnimationView view = Fx.call(() -> (AnimationView) ((StackPane) dialog.getScene().getRoot()).getChildren().get(0));
            Fx.run(() -> view.setRate(16));
            await(() -> "ambient".equals(view.getPlayer().getActiveMarker()));
            Fx.run(() -> {
                assertEquals(AnimationPlayer.INDEFINITE, view.getCycleCount());
                assertEquals(AnimationPlayer.Status.PLAYING, view.getStatus());
            });
            Path directory = Fx.screenshots();
            if (directory != null) {
                WritableImage image = Fx.call(() -> {
                    view.pause();
                    view.seek(5);
                    dialog.getScene().getRoot().applyCss();
                    dialog.getScene().getRoot().layout();
                    return Fx.snapshot(dialog.getScene().getRoot(), Color.TRANSPARENT);
                });
                Fx.writePng(image, directory.resolve("login-demo.png"));
            }
        } finally {
            Fx.run(dialog::close);
        }
    }

    @Test
    void everySamplePlaysWhenSelected() throws Exception {
        for (String name : Samples.SHOWCASE) {
            Fx.run(() -> browser.getList().getSelectionModel().select(name));
            AnimationView view = browser.getShowcase(name).getView();
            // auto play starts the view once it is part of the showing window
            await(() -> view.getStatus() == AnimationPlayer.Status.PLAYING && view.getCurrentTime() > 0.05);
            double before = Fx.call(view::getCurrentTime);
            await(() -> view.getCurrentTime() != before);
            Fx.run(() -> {
                assertSame(browser.getShowcase(name), browser.getCenter());
                assertNotNull(view.getScene());
                assertEquals(AnimationPlayer.INDEFINITE, view.getCycleCount());
            });
        }
    }

    @Test
    void theTransportControlsDriveTheView() throws Exception {
        Fx.run(() -> browser.getList().getSelectionModel().select("success"));
        SampleShowcase showcase = browser.getShowcase("success");
        AnimationView view = showcase.getView();
        await(() -> view.getStatus() == AnimationPlayer.Status.PLAYING);
        Fx.run(() -> {
            Button play = lookup(showcase, "#play", Button.class);
            assertEquals("Pause", play.getText());
            play.fire();
            assertEquals(AnimationPlayer.Status.PAUSED, view.getStatus());
            assertEquals("Play", play.getText());

            Slider time = lookup(showcase, "#time", Slider.class);
            assertEquals(2.6, time.getMax());
            time.setValue(1.0);
            assertEquals(1.0, view.getCurrentTime());
            view.seek(2.0);
            assertEquals(2.0, time.getValue());
            assertEquals("2.00 / 2.60 s", lookup(showcase, "#timecode", Label.class).getText());

            lookup(showcase, "#loop", CheckBox.class).setSelected(false);
            assertEquals(1, view.getCycleCount());

            @SuppressWarnings("unchecked")
            ChoiceBox<Double> rate = lookup(showcase, "#rate", ChoiceBox.class);
            rate.setValue(2.0);
            assertEquals(2.0, view.getRate());

            // one button per marker of the document
            lookup(showcase, "#marker-success", Button.class).fire();
            assertEquals(AnimationPlayer.Status.PLAYING, view.getStatus());
            assertEquals("success", view.getPlayer().getActiveMarker());
            assertEquals(1.3, view.getCurrentTime());
            lookup(showcase, "#marker-intro", Button.class).fire();
            assertEquals("intro", view.getPlayer().getActiveMarker());
            assertEquals(0, view.getCurrentTime());
        });
        // not looping: the marker range ends and the view stops at its end
        await(() -> view.getStatus() == AnimationPlayer.Status.STOPPED);
        Fx.run(() -> assertEquals(1.3, view.getCurrentTime(), 1e-9));
    }

    @Test
    void theCardEmbedsARealLabelAndARealButton() throws Exception {
        Fx.run(() -> browser.getList().getSelectionModel().select("card"));
        SampleShowcase showcase = browser.getShowcase("card");
        AnimationView view = showcase.getView();
        await(() -> view.getCurrentTime() > 0.05);
        Fx.run(() -> {
            view.pause();
            view.seek(2.6);
            showcase.applyCss();
            showcase.layout();
            Label title = lookup(view, "#card-title", Label.class);
            Button action = lookup(view, "#card-action", Button.class);
            assertEquals("Hello, AnimatorFX", title.getText());
            assertSame(view.getLayerNode("card-title"), title.getParent());
            assertSame(view.getLayerNode("card-action"), action.getParent());
            // resized to the slot's reference size
            assertEquals(104, action.getWidth());
            assertEquals(32, action.getHeight());
            assertEquals(1, action.getParent().getOpacity());

            view.seek(0.6);
            double early = action.getParent().getOpacity();
            assertTrue(early > 0 && early < 1, "the slot fades in, opacity " + early);

            // the embedded button is live
            view.seek(2.0);
            action.fire();
            assertEquals(AnimationPlayer.Status.PLAYING, view.getStatus());
            assertEquals(0, view.getCurrentTime());
        });
    }

    @Test
    void theInPlaceButtonIsAnimatedAndGivenBack() throws Exception {
        InPlaceDemo demo = browser.getInPlaceDemo();
        Button button = demo.getButton();
        AnimationPlayer player = demo.getPlayer();
        Parent parent = Fx.call(button::getParent);
        Fx.run(() -> {
            assertSame(demo, parent);
            button.setRotate(3);
            button.fire();
            assertEquals(AnimationPlayer.Status.PLAYING, player.getStatus());
            assertEquals(Set.of("target"), player.getBoundSlots());
            player.pause();
            player.seek(0.1);
            assertEquals(-7, button.getRotate(), 1e-9);
            assertTrue(button.getScaleX() > 1.01);
            // still in the application's own layout
            assertSame(parent, button.getParent());
            player.play();
        });
        await(() -> player.getStatus() == AnimationPlayer.Status.STOPPED);
        Fx.run(() -> {
            assertTrue(player.getBoundSlots().isEmpty());
            assertEquals(3, button.getRotate());
            assertEquals(1, button.getScaleX());
            assertEquals(0, button.getTranslateX());
            assertNull(button.getEffect());
            assertFalse(button.isDisabled());
        });
    }

    @Test
    void theApplicationStartsSilentWhenAskedTo() throws Exception {
        assertTrue(Boolean.getBoolean(SampleApp.MUTED_PROPERTY));
        Fx.run(() -> {
            assertTrue(browser.isMuted());
            assertTrue(browser.getShowcase("loader").getView().isMuted());
            assertTrue(browser.getInPlaceDemo().getPlayer().isMuted());
        });
    }

    @Test
    void muteAndVolumeAreSharedByEverySampleAndTheInPlacePlayer() throws Exception {
        // on the loader, which has no sound: nothing can be heard while the controls are tried
        SampleShowcase loader = browser.getShowcase("loader");
        AnimationPlayer inPlace = browser.getInPlaceDemo().getPlayer();
        Fx.run(() -> {
            CheckBox mute = lookup(loader, "#mute", CheckBox.class);
            Slider volume = lookup(loader, "#volume", Slider.class);
            assertTrue(mute.isSelected());
            assertTrue(volume.isDisabled());
            assertEquals(1, volume.getValue());
            assertEquals(0, volume.getMin());
            assertEquals(1, volume.getMax());

            // as quiet as it gets before the sound is switched on
            volume.setValue(0);
            assertEquals(0, loader.getView().getVolume());
            assertEquals(0, inPlace.getVolume());
            assertEquals(0, browser.getVolume());
            mute.setSelected(false);
            assertFalse(loader.getView().isMuted());
            assertFalse(inPlace.isMuted());
            assertFalse(volume.isDisabled());

            // a showcase that is created later starts with the same settings
            SampleShowcase morph = browser.getShowcase("morph");
            assertFalse(morph.getView().isMuted());
            assertEquals(0, morph.getView().getVolume());
            assertFalse(lookup(morph, "#mute", CheckBox.class).isSelected());
            lookup(morph, "#mute", CheckBox.class).setSelected(true);
            lookup(morph, "#volume", Slider.class).setValue(0.5);
            assertTrue(mute.isSelected());
            assertEquals(0.5, volume.getValue());
            assertTrue(inPlace.isMuted());
            assertEquals(0.5, inPlace.getVolume());
        });
    }

    @Test
    void closingTheWindowDisposesTheViewsForGood() throws Exception {
        Set<Path> before = soundDirectories();
        // sound on, at volume 0: real media players and extracted files, and still nothing to hear
        Fx.run(() -> {
            browser.setVolume(0);
            browser.setMuted(false);
            browser.getList().getSelectionModel().select("success");
        });
        AnimationView view = browser.getShowcase("success").getView();
        await(() -> view.getStatus() == AnimationPlayer.Status.PLAYING && view.getCurrentTime() > 0.05);
        // only the directories that appeared during this test count: other AnimatorFX processes
        // keep theirs in the same place
        Set<Path> created = new HashSet<>();
        await(() -> {
            created.addAll(soundDirectories());
            created.removeAll(before);
            return !created.isEmpty();
        });
        Fx.run(() -> stage.close());
        // the temporary files of the extracted sounds go with the window
        await(() -> created.stream().noneMatch(Files::exists));
    }

    /** The private directories the runtime extracts sounds into, see the player's Javadoc. */
    private static Set<Path> soundDirectories() {
        File[] found = new File(System.getProperty("java.io.tmpdir"))
                .listFiles((directory, name) -> name.startsWith("animatorfx-audio"));
        Set<Path> directories = new HashSet<>();
        if (found != null) {
            for (File file : found) {
                directories.add(file.toPath());
            }
        }
        return directories;
    }

    @Test
    void theBackdropCanBeSwitched() throws Exception {
        Path directory = Fx.screenshots();
        Fx.run(() -> {
            browser.getList().getSelectionModel().select("card");
            // for the picture; the card has no sound and nothing else plays
            browser.setMuted(false);
        });
        AnimationView view = browser.getShowcase("card").getView();
        await(() -> view.getCurrentTime() > 0.05);
        WritableImage dark = Fx.call(() -> {
            view.pause();
            view.seek(1.25);
            return stage.getScene().snapshot(null);
        });
        WritableImage light = Fx.call(() -> {
            lookup(browser, "#dark", CheckBox.class).setSelected(false);
            browser.applyCss();
            return stage.getScene().snapshot(null);
        });
        int x = 400;
        int y = 40;
        assertEquals(Color.web("#0f172a"), dark.getPixelReader().getColor(x, y));
        assertNotEquals(dark.getPixelReader().getColor(x, y), light.getPixelReader().getColor(x, y));
        if (directory != null) {
            Fx.writePng(dark, directory.resolve("sample-app-dark.png"));
            Fx.writePng(light, directory.resolve("sample-app-light.png"));
        }
    }

    private static <T extends Node> T lookup(Node root, String selector, Class<T> type) {
        return type.cast(assertInstanceOf(type, root.lookup(selector), selector));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!Fx.call(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not reached within 10 s");
            }
            Thread.sleep(20);
        }
    }
}
