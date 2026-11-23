# AnimatorFX Video Layers - Design

Date: 2026-11-23
Status: approved after written-design review
Extends: `2026-11-18-animatorfx-design.md` and `2026-11-19-audio-support-design.md`.
This design lifts their exclusion of video for the scope below.

## 1. Goal and approved decisions

An animation can contain video layers with synchronized picture and sound, both in the
editor and when embedded in an application through `AnimationView`.

| Topic | Decision |
|---|---|
| Storage | Embed video files in `.afx`, like image and audio assets |
| Import size | At most 50 MiB per clip, matching the existing audio import limit |
| Decoder | JavaFX media; no new native or third-party runtime dependency |
| Initial formats | MP4 containers (`.mp4`, `.m4v`) with streams the platform's JavaFX media stack can decode; H.264/AAC is the primary target |
| Synchronization | The animation clock remains authoritative |
| Sound | The video's own audio track, with per-layer mute/volume and existing master mute/volume |
| Seeking | Asynchronous, best-effort JavaFX seeking, not frame-accurate editing |
| Editing | Import, asset placement, transforms, sizing, opacity, timeline trimming, source offset, loop, undo/redo |
| Export | Omit video layers and their sound from Lottie, with explicit warnings |

Linked external files and an FFmpeg-based decoder were considered. Embedded assets preserve
the existing portable-project model; JavaFX preserves the dependency-free runtime. The tradeoffs
are larger projects, platform-dependent codec availability, and asynchronous seeks.

## 2. Model and `.afx` format

`animatorfx-model` remains immutable and depends only on `java.base`.

- Add `Asset.Kind.VIDEO`; store the original clip under `assets/` with its file extension.
- Add `VideoLayer` to the sealed layer hierarchy:
  - `base`: common identity, visibility, lock, time range, transform, opacity, mask, effects,
    and motion path.
  - `assetId`: a reference to a `VIDEO` asset.
  - `width`, `height`: positive finite dimensions in document pixels; default to the imported
    video's natural dimensions when creating a layer.
  - `clipOffset`: nonnegative finite seconds into the source at the layer's in-point; default 0.
  - `volume`: animatable value in `0..1`; default 1.
  - `loop`, `muted`: static flags, default false.
- The reader validates asset references and kinds, dimensions, offset, volume values, and
  keyframes using the existing path-specific format-error conventions.
- The evaluator samples video volume and the ordinary visual properties. Media decoder objects,
  temporary paths, loading state, and native metadata do not enter the model or undo history.
- Advance `formatVersion` from 2 to 3. Versions 1 and 2 load without structural migration.
  Older readers reject version 3 through the existing unsupported-version error.
- JSON uses layer `"type": "video"` and members `asset`, `width`, `height`, `clipOffset`,
  `volume`, `loop`, and `muted`, alongside the existing common members. Omit optional defaults
  consistently with the existing writer.
- The archive stores the source video bytes unchanged. The document remains self-contained
  after moving it to another computer; decoding still depends on that computer's codec support.

## 3. Runtime and media ownership

### 3.1 Rendering and synchronization

Render a video through `MediaView` within the normal layer node tree. Its transform, opacity,
effects, and incoming masks follow the same path as other visual layers. Geometry-only uses,
including clip-mask geometry and canvas selection, use its rectangular bounds; they must not
create an additional audible decoder.

A per-player video session owns one `MediaPlayer` per video-layer instance, not per asset.
Two layers referencing the same clip can therefore have independent offsets, volume, and
playback positions. The visible `MediaView` and soundtrack use that same player: the existing
audio-layer engine must not open a second player for the video's sound.

Keep the media session separate from the pure evaluator. A narrow internal scheduling/output
boundary decides desired media state and makes timing testable without codecs. Rendering
borrows the session's players rather than taking ownership of them.

`AnimationViewSkin` connects the renderer to its player's video session. A standalone renderer
must also support silent, seek-only video rendering with explicit ownership and cleanup; it
must not start playback or emit sound merely because a frame was applied.

### 3.2 Time and sound behavior

For animation time `t` within a layer's active range, the requested source time is:

```text
sourceTime = clipOffset + t - inPoint
```

- Treat video activity as `inPoint <= t < outPoint`; outside it the picture is hidden and
  sound is silent.
- Without looping, reaching the source end hides the picture and silences sound rather than
  leaving an indefinitely frozen last frame.
- With looping, wrap source time by the decoded source duration. The offset selects the
  initial position; subsequent wraps return to the source beginning, matching audio layers.
- Ordinary forward playback uses the native media clock and playback rate, with bounded
  drift correction against the animation clock. Do not issue a seek on every animation pulse.
- Seek or loop discontinuities reposition the media. While loading, retain only the latest
  desired position and playback state.
- Pause freezes the picture and stops sound. A paused seek or scrub updates the picture
  silently; video tracks do not participate in the existing audible audio-layer scrub snippets.
- Rate zero freezes the picture and is silent. Forward rates supported by JavaFX media follow
  the animation rate. Unsupported rates, negative rates, and the reverse leg of auto-reverse
  use coalesced, best-effort silent seeking instead of smooth reverse decoding.
- Marker ranges and animation cycles obey the same rules as full-document playback.
- Effective volume is sampled layer volume multiplied by master volume. Layer mute or master
  mute silences sound without hiding the picture or stopping its forward motion.
- Hidden layers, layers outside their time range, and children of hidden or inactive groups
  are silent. Opacity alone does not act as an audio mute.

These are synchronization targets, not a frame-accuracy guarantee. Pending decoder operations
must not block the JavaFX application thread or allow stale seek callbacks to restore an old
frame after the user has moved on.

### 3.3 Lifecycle and failures

