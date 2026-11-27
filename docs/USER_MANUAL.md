# AnimatorFX User Manual

**Design in the editor. Play in JavaFX.**

Create a keyframed animation, save it as an `.afx` document, and play it inside a JavaFX application. This guide takes you from your first keyframe to a running application.

![AnimatorFX in dark mode, with an animation open on the canvas](images/editor-dark.png)

## Contents

- [1. Get started](#1-get-started) — Launch the editor and find your way around.
- [2. Make your first animation](#2-make-your-first-animation) — Animate a shape, preview it, and save it.
- [3. Build a composition](#3-build-a-composition) — Organize layers, import artwork, generate or edit with AI attachments, and name markers, slots or live elements.
- [4. Add sound](#4-add-sound) — Work with audio layers, waveforms, and cues.
- [5. Embed in JavaFX](#5-embed-in-javafx) — Add the player, package an `.afx` file, and run it.
- [6. Connect animations to your UI](#6-connect-animations-to-your-ui) — Play named ranges and animate application nodes.
- [7. Shortcuts and troubleshooting](#7-shortcuts-and-troubleshooting) — Find common commands and solve playback problems.

## 1. Get started

Launch the packaged **AnimatorFX** editor for your platform. The application image includes a Java runtime. If you are working from a source checkout, use JDK 27 and run these commands from the repository root:

```shell
mvn clean install
mvn -pl animatorfx-editor javafx:run
```

See the [README](../README.md#quick-start) for Maven wrapper, packaging, and sample-app instructions.

### Find your way around

![Light-theme workspace showing the canvas, Document tab, and timeline](images/editor-light.png)

*Figure 1. The workspace with the Document tab open.*

| Area | What you do there |
|---|---|
| **Toolbar and tabs** | Switch documents, play or pause, jump to the beginning or end, loop, mute, and zoom. |
| **Tool rail and canvas** | Choose a tool on the left. Draw, select, and edit layers on the artboard. |
| **Side panel** | Edit the selection in **Inspector**, manage media in **Assets**, and set size, duration, and frame rate in **Document**. |
| **Timeline** | Arrange layers on the left. On the right, scrub the ruler, edit keyframes, trim layer bars, and place markers and cues. |
| **Status bar** | Check document size, selection, and autosave state. |

> **Start with a sample.** Use **File → Open…** to open `samples/animations/success.afx`. Press **Space** to preview it, then drag the playhead to see how its layers and keyframes work together.

Toolbar playback looping is enabled by default. Switch off **loop** to play the animation once.

## 2. Make your first animation

In this exercise, a rectangle moves across a two-second artboard. You will set its first keyframe manually and let Auto-Key record the second.

1. Choose **File → New**. In the **Document** tab, set **Size** to `480 × 270`, **Duration** to `2 s`, and **Frame rate** to `30`. Leave the playhead at the start.
2. Press **R** for the Rectangle tool and drag a small rectangle on the left side of the canvas. Switch to **Select** (**V**) and select the rectangle if it is not already selected.
3. Open **Inspector → Transform**. Click the diamond beside **Position** to record the starting position at `0 s`.
4. Click the ruler at `1.5 s`. Turn on **Auto-Key** with the record button in the timeline header, then drag the rectangle to the right without changing its size. Turn Auto-Key off again. Expand the rectangle row in the timeline to see the two Position keyframes.
5. Return to the start using the toolbar and press **Space**. The rectangle moves between the two keyframes and holds its final position for the rest of the document.
6. Choose **File → Save As…** and save `slide.afx`. This is the file you will use in the JavaFX example.

### Refine timing and easing

Drag a keyframe to change when it happens. Keyframe times snap to frames; hold **Alt** while dragging for free positioning. To change how the rectangle moves without changing when it arrives, select the **first** Position keyframe and pick a curve in the **Easing** section of the Inspector. Try **Ease In Out** for a gentler start and finish.

| Easing | Effect on the segment after the keyframe |
|---|---|
| **Linear** | Changes the value at a constant rate. |
| **Hold** | Keeps the value until the next keyframe, then jumps. |
| **Preset or custom Bézier** | Changes the rate over the segment. Drag handles or enter a `cubic-bezier(...)` curve. |

> **Which diamond should I edit?** The Inspector diamond adds or removes a keyframe at the playhead. To change a keyframe already there, edit the property value rather than clicking the diamond again.

### Save and recover

Use **Save** for the current `.afx` and **Save As…** to make a separate copy. An `.afx` contains the document and its embedded assets; share it intact. Recovery autosaves are separate from your saved file. If the editor restores unsaved edits after a restart, review the recovered document and save it explicitly. Undo history is not restored.

## 3. Build a composition

### Work with layers

Draw rectangles, ellipses, lines, polygons, and stars with the shape tools. Use the **Pen** tool for Bézier paths: click for corners, drag for smooth points, click the first point to close the contour, or press **Enter** to finish it open. The **Text** tool places a single line of text. Use **Image** to place a PNG or JPEG.

In the timeline, drag rows to reorder layers or move them into groups. Lock a layer to prevent accidental editing; hide it to exclude it from the preview. Drag the middle of a layer bar to move its active interval, or drag an edge to change when the layer appears or disappears. Expand a row to edit its property keyframes.

With the Select tool active, right-click the canvas for Cut, Copy, Paste, Duplicate, Group, Ungroup, and layer ordering. Right-clicking an unselected layer selects it; right-clicking a selected layer keeps the current selection. You can also paste onto empty canvas. Duplicated visual layers are offset slightly so the copies are visible.

### Pick colors from the desktop

Every color field has a pipette button beside its hex value, including solid paints, gradient
stops and the document background. Activate it, then click the desired pixel anywhere on the
desktop. The color changes as one undo step and keeps the field's existing transparency.
**Escape** or a right-click cancels without changing the color.

You can also point at a pixel and press **Enter** to pick without clicking the application
behind it. Use this for system areas such as the macOS menu bar or Dock. macOS may require
**System Settings → Privacy & Security → Screen Recording** permission for AnimatorFX, or for
the Java launcher / IDE used to run it.

### Give a layer more movement

| To achieve this | Use this |
|---|---|
| Draw a stroke on over time | **Trim**: animate Start, End, or Offset. |
| Move a layer along a curve | **Motion Path**: draw a route and keyframe Progress. Enable **Orient to path** to turn along the route. |
| Morph one outline into another | Choose **Edit → Convert to Path** if needed, then edit path points at different times with Auto-Key on. |
| Reveal content through another shape | **Mask**: pick a sibling layer as a Clip or Alpha mask. The mask itself is no longer drawn separately. |
| Add depth or emphasis | **Effects**: add blur, drop shadow, or glow. |

### Import artwork

Use **File → Import** for SVG, Lottie JSON, or FXML. These imports open a new document by default; **SVG into Current Document…** adds SVG artwork to the active one. Images and sounds import into the active document. If an import reports skipped content, select **Show warnings** and inspect the resulting layers before saving. See [importer coverage](../README.md#importers) for format-specific limitations.

### Generate with AI

Choose **File → AI Preferences…** to select OpenAI or Anthropic and save an API key in the operating system's secure credential store. The model dropdowns are non-editable and offer only models supporting structured animation responses and image input, ordered for AnimatorFX. Use **Refresh** after entering a key.

Choose **File → Generate with AI…** to describe an animation and let the selected provider build it. If a provider key and model are missing, the editor offers **Open AI Preferences**. Generation runs in the background. When it is ready, click **Open Animation** in its notification to open the result as a new document.

To use your own artwork, click **Add Files…** in the generation dialog or drop files onto its **Attachments** area. You can attach up to five PNG, JPEG, GIF, or SVG files of at most 5 MB each. The AI either places an attachment in the animation (images as image layers, SVGs as editable vector layers) or uses it only as a reference for colors, style, and layout. Say in your description how each file should be used, for example "animate my logo into the center" or "use the colors of the photo". Only placed attachments are embedded in the document. Attachments are sent to the AI provider together with your description.

### Edit with AI Chat and attachments

With a provider key and model configured, open **AI Console → Chat** in the side panel to request changes to the active document. Chat can add or edit layers and keyframes; an accepted response is one undoable edit. Changes are discarded if the document changes during the request, and locked content is not modified.

1. Choose **Project image…** in the attachment tray to select an existing project image. Only your explicit selections are attached; the rest of the project's asset library is not shared.
2. Alternatively, click **Add Files…** or drop external PNG, JPEG, GIF or SVG files onto the tray. You can combine project images and external files, up to five attachments per message. External files are limited to 5 MB each.
3. Inspect the preview chips and remove any unwanted attachments. Invalid files or previews over 200 KB show an inline error. If a selected project image becomes unavailable after an edit or undo, restore it or remove its chip before sending.
4. Describe the intended use and click **Send**. For example: "Reuse this project image in the closing scene" or "Add this SVG logo as editable artwork."

The AI receives bounded PNG previews and attachment names, not the original files or local paths. Existing project images, including SVG image assets, keep their asset IDs when reused. Referenced external rasters become embedded image assets, and external SVGs become editable vector layers. Unused external attachments are not embedded; new layers and assets are applied and undone together.

Sending clears the attachment tray. A later message must attach its artwork again: conversation history retains attachment names but not image bytes. Switching documents also clears draft attachments. Project image choices update as assets are added, removed, undone or redone.

Requests share project structure, layer names, text, selection, playhead and recent conversation with the selected provider and may incur charges. **Enable AI visual review** in AI Preferences optionally sends three rendered frames for refinement; its information icon explains the behavior. Review can reveal embedded project content and adds provider calls. If review fails or is unavailable, the validated edit is retained. **Activity** reports progress and errors; terminal request logs can contain private project content.

### Name the parts your app will use

- **Marker:** a named time range, such as `intro`. Double-click empty space in the marker lane beneath the ruler to create one. Drag its body to move it, drag an edge to resize it, or double-click the marker to rename it. JavaFX can play the range by name.
- **Slot:** a named placeholder for a JavaFX node. Choose the **Slot** tool (**K**), drag a rectangle, and give it a unique name such as `title`. Animate the slot just like a visual layer. Use the same name when you bind the node in Java.
- **Live element:** select one unlocked visual layer and set **Layer → Export name**, such as `greeting`. This exposes the authored layer to your application without replacing it with a slot. Export names must be unique throughout the document, including nested groups; they are separate from display names and internal IDs. An empty export name keeps a layer private, and duplicated or pasted copies start without exports. See [Interact with an authored element](#interact-with-an-authored-element).

### Author an interactive state machine

1. Create a marker for each animation range you want a state to play.
2. Choose **View → State Machines** and click **New Machine**. Rename it in the inspector and
   click **Rename**. The first state uses the first marker.
3. Select a state in the graph or the state list. Set its name, marker, and whether it loops;
   check **Initial state** for the state to enter when the machine starts. Click **Apply State**.
   Use **Add State** to create more, and drag nodes to arrange the graph.
4. Select a source state, choose a target and enter an event name, then click **Add Transition**.
   Leave the event empty to transition when a non-looping state's marker finishes. Select an
   arrow or an item in **Transitions** to edit or delete it.
5. Resolve any missing-marker or duplicate-transition message in the inspector. Use **Undo** and
   **Redo** for graph changes, and **Canvas** to return to the animation.

Machines and graph positions are saved in `.afx`. Lottie export omits them and reports a warning.
Your application supplies events; a state change immediately interrupts the current marker.

For a complete example, open `samples/animations/download-workflow.afx` and choose **View →
State Machines**. Its six states have different visuals and markers. In the sample application,
**State machine demo** lets you start, pause, resume, simulate failure, retry, and reset while
showing the current state and most recent transition. Connecting and downloading advance on
completion; resuming starts the download animation again.

## 4. Add sound

Use an **audio layer** for a clip that must follow timeline time, such as narration or music. Use a **cue** for a one-shot sound, such as a click or completion chime.

![Timeline with an audio waveform, sound cue, and audio inspector](images/editor-audio.png)

*Figure 2. Audio layers and one-shot cues share the animation timeline.*

### Place and trim a clip

1. Move the playhead to the desired start and choose **File → Import → Audio…**. The editor embeds the asset and adds an audio layer starting at the playhead. It accepts MP3, WAV, AIFF, M4A, and AAC files up to 50 MB; playback support depends on the platform's JavaFX media stack.
2. Drag the bar to move the clip. Drag the left edge to trim the beginning while keeping the remaining sound in sync; drag the right edge to set the end. **Alt**-drag the bar to slide the sound within the existing interval.
3. In the **Audio** section of the Inspector, edit **Clip offset**, **Loop**, or **Mute**, and keyframe **Volume** or **Balance** as needed.

The waveform is generated in the background. Compressed formats may take longer and show an approximate waveform. If you only want a reusable asset, use **Assets → Add…** instead of importing it as a layer.

You can also drop audio files onto the window. Choose **Beginning** to insert at time zero,
**Current Time Cursor** to use the playhead position when you dropped the files, or **Cancel**
to import nothing. One choice applies to all audio files in the drop.
If the chosen cursor is at the animation's end, the editor asks you to move it earlier or
choose **Beginning**, rather than silently inserting the sound at zero.

### Edit volume directly on the track

Right-click an audio bar and enable **Edit Volume**. Click inside the bar to add a volume
point, then drag a point horizontally to change its time or vertically to change its volume.
The bottom is silence and the top is full volume. Points snap to animation frames; hold **Alt**
for an unsnapped time. Select a point and press **Delete**, or right-click it for deletion and
easing choices.

Volume points are normal keyframes and are saved with the document. Auto-key need not be
enabled, and changes support undo/redo; **Escape** cancels a point drag. Disable **Edit Volume**
through the audio bar's context menu to resume moving and trimming the clip.

### Place a cue and preview

Move the playhead and use **+** in the cue lane, then select or import a sound if prompted. Drag the cue to adjust its time; select it to edit its name, sound, or volume in the Inspector. To align a cue exactly with a marker, use the marker's **Add Cue at Marker Start** context command.

Press **Space** to hear the full mix. Scrubbing the ruler previews audio layers, not cues. Cues fire when forward playback reaches them, not when you jump past them. **M** mutes the editor preview without changing the saved animation.

### Add video

Click **Video** in the left toolbar, choose **File > Import > Video...**, or drop an **MP4/M4V**
file onto the window. The original
clip is embedded in the project (maximum **50 MiB**), placed at the playhead and selected.
H.264/AAC is the primary supported combination; actual decoding depends on JavaFX and the
platform's codecs. Audio-only files and failed probes add nothing.

Resize and transform video on the canvas like an image. The **Video** inspector provides
source, dimensions, clip duration, source offset, looping, mute and keyframed volume.
**Assets > Add...** stores reusable clips; the camera button places another layer without
duplicating the bytes. Import, placement, edits and trimming are undoable.

Video has a film-style timeline bar. Drag its body to move it, its right edge to trim the end,
or its left edge to trim while preserving picture/sound alignment. **Alt**-drag the left edge
to change only the in-point; **Alt**-drag the body to slip the source under its current range.
These gestures snap to frames. The **eye** controls picture visibility; the separate **speaker**
mutes only the soundtrack. Master mute also leaves the picture moving.

Seeking and paused scrubbing are silent, asynchronous and not frame-accurate. Reverse playback,
rate zero and unsupported rates use best-effort silent seeking. Source end hides the picture
unless looping. Video has no waveform or thumbnail strip. Saving retains the original bytes
in format-version-3 `.afx` projects; versions 1 and 2 remain readable. Lottie export omits video
and its soundtrack with a warning naming the layer.

## 5. Embed in JavaFX

The editor and the runtime are separate. Your application needs **`animatorfx-player`**, not the editor or the importers. The player runs on Java 21 or later with JavaFX 21 or later and includes its JavaFX dependencies transitively when resolved with Maven.

### Add the dependency and the animation

If you are using this checkout's `1.0-SNAPSHOT`, run `mvn clean install` with JDK 27 at the repository root first. In your JavaFX application's `pom.xml`, add:

```xml
<dependency>
    <groupId>com.dlsc.animatorfx</groupId>
    <artifactId>animatorfx-player</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Use the corresponding release version if you are using a published player. For a modular application, add `requires com.dlsc.animatorfx.player;` to `module-info.java`. For the example below, a minimal descriptor is:

```java
module com.example.app {
    requires com.dlsc.animatorfx.player;
    exports com.example.app to javafx.graphics;
}
```

Save the animation to `src/main/resources/animations/slide.afx` in your application. For an existing sample instead, copy `samples/animations/success.afx` there as `slide.afx`. Keep it as an `.afx` archive; do not extract its assets. If your Maven build filters resources, exclude `.afx` files from filtering and copy them unchanged.

> **Building the module path by hand?** Include `javafx.media` even for silent animations. It is a dependency of the player; Maven resolves it automatically.

![JavaFX sample application displaying five animations and playback controls](images/sample-app.png)

*Figure 3. The sample JavaFX application plays `.afx` files without loading the editor.*

### Load, show, and play

In your JavaFX project, save this as `src/main/java/com/example/app/AnimatorExample.java`. Run `AnimatorExample` as the JavaFX application (use `com.example.app/com.example.app.AnimatorExample` as the main class if your Maven JavaFX launcher expects a module-qualified name).

```java
package com.example.app;

import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.player.AnimationView;
import com.dlsc.animatorfx.player.Fit;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Objects;

public class AnimatorExample extends Application {
    private AnimationView view;

    @Override
    public void start(Stage stage) throws Exception {
        URL resource = Objects.requireNonNull(
                getClass().getResource("/animations/slide.afx"),
                "Missing /animations/slide.afx on the classpath");
        view = new AnimationView(AnimationIO.read(resource));
        view.setFit(Fit.CONTAIN);
        stage.setScene(new Scene(new StackPane(view), 480, 270));
        stage.setTitle("AnimatorFX Example");
        stage.show();
        view.play();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

`AnimationIO.read(URL)` works with resources in a JAR as well as in a development directory. For an animation chosen from disk, use the `Path` overload. Loading can throw `IOException`, including an `AnimationFormatException` for invalid animation data. Report that error to your user instead of trying to play an invalid file.

## 6. Connect animations to your UI

### Control the view

| Want to… | Call… |
|---|---|
| Pause, jump, or reset | `view.pause()`, `view.seek(0.5)` (seconds), or `view.stop()`. |
| Loop | `view.setCycleCount(AnimationPlayer.INDEFINITE)`. |
| Change speed | `view.setRate(1.5)`; a negative rate plays backward without sound. |
| Start when shown | `view.setAutoPlay(true)` instead of calling `play()` explicitly. |
| Fit the available space | `view.setFit(Fit.CONTAIN)` fits everything; `COVER` fills and may crop; `NONE` keeps the original size. |
| Change audio | `view.setMuted(true)` or `view.setVolume(0.5)`; volume ranges from `0` to `1`. |

Use JavaFX's application thread to configure and control the view. A view pauses playback outside a showing window. Call `dispose()` when you permanently discard a view or standalone player so it can release its media resources.

### Play a named range

If the animation has a marker named `intro`, replace `view.play()` with `view.playMarker("intro")`. A marker plays just its range and respects cycle count and auto-reverse. Use an exact name: an unknown marker raises `IllegalArgumentException`. The underlying `AnimationPlayer`, available through `view.getPlayer()`, exposes `setOnFinished` and `setOnMarkerReached`.

### Run a state machine

After loading an animation containing a machine named `button`, retrieve it and send it events:

```java
// Imports: com.dlsc.animatorfx.model.StateMachine,
//          com.dlsc.animatorfx.player.StateMachineController
StateMachine machine = view.getAnimation().animation().stateMachines().stream()
        .filter(m -> m.name().equals("button")).findFirst().orElseThrow();
StateMachineController controller = new StateMachineController(view.getPlayer(), machine);
controller.start();
controller.send("click"); // an unmatched event is ignored
// When the machine is no longer needed:
controller.stop();
```

The controller runs on the JavaFX application thread, validates its markers on start, and directs
the player until stopped. A non-looping state may transition on completion. Stop the controller
before controlling the player directly; replacing the animation also stops it.

### Interact with an authored element

A **live element** lets your application interact with existing animated artwork instead of supplying its own node in a slot.

Select one visual layer and give it an **Export name** in the **Layer** inspector. The name
must be unique across the entire animation, including groups. It is separate from the layer's
display name; leave it empty to keep the layer private. Duplicated and pasted copies start
without export names.

After loading that animation, use the names from your application:

```java
// Import: javafx.scene.paint.Color
view.getElement("submit").setOnMouseClicked(event -> controller.submit());
view.getElement("greeting").setText("Welcome, Anna");
view.getElement("accent").setFill(Color.CORNFLOWERBLUE);
view.getElement("submit").setVisible(false);
```

Configure handles on the JavaFX application thread, even before showing the view. Text overrides
apply to text layers, fill overrides to shape or text layers, and visibility overrides to visual
layers. Application overrides win during playback; all other properties continue to animate.
Pass `null` to clear one override or call `clearOverrides()` to restore all authored values
without removing the registered click handler.

Handles follow their export names across document and skin replacements. `getNode()` is `null`
before rendering or while an export is absent. Listen to `nodeProperty()` to install additional
interactions such as tooltips whenever a node is replaced. Do not reparent the rendered node or
bind its animation-controlled properties. Unknown exports raise `IllegalArgumentException`.
Audio layers cannot be accessed as visual elements; mask layers expose clip nodes, not separately
interactive artwork. Use slots below for application-owned controls.

Overrides customize playback without changing the saved `.afx` document. They remain on the handle while its export is absent and apply again when it returns. A replacement layer of another type ignores unsupported overrides. Visibility changes the layer's own visibility, including its active interval, but cannot overcome hidden parents, clipping or zero opacity. Looking up an element before loading a document raises `IllegalStateException`.

Run the sample application's **Live elements demo** for a complete example. It loads `samples/animations/live-elements.afx`: click the animated circle to change its fill and greeting, toggle the sparkle's visibility, and reset the overrides. The implementation is in `LiveElementsDemo`.

### Put a JavaFX node into the animation

Use an **embedded binding** when the animation should own the node's placement. For example, package `samples/animations/card.afx` as `src/main/resources/animations/card.afx`. In your `start` method, load it in place of `slide.afx` and bind these nodes before playback:

```java
// Imports: javafx.scene.control.Label and javafx.scene.control.Button
view.bind("title", new Label("New message"));
view.bind("action", new Button("Open"));
```

The nodes become children of the animation view, take the slot's transform, and are resized to the slot if resizable. Give the view new or unparented nodes. An unbound slot is skipped; binding an unknown slot name raises `IllegalArgumentException`.

### Animate a node that is already in your layout

Use an **in-place binding** when the application should keep ownership of the node's layout. Package `samples/animations/attention.afx` under `src/main/resources/animations/`. With an existing `button` in your layout, run this code on the JavaFX application thread:

```java
// Import: com.dlsc.animatorfx.player.AnimationPlayer
URL resource = Objects.requireNonNull(
        getClass().getResource("/animations/attention.afx"),
        "Missing /animations/attention.afx");
AnimationPlayer player = new AnimationPlayer(AnimationIO.read(resource));
player.bindInPlace("target", button);
player.setOnFinished(event -> player.unbind("target"));
player.play();
```

No `AnimationView` is needed for this example. The button stays in its layout; the player changes properties such as translation, scale, rotation, and opacity relative to their original values. Keep a reference to the player. When discarding it, unbind any remaining slots to restore the node properties, then call `player.dispose()`.

For views with several in-place slots, `view.autoBind(root)` matches slot names to node IDs. For FXML, `view.autoBind(root, loader.getNamespace())` checks the loader's namespace first, then node IDs.

> **Generate a starting point.** Open **Document → Copy Java Snippet** to copy loading, slot, and marker calls for the active document. Adjust the resource path and supply the actual nodes from your application.

See the [sample application](../samples/animatorfx-sample-app) for a complete application with both binding modes.

## 7. Shortcuts and troubleshooting

Use **Cmd** on macOS and **Ctrl** elsewhere for shortcuts shown with **Cmd/Ctrl**.

| Action | Shortcut |
|---|---|
| Select, rectangle, ellipse, line | `V`, `R`, `O`, `L` |
| Polygon, star, pen, text, image, slot | `Y`, `S`, `P`, `T`, `I`, `K` |
| Play / pause; mute | `Space`; `M` |
| Pan; zoom to fit | `Space` + drag; `Cmd/Ctrl` + `0` |
| Nudge selection; nudge by 10 px | Arrow key; `Shift` + arrow key |
| Edit selected path; exit editing or cancel | `Enter`; `Esc` |
| Undo; redo | `Cmd/Ctrl` + `Z`; `Shift` + `Cmd/Ctrl` + `Z` |
| Group; ungroup | `Cmd/Ctrl` + `G`; `Shift` + `Cmd/Ctrl` + `G` |
| Save; save as | `Cmd/Ctrl` + `S`; `Shift` + `Cmd/Ctrl` + `S` |

When the zoomed artboard is larger than the canvas viewport, scrollbars appear along the bottom
and right edges as needed. Drag their thumbs to navigate, or continue using wheel/trackpad
scrolling and **Space** + drag. The scrollbars hide again when the artboard fits.

### Diagnose a problem

| Symptom | What to check |
|---|---|
| A property changed everywhere | Place a keyframe at the starting time, move the playhead, then turn on Auto-Key before editing the next value. |
| A layer is missing in the preview | Check its visibility, its timeline in/out range, and whether it is used as a mask. |
| An import looks different | Open **Show warnings** and look for unsupported features. Preview and correct the editable result before saving. |
| Java cannot find the animation | Check the leading `/` in the resource path, spelling and case, and that your build copied the `.afx` into the JAR. |
| The view is blank or does not advance | Add it to the scene and show the stage before playing. For a slot-only file, bind a node or use a standalone player. |
| Sound is missing | Check the editor or view mute state, clip range and volume, and playback direction. Confirm platform codec support and that `javafx.media` is on the module path. |
| A cue did not fire | Play forward across its time instead of seeking past it. If playing a marker, place the cue inside that range or at its start. |

**More information:** [AnimatorFX project documentation](../README.md) · [Sample `.afx` files](../samples/animations)
