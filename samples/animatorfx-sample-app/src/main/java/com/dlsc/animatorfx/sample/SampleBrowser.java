package com.dlsc.animatorfx.sample;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;

/**
 * The window content: the list of samples, the selected sample and the in-place demonstration.
 */
public final class SampleBrowser extends BorderPane {

    private final ListView<String> list = new ListView<>();
    private final Map<String, SampleShowcase> showcases = new HashMap<>();
    private final InPlaceDemo inPlace = new InPlaceDemo();
    private final CheckBox dark = new CheckBox("Dark backdrop");
    private final BooleanProperty muted = new SimpleBooleanProperty(this, "muted");
    private final DoubleProperty volume = new SimpleDoubleProperty(this, "volume", 1);

    public SampleBrowser() {
        this(false);
    }

    /**
     * @param muted whether the window starts with its sound switched off
     */
    public SampleBrowser(boolean muted) {
        this.muted.set(muted);
        // the in-place player has no view; it follows the same mute and volume controls
        inPlace.getPlayer().mutedProperty().bindBidirectional(this.muted);
        inPlace.getPlayer().volumeProperty().bindBidirectional(volume);
        getStylesheets().add(SampleBrowser.class.getResource("sample.css").toExternalForm());
        list.setId("samples");
        list.setPrefWidth(150);
        list.getItems().addAll(Samples.SHOWCASE);
        list.getSelectionModel().selectedItemProperty().addListener((observable, old, name) -> show(name));
        setLeft(list);

        dark.setId("dark");
        dark.setSelected(true);
        dark.selectedProperty().addListener((observable, old, selected) -> {
            if (selected) {
                getStyleClass().remove("light");
            } else {
                getStyleClass().add("light");
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button loginDemo = new Button("Interactive login demo");
        loginDemo.setId("open-login-demo");
        loginDemo.setOnAction(event -> {
            LoginDemo demo = new LoginDemo();
            Stage dialog = new Stage();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("AnimatorFX - Interactive Login Demo");
            dialog.setScene(new Scene(demo, 1200, 800));
            dialog.setOnHidden(hidden -> demo.dispose());
            dialog.show();
        });
        Button liveElementsDemo = new Button("Live elements demo");
        liveElementsDemo.setId("open-live-elements-demo");
        liveElementsDemo.setOnAction(event -> {
            LiveElementsDemo demo = new LiveElementsDemo();
            Stage dialog = new Stage();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("AnimatorFX - Live Elements Demo");
            dialog.setScene(new Scene(demo, 640, 420));
            dialog.setOnHidden(hidden -> demo.dispose());
            dialog.show();
        });
        HBox footer = new HBox(12, inPlace, spacer, liveElementsDemo, loginDemo, dark);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(0, 16, 0, 0));
        setBottom(new VBox(new Separator(), footer));

        list.getSelectionModel().selectFirst();
    }

    public ListView<String> getList() {
        return list;
    }

    /** The showcase of a sample; created on first use. Mute and volume are the same for all of them. */
    public SampleShowcase getShowcase(String name) {
        return showcases.computeIfAbsent(name, key -> {
            SampleShowcase showcase = new SampleShowcase(key, Samples.load(key));
            showcase.getView().mutedProperty().bindBidirectional(muted);
            showcase.getView().volumeProperty().bindBidirectional(volume);
            return showcase;
        });
    }

    /** Whether the sound of every sample is switched off. */
    public final BooleanProperty mutedProperty() {
        return muted;
    }

    public final boolean isMuted() {
        return muted.get();
    }

    public final void setMuted(boolean value) {
        muted.set(value);
    }

    /** The volume of every sample, from 0 to 1. */
    public final DoubleProperty volumeProperty() {
        return volume;
    }

    public final double getVolume() {
        return volume.get();
    }

    public final void setVolume(double value) {
        volume.set(value);
    }

    /**
     * Releases the media players and temporary files of every view and of the in-place player.
     * Called when the window is closed for good.
     */
    public void dispose() {
        showcases.values().forEach(SampleShowcase::dispose);
        inPlace.getPlayer().dispose();
    }

    public InPlaceDemo getInPlaceDemo() {
        return inPlace;
    }

    private void show(String name) {
        // a view that leaves the window pauses by itself, falls silent and releases its sound; it
        // resumes when it comes back
        setCenter(name == null ? null : getShowcase(name));
    }
}