- Extract embedded bytes lazily into private temporary media files, using the existing
  media-file lifecycle conventions where possible.
- Reuse a session across edits that leave a video's source and identity unchanged. Updating
  transforms or volume must not recreate its decoder on every edit.
- Release obsolete players and temporary files when layers/assets are replaced or removed,
  documents change, views/skins are detached or disposed, or the owning player is disposed.
  Reattaching or restarting follows the existing view/player resource-recreation contract.
- Invalidate callbacks after disposal, document replacement, or a superseding import. A late
  ready callback must never restart an abandoned or paused player.
- A corrupt or unsupported asset fails locally: keep the rest of the animation playing,
  silence the failed layer, and report a useful diagnostic once rather than once per pulse.
  The editor surfaces the affected clip and failure through its existing notice mechanism.
- Animations without video allocate no video decoders or temporary video files.

## 4. Editor integration

### 4.1 Import and assets

- Add File > Import > Video, dropping video files on the window, and video import/placement
  in the Assets tab.
- Check the 50 MiB limit before reading bytes. Read files off the JavaFX thread; probe media
  readiness asynchronously with cancellation and a finite timeout.
- Validate that the source contains an actual video track, with usable duration and natural
  dimensions; an audio-only MP4 is not a video layer. Unsupported streams or failed probes
  produce a notice without adding a partial asset or layer.
- Import adds the embedded asset and selected video layer in one undoable command, positioned
  at the playhead with an out-point based on the source duration and bounded by the document's
  duration, following audio placement conventions.
- Reusing a stored video asset creates another layer without duplicating its asset bytes.
  Refuse asset deletion while a layer references it.
- Metadata probing and asset readiness never autoplay a clip or make sound.

### 4.2 Canvas, inspector, and timeline

- Treat video as a visual rectangular layer for hit testing, selection, movement, resizing,
  grouping, layer ordering, copy/paste, and undo/redo.
- Preserve the source aspect ratio when initially placing a clip. Width and height remain
  editable, with the ordinary transform and opacity controls available.
- Provide a Video inspector section with the asset, dimensions, clip duration readout, source
  offset, loop, mute, and animatable volume. Reuse common sections for the visual properties.
- Show a distinct video icon and clip bar on the timeline. Keep picture visibility and audio
  mute as separate concepts; muting must not hide the video.
- Moving the bar moves its active range. Left-edge trimming advances source offset so the
  remaining frames stay aligned; Alt-left-edge dragging changes only the in-point. Right-edge
  dragging changes the out-point; Alt-dragging the bar slips the source under the range.
  Clip gestures snap to document frames, following the audio-clip conventions.
- The existing playhead and transport drive both video and audio. No separate video transport
  is introduced.

Timeline thumbnail strips and waveform analysis for video sound are not part of this version.

## 5. Export, compatibility, and documentation

- Extend exhaustive layer/asset handling throughout model utilities, validation, editor
  commands, bounds, asset-reference checks, clipboard, and exporters.
- Lottie export omits video layers and their associated video assets/sound, with a warning
  naming each omitted layer. Continue exporting supported layers; never encode video bytes
  as an image or silently convert a video to a poster frame.
- Preserve loading and playback of existing animations. New video APIs must not require
  applications containing only shapes, images, slots, or audio to change their code.
- Update the README, user manual source, and format documentation with the import workflow,
  size limit, runtime lifecycle, codec/platform caveat, silent asynchronous scrubbing, reverse
  limitations, and Lottie warning behavior.
- Include a small original video fixture for playback verification. Document its provenance;
  generating it may use development tooling, but building or running AnimatorFX must not
  require that tooling.

## 6. Verification and acceptance

- **Model:** video layer/asset JSON and archive round-trips, exact embedded-byte preservation,
  defaults, invalid references and values, and unchanged loading of version-1/version-2 files.
- **Timing:** start/end boundaries, source offset, looping, pause/resume, discontinuous seeks,
  rate zero, reverse/auto-reverse, group visibility, volume keyframes, and master/layer mute.
  Scheduling tests run without native media.
- **Lifecycle:** independent players for two instances of one asset; no duplicate soundtrack;
  reuse across visual edits; cancellation and stale callbacks; cleanup after source changes,
  document replacement, skin/view removal, and disposal.
- **Editor:** import success/refusal/cancellation, no partial edits on failure, asset placement,
  selection/geometry, inspector edits, trimming/slipping, copy/paste, undo/redo, save/reopen,
  and preservation of the mute behavior for picture versus sound.
- **Export:** unsupported-video warnings, no video bytes in Lottie output, and continued
  export of supported neighboring layers.
- **Native integration:** explicitly run an opt-in test with a real JavaFX media backend and
  the original fixture, checking decoded picture changes, seeking, pause/resume, playback
  completion, and synchronized audio state. Report codec/device limitations explicitly;
  a successful headless scheduling test is not evidence that native video decoding works.
- Run the repository's affected tests and portable verification on JDK 27. Keep codec-dependent
  native checks distinct from ordinary headless tests.

The feature is complete only when an imported clip survives saving and reopening, displays
and plays with sound in both the editor and an embedded `AnimationView`, responds to timeline
edits and mute controls, and releases its media resources.

## 7. Out of scope

- Linked files, remote URLs, streaming, or fetching external media referenced by a project.
- FFmpeg or other new codec/transcoding runtime dependencies.
- Frame-accurate seeking, guaranteed smooth reverse playback, or sample-accurate synchronization.
- Video encoding/export, camera capture, recording, or a general-purpose video editor.
- Thumbnail strips, extracted-video waveforms, and automatic soundtrack extraction into a
  separate audio layer.
- Changes to the existing audible-scrubbing behavior of audio-only layers.
