# AnimatorFX

[![Build](https://github.com/dlemmermann/AnimatorFX/actions/workflows/build.yml/badge.svg)](https://github.com/dlemmermann/AnimatorFX/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

AnimatorFX is a keyframe animation editor for JavaFX and a small runtime library that plays the
animations it creates inside JavaFX applications.

- You **author** an animation in the desktop editor and save it as an `.afx` file.
- Your application **plays** it with the `AnimationView` control. It needs one dependency,
  `animatorfx-player`, and none of the tooling.
- An animation can mix **vector content** (shapes, paths, text, images) with **slots**: named
  placeholders that the application fills with its own nodes, which the animation then drives.
- An animation can carry **sound**: audio layers that play in sync with it and cues that fire
  at a point in time.
- Embedded **video layers** play MP4/M4V picture and sound together on the animation timeline.

**User manual:** [Read online](docs/USER_MANUAL.md) · [Download the PDF](docs/AnimatorFX-User-Manual.pdf)

![The AnimatorFX editor, dark theme](docs/images/editor-dark.png)

<details>
<summary>Light theme</summary>

![The AnimatorFX editor, light theme](docs/images/editor-light.png)

</details>

## Contents

- [Features](#features)
- [Modules](#modules)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Embedding animations](#embedding-animations)
- [The editor](#the-editor)
- [The `.afx` format](#the-afx-format)
- [Importers](#importers)
- [Export to Lottie](#export-to-lottie)
- [Known limitations](#known-limitations)
- [Project layout](#project-layout)
- [Development](#development)
- [Contributing](#contributing)
- [Builds and releases](#builds-and-releases)
- [License](#license)

## Features

**Runtime**

- `AnimationView`, a resizable control: play, pause, seek, rate (also negative), cycle count,
  auto-reverse, fit modes, auto-play, CSS styleable.
- `AnimationPlayer`, the non-visual controller behind it, usable on its own.
- Markers: named time ranges that can be played by name.
- Slots, in two modes: *embedded* (your node lives inside the animation) and *in place* (your
  node stays in your layout and is moved, scaled, rotated and faded by the animation).
- Named live elements: interact with authored content and override its text, fill or visibility
  without losing customization during playback or node replacement.
- Shapes (rectangle, ellipse, line, polygon, star, Bezier path), text, images, groups.
- Solid, linear and radial paints; strokes with caps, joins and dashes; trim paths.
- Path morphing, motion paths with orient-to-path, clip and alpha masks, blur, drop shadow, glow.
- Sound: audio layers (MP3, WAV, AIFF, M4A/AAC) with animatable volume and balance, clip offset
  and loop, and sound cues; `muted` and `volume` on the view and the player. The sound follows
  the animation clock and the playback rate.
- Video layers: embedded MP4/M4V, transforms, masks, effects, source offset, looping and
  animatable soundtrack volume; one JavaFX media player supplies both picture and sound.
- Scene-graph rendering, so content stays crisp at any size. One `AnimationTimer` per player,
  running only while playing.
- Java 21, JavaFX 21 (including `javafx.media`), JPMS modules, no third-party dependencies.

**Editor**

- Canvas with select, shape, pen, text, image and slot tools, snapping, zoom and pan.
- Timeline with a layer tree, keyframes, markers, in/out bars and a scrubbing playhead.
- Sound: import of audio files, audio rows with waveforms, a cue lane, audible playback and
  scrubbing, a mute switch.
- Video import, reusable assets, rectangular canvas editing, inspector controls and clip bars.
- Inspector with a keyframe toggle on every animatable property and a cubic-bezier easing editor.
- Auto-key, undo/redo, copy/paste of layers and keyframes, groups.
- Import of SVG, Lottie JSON (with embedded sounds) and FXML.
- Export to Lottie: a dotLottie archive or Lottie JSON, with a report of what Lottie cannot hold.
- Dark and light theme, or follow the system.
- Dialogs that show inside the window (GemsFX `DialogPane`) instead of opening windows of their own.
- Session restore and crash recovery.

## Modules

| Artifact | JPMS module | Purpose | Depends on |
|---|---|---|---|
| `animatorfx-model` | `com.dlsc.animatorfx.model` | Immutable document model, interpolation, easing, `.afx` reader and writer | `java.base` |
| `animatorfx-player` | `com.dlsc.animatorfx.player` | Evaluator, renderer, `AnimationPlayer`, `AnimationView`, slot binding, sound | model, `javafx.controls`, `javafx.media` |
| `animatorfx-import` | `com.dlsc.animatorfx.importer` | SVG, Lottie and FXML importers, Lottie export | model, `javafx.fxml` |
| `animatorfx-editor` | `com.dlsc.animatorfx.editor` | The authoring tool | model, player, import, `javafx.media`, `java.desktop`, AtlantaFX, Ikonli, GemsFX |
| `animatorfx-sample-app` | `com.dlsc.animatorfx.sample` | Sample application (in `samples/`) | player |

Group id `com.dlsc.animatorfx`, version `1.0-SNAPSHOT`.

**An application needs `animatorfx-player` only.** It brings `animatorfx-model` and the JavaFX
artifacts it uses (`javafx-controls`, `javafx-media` and what those need) transitively.

## Requirements

| | Java | JavaFX | Other dependencies |
|---|---|---|---|
| Runtime (`model`, `player`) | 21 or later | 21 or later: `javafx.base`, `javafx.graphics`, `javafx.controls`, `javafx.media` | none |
| Editor and importers | JDK 27 | 27.0.1 | AtlantaFX 3.0.0, Ikonli 12.4.0, GemsFX 4.16.0 (editor only) |

Building the whole project needs **JDK 27**; the included Maven wrapper pins Maven 3.9.11.
Use `./mvnw` (or `mvnw.cmd` on Windows) instead of `mvn` in the commands below if Maven is not installed.
The runtime modules are compiled with
`--release 21` against JavaFX 21.0.13.

The player requires the module `javafx.media` since it plays sound. With Maven or Gradle there is
nothing to add: `animatorfx-player` declares `org.openjfx:javafx-media` as a compile-scope
dependency, so it arrives with the player like `javafx-controls` does. If you put the module path
together by hand (a JavaFX SDK, `jlink --add-modules`), `javafx.media` has to be on it, also for
animations without sound. Which audio formats play is decided by JavaFX media on the platform,
see [Known limitations](#known-limitations).

## Quick start

All commands run from the repository root with a JDK 27 as `JAVA_HOME`.

```bash
# build everything and run all tests (headless)
mvn clean install

# run the editor
mvn -pl animatorfx-editor javafx:run

# run the sample application
mvn -pl samples/animatorfx-sample-app javafx:run

# package the editor as a native application image
mvn -Ppackage -pl animatorfx-editor -am package
```

The two `javafx:run` commands need the modules in the local Maven repository, so run
`mvn clean install` first.

Options for running the editor:

```bash
mvn -pl animatorfx-editor javafx:run \
    -Danimatorfx.args="$PWD/samples/animations/success.afx" \
    -Danimatorfx.configDir=/path/to/a/scratch/dir
```

`animatorfx.args` are the files to open (absolute paths; the editor runs in its module directory); `animatorfx.configDir` moves the session directory (see
[Session and recovery](#session-and-recovery)).

The sample application starts with its sound switched off with
`mvn -pl samples/animatorfx-sample-app javafx:run -Danimatorfx.sample.muted=true`.

### The sample application

The **Interactive login demo** button opens a 1200×800 animation with real JavaFX form controls
embedded in the `login-form` slot of `samples/animations/login.afx`. Sign in with
`demo@example.com` and `animatorfx`, or try the simulated forgotten-password and reset flow.
The demo sends no email, contacts no server, and keeps password changes only until its window
closes. The animation can also be opened in the editor; the interactive form is supplied by the
sample application.

![The sample application](docs/images/sample-app.png)

`samples/animatorfx-sample-app` is a plain JavaFX window (Modena, no AtlantaFX) that depends on
`animatorfx-player` and nothing else; a test and a Maven Enforcer rule keep it that way. Its
transport row has a *Mute* check box and a volume slider, bound to the `muted` and `volume`
properties of the views and of the player of the in-place demonstration, and it disposes its
views when the window closes. It plays the files of `samples/animations`:

| File | Shows |
|---|---|
| `loader.afx` | A spinner: a stroked arc with round caps and animated trim start, end and offset, looping seamlessly |
| `success.afx` | A badge that pops in with overshoot, a check mark drawn on with a trim path, a ring pulse; markers `intro` and `success`. With sound: an audio layer under the intro whose volume fades in and out, and a cue that plays a chime at the start of `success` |
| `morph.afx` | Play, pause and stop morphing into each other through path keyframes, with animated gradients |
| `card.afx` | A card with a drop shadow, a masked shine sweep and two slots, `title` and `action`; marker `shine` |
| `orbit.afx` | Two shapes following motion paths, oriented to the path, with trimmed trails and a blurred glow |
| `attention.afx` | A single slot that wiggles, with a cue that ticks when it starts; used to animate an ordinary button in place |

![Frames of loader.afx](docs/images/sample-loader.png)
![Frames of success.afx](docs/images/sample-success.png)
![Frames of morph.afx](docs/images/sample-morph.png)
![Frames of card.afx](docs/images/sample-card.png)
![Frames of orbit.afx](docs/images/sample-orbit.png)

The files are generated with the model API by `SampleGenerator` (test scope of the sample
application), and a test checks that the committed files hold what the generator produces: the
same `animation.json` and the same bytes for every sound. The three sounds
are generated there as well, from sine waves, and stored as 16-bit mono WAV; no audio from
anywhere else is shipped. The other samples are silent. To write the files again:

```bash
mvn -pl samples/animatorfx-sample-app test -Dtest=SampleFilesTest -Danimatorfx.samples.write=true
```

To regenerate the separate interactive login scene:

```bash
mvn -pl samples/animatorfx-sample-app test -Dtest=SampleFilesTest#loginSceneHasAStaggeredEntranceAndAnAmbientLoop -Danimatorfx.login.write=true
```

### Packaging

`mvn -Ppackage -pl animatorfx-editor -am package` produces an application image for the platform
it runs on in `animatorfx-editor/target/dist` (`AnimatorFX.app` on macOS). Add `-DskipTests` if
the tests have already run. The profile

1. copies the editor and its runtime dependencies to `target/package/app/lib`,
2. links a trimmed Java runtime with JavaFX and the required JDK modules (`target/package/runtime`).
   Application libraries run on the class path because LangChain4j uses automatic modules, which
   `jlink` cannot link,
3. wraps the application and runtime with `jpackage --type app-image` (name AnimatorFX, version 1.0.0, bundle
   identifier `com.dlsc.animatorfx.editor`, and on macOS the icon `src/package/macos/AnimatorFX.icns`).

```bash
# macOS: start the packaged application
open animatorfx-editor/target/dist/AnimatorFX.app
# or, with a console and files to open
animatorfx-editor/target/dist/AnimatorFX.app/Contents/MacOS/AnimatorFX "$PWD/samples/animations/success.afx"
```

To check that sound works inside a packaged image, start it with `--diagnose-audio`. It opens no
window: it plays a generated sound in a muted media player, prints `audio: ok (…)` or
`audio: failed: …` and exits with 0 or 1.

```bash
animatorfx-editor/target/dist/AnimatorFX.app/Contents/MacOS/AnimatorFX --diagnose-audio
```

The image is not signed or notarized, there is no installer, and `.afx` files are not associated
with the application. Only the macOS (Apple Silicon) image has been built and started; on Windows
and Linux the profile uses the default icon and has not been tried. jDeploy uses the repository
root's `icon.png` for its installers and applications on all platforms.
`animatorfx-editor/src/package/make-icons.sh` rebuilds the macOS `.icns` and the small copy that
the About dialog shows from that same master artwork.

## Embedding animations

The artifacts are not published to a public repository yet: run `mvn clean install` once, then
depend on the player.

```xml
<dependency>
    <groupId>com.dlsc.animatorfx</groupId>
    <artifactId>animatorfx-player</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
module com.example.app {
    requires com.dlsc.animatorfx.player;   // brings the model, javafx.graphics and javafx.controls
}
```

The player also requires `javafx.media`, for sound. Your module does not have to name it unless
your own code uses media classes; it only has to be on the module path, where the Maven
dependency above puts it.

### Loading and playing

`AnimationIO.read` accepts a `Path`, a `URL` or an `InputStream` and returns an `AnimationFile`:
the document plus the bytes of its assets. It throws `IOException` — an `AnimationFormatException`
when the content is not a valid document — which the snippets below leave to the caller.

```java
AnimationFile file = AnimationIO.read(getClass().getResource("success.afx"));

AnimationView view = new AnimationView(file);
view.setFit(Fit.CONTAIN);                         // CONTAIN (default), COVER or NONE
view.setCycleCount(AnimationPlayer.INDEFINITE);   // default 1
view.setRate(1.5);                                // negative plays backwards
view.play();

view.pause();
view.seek(0.5);                                   // seconds; also view.setCurrentTime(0.5)
view.stop();                                      // back to the start

view.setAutoPlay(true);                           // start when the view is shown
```

`AnimationView` is a resizable control. Its preferred size is the document size; the content is
scaled uniformly, centred and clipped to the view. Playback pauses by itself while the view is not
part of a showing window.

Fit and auto-play can also be set from CSS (style class `animation-view`):

```css
.animation-view {
    -fx-fit: cover;        /* contain | cover | none */
    -fx-auto-play: true;
}
```

`view.getPlayer()` returns the `AnimationPlayer` with the remaining properties and events
(`autoReverse`, `status`, `activeMarker`, `onFinished`, `onMarkerReached`).

### Interacting with authored elements

Select one visual layer in the editor and set **Layer > Export name**, for example `submit`,
`greeting` or `accent`. Export names are independent of display names and internal IDs, and
must be unique throughout the animation, including nested groups. An empty name keeps a layer
private. Duplicating or pasting layers clears the copies' export names.

```java
AnimationElement submit = view.getElement("submit");
submit.setOnMouseClicked(event -> controller.submit());

view.getElement("greeting").setText("Welcome, Anna");
view.getElement("accent").setFill(Color.CORNFLOWERBLUE);
submit.setVisible(false);

view.getElement("accent").setFill(null);  // restore the current sampled fill
submit.clearOverrides();                 // restore authored values; keep the click handler
```

Call these methods on the JavaFX application thread after loading the document, even before
adding the view to a scene. Text (text layers), fill (shape and text layers), and visibility
overrides win until cleared; geometry, transforms, opacity and other properties still follow
the animation. A visibility override changes the layer's own visibility, including its in/out
range, but cannot overcome hidden parents, clipping or zero opacity.

Each export has one stable handle per view. Its `getNode()` is `null` until the skin renders it,
or while the export is absent. Listen to `nodeProperty()` to install other interactions such as
tooltips on each replacement node. Click handlers registered on the handle are reattached
automatically and removed from old nodes. Handles and overrides follow the same export name
when the document or skin changes; a replacement of another type ignores unsupported overrides.
An unknown visual export throws `IllegalArgumentException`; lookup without a document throws
`IllegalStateException`. Audio layers are not visual exports. Exported mask layers expose clip
nodes rather than independently interactive content.

Do not move these nodes into another parent or bind properties controlled by the renderer.
Shape layers expose groups containing paths, not necessarily JavaFX `Shape` instances. The
lower-level `getLayerNode(layerId)` remains available for tooling, but direct changes to
renderer-owned node properties can be overwritten by playback. Use slots for host-owned controls.

### Markers

A marker is a named time range of the document. `playMarker` plays that range and respects cycle
count and auto-reverse; an unknown name throws `IllegalArgumentException`.

```java
view.playMarker("intro");
view.getPlayer().setOnFinished(event -> view.playMarker("success"));
view.getPlayer().setOnMarkerReached(marker -> System.out.println("reached " + marker.name()));
```

### Slots

A slot is a placeholder layer with a name. There are two ways to fill it.

**Embedded** — the node becomes part of the animation. It is placed in the view, resized to the
slot's size if it is resizable, and gets the slot's full transform, opacity, mask and effects.

```java
AnimationView card = new AnimationView(AnimationIO.read(getClass().getResource("card.afx")));
card.bind("title", titleLabel);
card.bind("action", actionButton);
```

**In place** — the node stays where your layout puts it. The player drives its `translateX/Y`,
`scaleX/Y`, `rotate`, `opacity`, `visible` and `effect` relative to the slot's rest transform. No
view is needed:

```java
AnimationPlayer player = new AnimationPlayer(AnimationIO.read(getClass().getResource("attention.afx")));
player.bindInPlace("target", button);
player.setOnFinished(event -> player.unbind("target"));
player.play();
```

With a view, `autoBind` binds every still unbound slot in place to the node whose id equals the
slot name. `FXMLLoader` does not copy `fx:id` to the node id, so there is a variant that looks in
the FXML namespace first:

```java
view.autoBind(root);                          // looks up "#<slotName>" under root
view.autoBind(root, loader.getNamespace());   // fx:id first, then the node id
```

**Unbinding** — `view.unbind(name)` or `player.unbind(name)` restores every property the binding
changed to its value at bind time and, for an embedded node, removes it from the view. Binding a
slot again replaces the earlier binding. Binding an unknown slot name throws
`IllegalArgumentException`; a slot that is left unbound is skipped and logged once.

The editor writes this code for a document: *Document* tab ▸ *Copy Java Snippet*.

### Sound

An animation with audio layers or sound cues makes sound by itself when it plays; there is
nothing to set up. The view and the player have a mute switch and a volume:

```java
view.setMuted(true);                  // silent; the animation plays on
view.setVolume(0.5);                  // 0..1, multiplied with the volume of every layer and cue
muteCheckBox.selectedProperty().bindBidirectional(view.mutedProperty());
volumeSlider.valueProperty().bindBidirectional(view.volumeProperty());

view.dispose();                       // when the view is discarded for good
```

An `AnimationPlayer` used without a view has the same members: `player.setMuted(true)`,
`player.setVolume(0.5)`, `player.dispose()`.

The rules:

- **Sound only while playing.** A paused, stopped or finished animation is silent, and so is a
  view that is not part of a showing window. A seek while playing repositions the sound.
- **The animation clock is the master.** A layer's sound is repositioned when it drifts more than
  80 ms from where it should be. The sync is good to some tens of milliseconds, not to the sample.
- **The sound follows the playback rate** for rates above 0 up to 8. Playing backwards — a
  negative rate or the returning leg of auto-reverse — is silent.
- **Cues** fire when playback passes their time going forward, also at the very start of
  playback or of a marker range, and again on every cycle; never on a seek that jumps over them.
  A cue that is sounding rings out when the animation is paused, finishes by itself or its view
  is hidden. `stop()`, `setMuted(true)`, `dispose()` and replacing the animation cut it.
- **Mute and volume** apply from the next frame. An audio layer is also silent while it is
  muted or hidden in the document, or outside its in/out range.
- **Without sound the animation still plays.** If there is no audio device, the media stack is
  missing or a sound cannot be decoded, that is logged once per sound
  (`System.Logger` `com.dlsc.animatorfx.player`, level `WARNING`) and playback goes on silently.
- **Temporary files.** JavaFX media plays files, not bytes: the sounds of a playing document are
  extracted to a private directory (`animatorfx-audio…` under `java.io.tmpdir`). They are removed
  when the animation is replaced, on `dispose()`, and at the latest when the JVM exits.
- **`dispose()`** stops all sound and releases the media players and those files. Call it when a
  view or player will not be used again. It is final for sound until `play()` or `playMarker()` is
  called again or another animation is set. A view that leaves its scene releases its sound by
  itself and gets it back when it returns, unless its player drives nodes in place. An animation
  without audio layers and cues never creates a media player or a file.
- `view.scrub(seconds)` seeks like `seek` and, while not playing and unless muted, plays about
  0.1 s of the audio layers at that time (no cues). It is meant for tools such as the editor's
  timeline.

### Video

Video layers need no extra setup in an `AnimationView`. The animation clock controls both the
picture and its soundtrack through one decoder per layer instance. Layer and master mute or
volume affect sound, not picture; hidden or inactive layers and groups suppress both.
At time `t`, the source position is `clipOffset + t - inPoint`. Without looping, source end
hides the picture and silences sound; looping wraps to the source beginning.

MP4/M4V with H.264 video and AAC audio is the primary target. Actual codec support depends on
the platform's JavaFX media stack. Seeking is asynchronous and best-effort, not frame-accurate.
Paused scrubbing, reverse playback, rate zero and rates above 8 use silent seeking; ordinary
forward playback uses the native media clock with drift correction. Video does not join the
audible scrub snippets of audio-only layers.

Embedded bytes are extracted lazily off the FX thread into private temporary files. Visual
edits reuse the decoder; replacing/removing sources or layers, detaching the view, replacing
its skin, or calling `dispose()` releases obsolete players and files. Reattaching recreates
them as needed. A standalone `Renderer` supports silent seek-only video and must be disposed
when discarded. An unsupported clip is reported once and does not stop neighboring layers.

## The editor

### Layout

- **Toolbar** (top): document tabs, transport (to start, play/pause, to end), timecode, frame
  rate, loop, mute, zoom, theme switch.
- **Tool rail** (left): the tools below.
- **Canvas** (centre): the artboard with selection handles, path and motion-path editing and
  snap guides. It previews through the same renderer the runtime uses.
- **Side panel** (right): *Inspector* (properties of the selection, easing), *Assets* (images,
  fonts and sounds), *Document* (name, size, duration, frame rate, background, markers, Java
  snippet).
- **Timeline** (bottom): layer tree on the left; ruler, marker lane, cue lane, in/out bars,
  keyframes and the playhead on the right.
- **Status bar**: document size, layer count, selection, autosave state.

### Dialogs

![The question of closing a document with unsaved changes, inside the window](docs/images/editor-dialog.png)

The questions and messages of the editor — closing a document with unsaved changes, *Revert*,
*About*, the warnings of an import — show inside the main window, in a GemsFX `DialogPane` above
the content, in the colours of the theme. No second window opens. While a dialog is showing the
window behind it is dimmed and takes neither clicks nor keys, shortcuts and menu items included;
Tab stays within the dialog, Enter presses its default button, Escape cancels it, and the focus
returns to where it was afterwards. One dialog shows at a time: a request made meanwhile, such
as a second *Close*, is ignored. Only *Open*, *Save As* and the import choosers are the file
dialogs of the operating system.

### AI-generated animations

AnimatorFX uses LangChain4j for generation. Choose *Edit ▸ AI Preferences…* to select OpenAI or
Anthropic and save the provider's API key in
the operating system's credential store. The model dropdowns load models available to each key;
use *Refresh* after entering a new key, or type a model ID manually. Not every listed model
supports structured animation generation. On Linux, a working Secret Service or GNOME Keyring is
required; AnimatorFX does not fall back to storing keys in plain text. Leave a key field blank to
keep its existing key, or select its removal checkbox.

Choose *File ▸ Generate with AI…* and describe the animation. The dialog shows the selected
provider and model; your description is sent to that provider and may incur charges. The result is
validated and opens as a new, unsaved
document containing editable vector layers and keyframes; it does not generate image, audio or
video assets. Cancelling or failing a request leaves your existing documents unchanged.

When the selected provider has a saved key and model, the side panel offers an *AI Console*.
Use its *Chat* tab to describe additions or changes to the current project. It can add vector and
text layers and groups, edit supported properties and text, and add or update keyframes. Changes
are validated and applied as one undoable edit; a response is discarded if the project changes
while the request is in progress. Locked layers and groups are not modified.

After an AI edit, expand the affected layers in Chat to adjust supported values directly, including
drop-shadow color, radius, offsets and spread. Adjustments apply immediately as separate undoable
changes. Controls follow the current project and become unavailable when their target is locked,
removed or no longer safely identifiable. Complex paths remain editable on the canvas.

Generation and chat can repair invalid AI responses twice before reporting a failure. Chat also supports
renaming, duplicating and reordering layers and cubic-bezier keyframes. Changes are validated before
application; a chat request remains one undo step.

In AI Preferences, **visual review** is optional and disabled by default. When enabled, the application
renders three small frames of a validated candidate and sends them to a vision-capable model for one
possible refinement. These frames may reveal embedded project content and incur additional charges.
There are at most four provider calls per interaction. If visual review fails, is unsupported, or the
project contains video layers, the validated candidate is retained and the Activity tab reports the skip.

The project structure (including layer names and text), selection, playhead and recent conversation
are sent to the selected provider and may incur charges. Embedded media and local asset paths are
not sent. Conversations are kept in memory per open document and can be cleared in *Chat*.
The separate *Activity* tab shows session activity and sanitized error details; prompts, project
content and API keys are not intentionally recorded there.

AI provider requests and responses are also logged to the application terminal, including prompts
and project content. The configured API key is redacted and HTTP headers are not logged; treat
terminal logs as private.

#### On-demand model quality evaluation

The test-scope `AiQualityEvaluation` main class compares explicitly chosen models using the real
generation service, its validation/repair policy, and four synthetic prompts (bounce, seamless
loader, staged text/card, Bezier orbit). **Only the explicit main-class invocation below makes
API calls; normal tests/builds do not.** With JDK 27, from the repository root:

```bash
./mvnw -pl animatorfx-editor -am install -DskipTests
./mvnw -pl animatorfx-editor org.codehaus.mojo:exec-maven-plugin:3.7.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.dlsc.animatorfx.editor.ai.AiQualityEvaluation \
  '-Dexec.args=--provider OPENAI --model MODEL_A --model MODEL_B --output /absolute/review-dir'
```

Replace model IDs and destination; repeat `--model` as needed. For Anthropic, use
`--provider ANTHROPIC`. Set `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` securely in the environment,
or save the selected provider's key through AI Preferences (environment takes precedence).
There is no key argument or key file; documents echoing the configured key are withheld
(`sensitive_output`). **Billable/private:** each model runs four requests,
potentially three generation attempts per prompt plus SDK/network retries; prompts and generated
content go to that provider and may appear in redacted-key terminal traffic logs. No current
project or assets are read. Check provider pricing/data policies before running.

Each invocation creates a new `quality-*` subdirectory; exclusive, index-based `.afx` filenames
never overwrite existing files or incorporate model/provider-generated names. Open successful
files in AnimatorFX for editable review. `results.jsonl` maps filenames to model and full prompt,
validation (`passed` / `not_passed`), status, service-request count (`providerCalls`) and end-to-end
generation latency in milliseconds (includes repairs/validation, excludes disk writes).
`not_passed` includes unavailable responses, not just malformed output. Failures are recorded
without raw error text and remaining cases continue; each case has a six-minute timeout.
Main-class failure status 1 means failed cases; 2 means setup/report failure. Interruption stops
the run; the report retains completed rows. Visual AI review is disabled for this comparison.

**Human rubric:** hide model names while reviewing; score each dimension 0 (missing/broken),
1 (major issues), 2 (minor issues), 3 (polished). Check (1) prompt fidelity and timing,
(2) composition, contrast and readable text, (3) smooth motion and believable bounce/contact,
(4) loop seams or staged entrance/final hold as appropriate, and (5) editable layers/groups and
usable keyframes. Play at normal speed and scrub start/middle/end and contact/seam frames.
Record scores beside the model/prompt rows; a schema pass is not proof of visual quality.
This is a small, nondeterministic manual benchmark, not an automated quality ranking or cost meter.

Offline regression tests: `./mvnw -pl animatorfx-editor -am test -Dtest=AiQualityEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false`
(fake transport/credentials and temporary output only).

### Tools and shortcuts

| Tool | Key | Notes |
|---|---|---|
| Select | `V` | Click, Shift-click, marquee; drag moves; handles scale and rotate; double-click enters a group or edits a path or text |
| Rectangle, Ellipse, Line | `R`, `O`, `L` | Drag to draw; Shift = square, circle or 45°; Alt = from the centre |
| Polygon, Star | `Y`, `S` | |
| Pen | `P` | Click = corner, drag = smooth point; click the first point to close; Enter finishes |
| Text | `T` | Click to place, type, Enter commits |
| Image | `I` | Places a PNG or JPEG, which becomes an asset of the document |
| Slot | `K` | Drag a rectangle and name the slot |

Horizontal and vertical canvas scrollbars appear when the zoomed artboard exceeds the viewport.
Drag their thumbs to reach the off-screen parts; wheel/trackpad and Space-drag panning still work.

| Action | Shortcut (`Cmd` on macOS, `Ctrl` elsewhere) |
|---|---|
| Play / pause | `Space` |
| Mute or unmute the sound of the editor | `M` |
| Pan | `Space` + drag, middle-button drag, scroll |
| Zoom | `Cmd` + scroll, pinch, `Cmd =`, `Cmd -`, `Cmd 0` (fit), `Cmd 1` (100 %) |
| Nudge selection | Arrow keys (1 px), with Shift 10 px |
| Cancel a drag, leave a tool, a group or the selection | `Esc` |
| Edit the path of the selected layer | `Enter` |
| Delete layers, sound cues, keyframes or the selected marker (depends on the focus; in the timeline selected cues go first, then keyframes) | `Delete` / `Backspace` |
| Undo / redo | `Cmd Z` / `Shift Cmd Z` |
| Cut, copy, paste (layers, or keyframes while keyframes are selected) | `Cmd X`, `Cmd C`, `Cmd V` |
| Duplicate, select all | `Cmd D`, `Cmd A` |
| Group / ungroup | `Cmd G` / `Shift Cmd G` |
| Bring forward / send backward, to front / to back | `Cmd ]` / `Cmd [`, with Shift |
| New, open, save, save as, close | `Cmd N`, `Cmd O`, `Cmd S`, `Shift Cmd S`, `Cmd W` |
| Show or hide side panel / timeline | `Alt Cmd I` / `Alt Cmd T` |

### Keyframes and auto-key

- Every animatable property has a diamond in the inspector and in its timeline row; clicking it
  adds or removes a keyframe at the playhead.
- With **auto-key** on (the record button in the timeline header), changing a property at the
  playhead adds or updates a keyframe there. With it off, the change sets the static value, or
  the keyframe at the playhead of a property that is already animated.
- In the timeline, keyframes can be selected (click, Shift, marquee), dragged, copied and pasted
  at the playhead, and deleted. Times snap to frames unless Alt is held.
- Dragging on the canvas, scrubbing a number or dragging keyframes is one undo step per gesture.

### Easing

Select keyframes to get the easing editor at the top of the inspector: a curve with two handles,
presets (the CSS keywords and the sine, quad, cubic, quart, expo and back families), *Linear*,
*Hold*, and an editable `cubic-bezier(x1, y1, x2, y2)` text. Easing belongs to the keyframe a
segment starts from.

### Timeline

Expand a layer to see its animated properties, or all of them with the header toggle. Layer bars
move and trim the in/out range. Rows can be renamed, hidden, locked and reordered by dragging,
also into groups. A locked layer cannot be picked on the canvas, its bar and keyframes stay put
in the timeline, and the inspector only lets you unlock it. Markers live in the lane under the ruler: double-click to create, drag to move
or resize, double-click to rename.

### Audio layers and cues

![Audio layers with waveforms and the cue lane in the timeline](docs/images/editor-audio.png)

**Adding sounds.** *File ▸ Import ▸ Audio…* takes MP3, WAV, AIFF (`.aif`, `.aiff`), M4A and AAC
files of up to 50 MB, several at once; sound files can also be dropped onto the window. Each
becomes an asset of the document and an audio layer that starts at the playhead and lasts as long
as the sound (cut at the end of the document), in one undo step. *Add…* in the *Assets* tab
adds a sound without a layer; its row there has a button that places it on the timeline. A sound
cannot be removed while a layer or a cue uses it.

**Audio rows.** An audio layer is a row with a note icon. It has no presence on the canvas. The
speaker in the row, where other layers have the eye, switches the layer silent and back. The
layer bar shows the waveform of the sound, placed by the clip offset, repeated with a dashed line
at each repetition when the layer loops, and hatched where a sound that does not loop has ended.
When the volume is animated, a thin line over the waveform shows its course. Expanded, the row
lists *Volume* and *Balance* with their keyframes.

**Waveforms** are worked out in the background and kept for the session; they are not stored in
the document. For WAV and AIFF they are exact. For MP3 and M4A/AAC there is no decoder to read
the samples with, so the file is played silently through JavaFX media and its level is sampled:
the result is approximate and fills in from the left while the analysis runs. The analysis asks
for double speed; on macOS the media stack plays these formats in real time regardless, so a
three-minute song takes three minutes. Until a waveform is there, and if the analysis fails, the
bar is plain.

**Gestures on an audio bar.** Dragging the bar moves the in and out point together. Dragging the
left edge trims the start: the in point moves and the clip offset follows, so the sound stays
where it is on the timeline. Alt-dragging the left edge moves only the in point, which shifts the
sound. Dragging the right edge moves the out point. Alt-dragging the bar slides the sound under
the bar (only the clip offset changes). These gestures snap to frames.

**Cue lane.** Under the marker lane; a cue shows as a tick with a small speaker and its name. The
`+` button in the lane's header adds a cue at the playhead (with one sound in the document, that
one; otherwise it offers the sounds and *Import Sound…*). Double-clicking free space in the lane
adds a cue with the sound used last. The context menu of a marker has *Add Cue at Marker Start*.
Cues are selected by clicking (Shift or `Cmd` to add), dragged to move (frame snapping, Alt for
free), and removed with Delete.

**Inspector.** For audio layers the *Audio* section: the sound, its length and format, clip
offset, volume and balance with keyframe toggles, *Loop*, *Mute* and a preview button. For a
selected cue the *Cue* section: name, time, sound, volume, *Mute* and a preview button. The
preview plays also while the editor is muted.

**Playback and scrubbing.** Playback in the editor sounds as it will in an application; it uses
the same runtime. Dragging the playhead in the ruler plays a short piece of the audio layers at
each position. The speaker button in the toolbar, *View ▸ Mute Sound* and the key `M` switch
the sound of the editor off and on; the setting is kept in the session.

### Paths and morphing

Double-click a path layer (or press Enter) to edit its points: drag points and tangent handles,
click a segment to insert a point, Alt-click to switch between corner and smooth, Delete removes.
*Edit ▸ Convert to Path* turns a rectangle, ellipse, line, polygon or star into a path. With
auto-key on, editing a path at different times creates path keyframes, and the shape morphs
between them. Paths with different point counts are matched by subdividing the shorter one.

### Masks, effects, trim and motion paths

All in the inspector:

- **Trim**: start, end and offset of the stroke in percent of the path length.
- **Effects**: Gaussian blur, drop shadow, glow; any number, in order, every parameter animatable.
- **Mask**: pick a sibling layer as *Clip* (its geometry) or *Alpha* mask. The mask layer is no
  longer drawn itself.
- **Motion Path**: draw a path the layer travels along; *Progress* is animatable and *Orient to
  path* turns the layer into the direction of travel.

### Import

*File ▸ Import* reads SVG, Lottie JSON and FXML into a new document, an SVG also into the current
one, and images and sounds into the current document. Files can be dropped onto the window as well. Anything an
importer skipped is listed behind the *Show warnings* action of the notice, which stays until it
is dismissed, so the list can be opened again and copied. See
[Importers](#importers).

### Export

*File ▸ Export ▸ Lottie…* writes the current document as a dotLottie archive (`.lottie`), or as
plain Lottie JSON when the file name ends with `.json`. The document itself stays an `.afx`
document: Lottie cannot hold everything it can, so this is an export and no second way of saving.
What was left out or approximated is listed behind the *Show warnings* action of the notice. See
[Export to Lottie](#export-to-lottie).

### Themes

Dark, light, or follow the system: the toolbar button toggles, *View ▸ Theme* has all three.

### Session and recovery

The editor reopens as you left it: window bounds, panel layout, theme, mute, open documents, and per
document the zoom, pan, playhead, selection, expanded layers and auto-key state. Unsaved changes
are autosaved every 5 seconds to a recovery copy; after a crash or a quit, the document comes back
with those changes, marked as modified, with a notice that offers to discard them. A broken
session file is moved aside and never prevents the start; a recovery copy that cannot be read is
kept as `<id>.afx.unreadable-<timestamp>` in `recovery/` and a notice says so. Undo history is
not kept across restarts.

Before a document is saved or autosaved, the bytes are read back with the same reader that opens
files. If that fails, nothing on disk changes — the file, or the previous recovery copy, stays as
it was — the document stays modified, and a notice gives the reason with its place in the
document (for example `layers[1].slotName: Duplicate slot name: card`).

The session lives in the per-user configuration directory:

| Platform | Directory |
|---|---|
| macOS | `~/Library/Application Support/AnimatorFX` |
| Windows | `%APPDATA%\AnimatorFX` |
| Linux | `$XDG_CONFIG_HOME/animatorfx` (default `~/.config/animatorfx`) |

It holds `session.json` and `recovery/`. The system property `animatorfx.configDir` overrides the
location, for example `-Danimatorfx.configDir=/path/to/dir` with `mvn javafx:run`, or
`JAVA_TOOL_OPTIONS=-Danimatorfx.configDir=/path/to/dir` for the packaged application.

### Video layers

Click **Video** in the left toolbar, choose **File > Import > Video...**, or drop an MP4/M4V
file onto the window. Clips up to
**50 MiB** are embedded unchanged and placed at the playhead with their natural dimensions,
as one undoable import. Probing is asynchronous, never autoplays, and refuses audio-only or
unsupported files without adding partial content.

**Assets > Add...** stores a clip for reuse; its camera button places another video layer
without duplicating the embedded bytes. In-use assets cannot be removed. The **Video** inspector
edits source, width, height, offset, loop, mute and animated volume; ordinary visual sections
provide transforms, opacity, masks and effects.

Video rows have film-style clip bars, a visibility eye and a separate soundtrack-mute button.
Drag the bar to move its range. Left-edge trimming advances the source offset to keep the
remaining picture aligned; Alt-left-edge dragging changes only the in-point. Drag the right
edge to trim the end, or Alt-drag the body to slip the source under the unchanged range.
Clip gestures snap to document frames. No thumbnail strip or video-sound waveform is generated.

## The `.afx` format

An `.afx` file is a zip archive:

```
animation.json     the document
assets/<id>.<ext>  images, fonts, sounds and original MP4/M4V videos
```

- `animation.json` is UTF-8, pretty-printed with a stable key order, so unzipped files diff
  cleanly. Equal documents give byte-identical archives.
- `formatVersion` is `3`, adding assets of kind `video` and layers of type `video`.
  Version 2 added audio assets/layers and `cues`. Versions 1 and 2 still load; older readers
  reject version 3 through the unsupported-version error.
- The reader also accepts a bare `animation.json` when the document has no assets. The editor
  always writes zips: *Save* on a document that was opened from a file that is no `.afx` asks
  for an `.afx` file instead of replacing the source. *Save As* adds `.afx` to a name that does
  not end with it (`my.animation` becomes `my.animation.afx`) and refuses a file that is open
  in another tab.
- Groups nest at most 100 levels deep (a top-level layer is level 1). The writer rejects deeper
  documents, and the editor refuses to group, paste, drop or import beyond the limit.
- Time is in seconds, coordinates in document pixels (origin top-left, y down), angles in degrees
  clockwise, colours `#RRGGBB` or `#RRGGBBAA`, opacity and trim `0..1`, scale `1` = 100 %.
- Layers have a `type`: `shape`, `text`, `image`, `group`, `slot`, `audio`, `video`. They are listed bottom
  to top.
- Video members are `asset`, positive `width`/`height`, nonnegative `clipOffset` (default 0),
  animatable `volume` (0..1, default 1), `loop` and `muted` (default false). Decoder state and
  metadata are not saved. Source bytes survive archive round-trips unchanged.
- An animatable value is written plainly when static and as `{"keyframes": [...]}` when animated.
  A keyframe has a time `t`, a value `v` and an easing `e` that leads to the next keyframe:
  `"linear"` (default), `"hold"` or `[x1, y1, x2, y2]`.
- Missing optional members take defaults, unknown members are ignored. Errors are reported as
  `AnimationFormatException` with the JSON path of the offending element.

A complete document, a dot that swells and shrinks:

```json
{
  "formatVersion": 3,
  "name": "pulse",
  "width": 120,
  "height": 120,
  "duration": 1,
  "layers": [
    {
      "type": "shape",
      "name": "Dot",
      "transform": {
        "anchor": [30, 30],
        "position": [60, 60],
        "scale": {
          "keyframes": [
            {"t": 0, "v": [1, 1], "e": [0.37, 0, 0.63, 1]},
            {"t": 0.5, "v": [1.5, 1.5], "e": [0.37, 0, 0.63, 1]},
            {"t": 1, "v": [1, 1]}
          ]
        }
      },
      "geometry": {"type": "ellipse", "size": [60, 60]},
      "fill": {"type": "solid", "color": "#6366F1"}
    }
  ]
}
```

Save it as `pulse.json` and load it with `AnimationIO.read`, or pass it to the editor as a file
argument (the Open dialog lists `.afx` files only). Documents
can also be built in code with the records of `com.dlsc.animatorfx.model` and written with
`AnimationIO.write(file, path)`; `SampleGenerator` does exactly that.

Sound in `animation.json`: an asset of kind `audio`, an audio layer and a cue. The layer sounds
from `inPoint` to `outPoint`; `clipOffset` is where in the sound it begins, in seconds. `volume`
(`0..1`) and `balance` (`-1` left to `1` right) are animatable. Members with their default value
are left out when writing: `clipOffset` 0, `volume` 1, `balance` 0, `loop` and `muted` false, and
for a cue `name` empty, `volume` 1, `muted` false. The sound itself is the archive entry at the
asset's `path`, so this document needs the zip form.

```json
{
  "formatVersion": 3,
  "name": "jingle",
  "width": 120,
  "height": 120,
  "duration": 2,
  "assets": [
    {"id": "chime", "kind": "audio", "path": "assets/chime.wav", "name": "chime.wav"}
  ],
  "layers": [
    {
      "type": "audio",
      "name": "Chime",
      "inPoint": 0.5,
      "outPoint": 1.5,
      "asset": "chime",
      "clipOffset": 0.1,
      "volume": {
        "keyframes": [
          {"t": 0.5, "v": 0},
          {"t": 0.7, "v": 1}
        ]
      },
      "balance": -0.25,
      "loop": true
    }
  ],
  "cues": [
    {"id": "cue-1", "name": "Tick", "t": 1.2, "asset": "chime", "volume": 0.8}
  ]
}
```

## Importers

Importers never fail on unsupported features; they skip them and report a warning. They fail only
on unreadable input.

Input is bounded rather than trusted: an import stops at 20,000 layers and at 64 levels of
nesting, numbers that are not finite or larger than 1e9 are replaced by defaults or end the
affected path or layer, and Lottie track mattes that refer to each other are declined — each
with a warning. Every imported document is checked to be saveable before it is handed over.

**SVG → static layers.** Supported: `g`, `path` (all commands including arcs), `rect`, `circle`,
`ellipse`, `line`, `polyline`, `polygon`, `text`, `image` (embedded PNG/JPEG data URIs), `use` and
`symbol`, `transform`, presentation attributes and inline `style` with inheritance, linear and
radial gradients, `clipPath`, `opacity`. External entities and DTDs are never loaded.
Not supported: filters, patterns, masks, markers, CSS stylesheets (`<style>`), external `use`,
SMIL animation, `textPath`, `foreignObject`. Approximated: text position (one line; the baseline
is estimated without font metrics, `text-anchor` is exact), radial gradients without focal point, `fill-rule: evenodd` (drawn
non-zero), elliptical `rect` corners.

**Lottie JSON → animated document.** Supported: shape, solid, image, text, null and
precomposition layers (flattened into groups); transforms; rectangle, ellipse, star and path
shapes; fill, stroke, gradient fill and stroke; trim paths; masks; alpha track mattes; markers;
keyframe easing; parenting (as group nesting); Gaussian blur and drop shadow; audio layers whose
sound is embedded in the file.
Not supported: expressions, time remapping, repeaters, merge paths, round corners, other layer
effects, blend modes, 3D layers, curved motion between position keyframes, animated text,
luma and inverted mattes. Trim applies to strokes only.

> The Lottie file is read by [Lottie4J](https://github.com/lottie4j/lottie4j) (`com.lottie4j:core`),
> which gives the importer a typed model of the format; the importer converts that model to an
> AnimatorFX document. Lottie4J is a dependency of the importer and therefore of the editor, and
> brings Jackson 3 and the SLF4J API with it. The runtime (`animatorfx-model`, `animatorfx-player`)
> does not depend on it.
>
> The conversion is implemented from the format's documentation and tested against small
> hand-written files. It has **not** been validated against real After Effects exports;
> conventions such as skew direction, shadow direction and blur factors may be off.
>
> `LottieOracleTest` renders those hand-written files a second time with the Lottie4J player (a
> test dependency only) and compares the pictures. The two do not agree everywhere yet: the test
> records how far apart they are per file and fails when an import gets worse. Gradients are the
> largest known difference, and it is not established which side is right. Set the environment
> variable `LOTTIE_ORACLE_DUMP` to a directory to get both pictures of every compared frame.

**FXML → slots.** The file is loaded without its controller; every node with an `fx:id` becomes a
slot at its laid-out bounds, and the editor shows a picture of the node as the slot's preview.
The FXML is not stored in the `.afx` file. FXML that needs its controller or classes that are not
on the editor's module path cannot be imported. The preview pictures are not saved and are gone
after reopening the document.

## Export to Lottie

`LottieExporter` (in `animatorfx-import`) converts a document to the Lottie model of
[Lottie4J](https://github.com/lottie4j/lottie4j), which also writes the file:

```java
try (OutputStream out = Files.newOutputStream(Path.of("animation.lottie"))) {
    List<String> warnings = LottieExporter.writeDotLottie(AnimationIO.read(Path.of("animation.afx")), out);
}
```

`writeJson` writes plain Lottie JSON, `convert` returns the Lottie4J `Animation`. Like the
importers, the export never fails on what it cannot express; it reports a warning.

How a document is laid out in Lottie:

- **Shapes** become shape layers. Rectangles and ellipses stay rectangles and ellipses; lines,
  polygons and stars are written as the path the player draws.
- **Groups** become null layers their children are parented to. Opacity, effects, in and out point
  and visibility of a group are handed down to the layers inside, as they do not travel along a
  Lottie parent. A group with an opacity therefore looks different where its layers overlap.
- **Masks** become alpha track mattes, written right above the masked layer and named by `tp`. A
  group that is a mask becomes a precomposition.
- **Keyframes** keep their times and easings.
- **Motion paths** become one position keyframe per frame.
- **Values that keyframes cannot follow** are written frame by frame as well: the opacity of a layer
  inside a group when both are animated, and the offset of an animated drop shadow, which Lottie
  stores as a direction and a distance.
- **Clip masks** are reduced to their outlines, as the player does: filled, opaque, without stroke
  or effects.
- **Text colours** have no alpha in Lottie; the alpha of the fill becomes the opacity of the layer.
- **Images and sounds** are embedded as data URIs. Audio layers and sound cues become Lottie audio
  layers.
- **Video layers and their sound** are omitted, with a warning naming each layer. Video assets
  are not embedded, converted to poster frames or copied into dotLottie archives.
- **The background colour** goes into the manifest of the dotLottie archive; plain Lottie JSON has
  no place for it.

Not exported: slots, glow, the spread of a drop shadow, skew along y, the balance and looping of a
sound, font files, animated or gradient text colours. Paths and paints whose structure changes
between keyframes (another number of points or colours) are written without animation. Text is
placed by an estimated baseline, as it is on import.

`LottieExporterTest` exports the importer's test files, reads them back and compares the pictures,
and renders the exported files with the Lottie4J player as well.

## Known limitations

Out of scope by design:

- Expressions or scripting inside animations.
- Recording, editing or processing sound (effects, per-channel mixing beyond balance).
- State machines and input-driven animations.
- Export to GIF or video.
- 3D transforms.
- Collaboration and cloud storage.
- Undo history across restarts.

Gaps in what is there:

- **Not published yet.** The artifacts have to be installed locally until the first Maven Central release.
- **Multi-selection** can be moved, nudged, grouped and edited in the inspector, but has no
  scale or rotate handles.
- **Text** is a single line in one style; multi-line text cannot be typed in the editor.
  Alignment is about the origin of the layer: left-aligned text starts at it, centred text is
  centred on it and right-aligned text ends at it, so changing the alignment moves the text.
- **Colours** are stored with 8 bits per channel (`#RRGGBBAA`).
- **Trim** affects the stroke only, never the fill.
- **Gradients** blend between keyframes only when they have the same number of stops; otherwise
  they hold.
- **Masks** must be siblings of the layer they mask. A mask layer has to be selected in the
  timeline; it cannot be clicked on the canvas.
- **In-place slots** ignore masks and skew. `autoBind` by node id needs slot names that are plain
  CSS identifiers.
- **Paths**: the pen draws one contour per layer; *Convert to Path* refuses shapes with animated
  geometry; there are no numeric point coordinates.
- **Motion paths**: only the selected layer's path is drawn on the canvas.
- **Timeline**: no auto-scroll while dragging towards an edge, no context menu on layer rows.
- **Packaging**: unsigned application image only, macOS on Apple Silicon only tried, no file
  association for `.afx`.
- **Large documents** have not been profiled; the editor refreshes its panels on every change.
  Every save and recovery copy is serialised and parsed once more to verify it.
- **Sound sync** is good to some tens of milliseconds, not sample-accurate: each audio layer is
  a JavaFX media player that follows the animation clock and is repositioned when it drifts by
  more than 80 ms.
- **Playing backwards is silent** (negative rate, the returning leg of auto-reverse), and so are
  rate 0 and rates above 8.
- **Video seeking is asynchronous**, including scrubbing and reverse playback; it is not
  frame-accurate. Video codecs depend on JavaFX/platform support. There is no transcoding,
  video encoding, linked-media support or video waveform/thumbnail analysis.
- **Waveforms of MP3 and M4A/AAC** are approximate and are computed by playing the file silently,
  which on macOS takes as long as the sound; the length JavaFX reports for AAC includes encoder
  padding, so such a layer can be a little longer than its sound. WAV and AIFF waveforms are exact.
- **Audio formats depend on the platform's JavaFX media support.** Everything here was built
  and run on macOS (Apple Silicon) only, with WAV, AIFF, MP3 and AAC files. Not tested: Windows
  and Linux. According to the JavaFX documentation, on Linux JavaFX media needs GLib and ALSA,
  and for MP3 and AAC additionally the FFmpeg (or libav) libraries `libavcodec` and `libavformat`
  in a version that JavaFX release supports; without them only uncompressed sound (WAV, AIFF) can
  be expected to play. Where a sound cannot be played, the animation runs silently.
- **Large sounds make saving slower.** *File ▸ Save* serialises and verifies on the UI thread:
  about 270 ms for a document with 10 MB of audio (recovery copies are written in the
  background). Sounds are stored as they are; nothing is recompressed.
- **Cues** have a static volume and no balance; a cue cannot be stopped individually.
- **Images** need a width and a height above 0; an image layer with a size of 0 draws nothing.
- **Editor interaction is covered by automated tests that fire JavaFX events**, not by manual
  testing with real mouse and keyboard input. In particular the macOS menu shortcuts while a text
  field has the focus have not been checked by hand.
- **Importers**: see the limits above; Lottie import is unverified against real-world files.

## Project layout

```
animatorfx-model/      document records, interpolation, easing, .afx I/O (no JavaFX)
animatorfx-player/     Evaluator, Renderer, AnimationPlayer, AnimationView, slot binding
animatorfx-import/     SvgImporter, LottieImporter, FxmlImporter, LottieExporter
animatorfx-editor/     the editor; src/package holds native icon assets and their conversion script
samples/
  animations/          the sample .afx files
  animatorfx-sample-app/  application that embeds the runtime only; generator in its test scope
docs/
  images/              pictures of this README
  superpowers/specs/   the design documents (the editor and runtime, and audio support)
```

## Development

```bash
mvn clean install                                   # all modules, all tests
mvn -pl animatorfx-player test                      # one module
mvn -pl animatorfx-editor test -Dtest=MainWindowTest   # one test class
```

- Tests are JUnit Jupiter (JUnit 6, through the `junit-bom`). Everything that needs JavaFX runs **headless** on the Monocle platform with
  TestFX; no window appears and no display is needed. The flags are in the parent pom
  (`javafx.headless.jvm.args`).
- The build needs JDK 27 as `JAVA_HOME`, also for the Java 21 modules (they are compiled with
  `--release 21`). `model` and `player` are checked by Maven Enforcer to have no third-party
  runtime dependencies, the sample application to have no tooling dependencies (editor, importer,
  AtlantaFX, Ikonli, GemsFX).
- The model is immutable records; every editor change is a command from one document to the next,
  which is what makes undo, autosave and tests simple.
- Editor tests never touch the real session directory; they pass a temporary one.
- `README.md` is tested too: `ReadmeTest` in the sample application loads the JSON examples above
  and checks the Java snippets against the API.
- To rebuild the PDF after editing the [user manual](docs/USER_MANUAL.md), install
  `Markdown==3.10.2` for Python 3 and run `python3 docs/manual/build.py` with Chrome or Chromium 131+.
- No test is audible. The editor's tests run with `-Danimatorfx.audio=off` and observe sound
  through a recording output; the sample application's tests start it muted
  (`-Danimatorfx.sample.muted=true`); tests that use real media players run them muted or at
  volume 0. That holds for a run from an IDE as well: the UI tests of the editor and the tests
  of the sample application set their silencing property themselves.
- The design is described in `docs/superpowers/specs/2026-11-18-animatorfx-design.md` and, for
  sound, `docs/superpowers/specs/2026-11-19-audio-support-design.md`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and pull request guidelines and the
[Code of Conduct](CODE_OF_CONDUCT.md) for participation expectations. Report vulnerabilities
privately as described in [SECURITY.md](SECURITY.md).

## Builds and releases

[GitHub Actions](https://github.com/dlemmermann/AnimatorFX/actions) builds and tests the project
on `ubuntu-latest`, using Xvfb for a virtual display, and verifies the portable jDeploy editor
bundle. CI does not build platform-specific application images or native archives.

The manually triggered Release workflow publishes the embeddable runtime (`animatorfx-model`,
`animatorfx-player`, and their parent POM) to Maven Central with GPG signatures, sources and Javadoc,
then creates a tagged GitHub release. It uses the same Central Portal publishing approach and
secret names as GemsFX. A tag-bound **jDeploy** workflow adds editor installers for Windows x64,
Linux x64 and macOS (Intel and Apple Silicon) to that
[GitHub release](https://github.com/dlemmermann/AnimatorFX/releases).
Release and jDeploy also run only on `ubuntu-latest`.
jDeploy manages Java/JavaFX installation and application updates; application binaries
and update metadata are hosted on GitHub, not npm. See [the release guide](docs/RELEASING.md) for
setup, local jDeploy packaging, credentials and the release procedure.

## License

Copyright 2026 DLSC Software & Consulting GmbH. Licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Dependencies retain their own licenses.
