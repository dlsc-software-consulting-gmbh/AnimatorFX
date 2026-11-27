# AI Generation Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users attach PNG, JPEG, GIF and SVG files to an initial AI generation. The AI may place
them as image or vector layers, or use them only as design references.

**Architecture:** An `AiAttachment` value is created on the FX thread from a file. It holds the original
bytes, the decoded size, a small PNG preview and, for SVG, the imported document. The generation
dialog collects attachments in an `AiAttachmentsView`. They flow through `AiNotifications` →
`AiActions.Generator` → `AiGenerationService` / `AiProviderClient.generate`. The previews are sent
as vision images and a manifest is appended to the instructions. `AiAnimation.parse(json, attachments)`
accepts the new `image` and `vector` content kinds and expands the referenced attachments into an
`ImageLayer` + `Asset`, or an imported, fitted `GroupLayer`.

**Tech Stack:** Java 27, JavaFX 27, langchain4j, GemsFX, JUnit 5 + TestFX (Monocle headless).

**Spec:** `docs/superpowers/specs/2026-11-25-ai-generation-attachments-design.md`

## Global Constraints

- Supported types: `png`, `jpg`, `jpeg`, `gif`, `svg` (by extension, then verified by decoding). WebP is rejected with "WebP is not supported."
- At most 5 attachments; each file at most 5 MB (5 * 1024 * 1024 bytes).
- Attachment ids are `asset1` … `asset5` in list order.
- Preview: PNG, longest side ≤ 768 px; if > 200,000 bytes re-encode at 512, 384, 256 px.
- Chat edits do not get attachments; `AiAnimation.parse(json)` keeps working with no attachments, so any `image`/`vector` reference there fails as an unknown attachment.
- JavaFX property methods are final; Javadoc only on public API.
- Build/test: `JAVA_HOME="$(/usr/libexec/java_home -v 27)" MAVEN_OPTS='-Duser.language=en -Duser.country=US' mvn -q -o -pl animatorfx-editor -Dtest=<Tests> -Dsurefire.failIfNoSpecifiedTests=false clean test`

---

### Task 1: `AiAttachment` value and reader

**Files:**
- Create: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiAttachment.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiPreviewFrames.java` (extract `static byte[] png(Image image)` from the inline frame encoding and reuse it)
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiAttachmentTest.java`

**Interfaces:**
- Produces:
  ```java
  public record AiAttachment(String name, Kind kind, String extension, byte[] bytes,
                             double width, double height, byte[] preview, AnimationFile vector) {
      public enum Kind { RASTER, VECTOR }
      public static final int MAX_COUNT = 5;
      public static final int MAX_BYTES = 5 * 1024 * 1024;
      public static String id(int index)            // "asset" + (index + 1)
      public static AiAttachment read(Path file) throws IOException   // FX thread only
  }
  ```
  `IOException` messages are user-facing ("logo.webp: WebP is not supported.", "big.png: larger than 5 MB.",
  "x.txt: unsupported file type. Use PNG, JPEG, GIF or SVG.", "bad.png: not a readable image.").

- [ ] Write tests (FX thread via `Platform.startup`/`runLater` like `AiActionsTest`): a generated 2000×1000 PNG reads as RASTER with width 2000 and a preview whose longest side ≤ 768 and is PNG; a small SVG (`<svg width="120" height="40">…`) reads as VECTOR with width 120, height 40, non-null `vector()` and a PNG preview; `.webp`, `.txt`, oversize (5 MB + 1, written sparse via `RandomAccessFile.setLength`) and corrupt PNG are rejected with the messages above.
- [ ] Run, expect compile failure.
- [ ] Implement: check extension, size, then decode. Raster: `new Image(new ByteArrayInputStream(bytes))`; error/zero size → not readable. Preview: `new Image(stream, s, s, true, true)` for s in 768, 512, 384, 256 until `png(image).length ≤ 200_000` (always keep the last). Vector: `SvgImporter.read(stream, name)`; width/height from the animation; render the frame at time 0 with `Renderer` into a snapshot scaled to the same sizes.
- [ ] Run, expect pass.

### Task 2: Schema kinds and expansion in `AiAnimation`

**Files:**
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiAnimation.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiAnimationTest.java`

**Interfaces:**
- Consumes: `AiAttachment` (Task 1).
- Produces: `public static AnimationFile parse(String json, List<AiAttachment> attachments)`; `parse(json)` delegates with `List.of()`.

- [ ] Write tests: a document with a layer whose content is `{"kind":"image","asset":"asset1","size":{"x":100,"y":50}}` and a raster attachment yields an `ImageLayer` (100×50) whose asset is in `animation().assets()` (Kind IMAGE, path `assets/<id>.png`) with the original bytes in `file.assets()`. A `vector` reference yields a `GroupLayer` with the AI layer's id containing one fitted wrapper group (scale = min(sx/w, sy/h), centred) whose descendants all have the AI layer's in/out points. An unreferenced attachment is not embedded. An unknown id (`asset9`) fails with path `$.layers[0].content.asset`. A raster id used as `vector` (and vice versa) fails at the same path. `parse(json)` with an image reference fails as unknown.
- [ ] Run, expect failure.
- [ ] Implement: add both kinds to the `content` anyOf (`asset` = `string(1, 16, "Attachment id from the manifest, e.g. asset1.")`, `size` = `ref("size")`); relax the descriptions ("no URLs; attachments only by id"). In `Conversion` handle the kinds before `fill`. Collect assets in a `LinkedHashMap` keyed by attachment index so repeated references share one `Asset` (UUID id).
- [ ] Run `AiAnimationTest` (includes the schema-shape checks), expect pass.

### Task 3: Provider request with previews and manifest

**Files:**
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiProviderClient.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiProviderClientTest.java`

**Interfaces:**
- Produces: `public CompletableFuture<String> generate(Provider, String model, String apiKey, String prompt, Map<String,Object> schema, AiUsage usage, List<AiAttachment> attachments)`; the existing overloads delegate with `List.of()`.
- Produces: `static String manifest(List<AiAttachment> attachments)`.

- [ ] Write tests with the existing mock server: an OpenAI generation with two attachments sends two `input_image` parts and its instructions contain `asset1: logo.svg (vector, 120×40 px)` and `asset2: photo.png (raster, …)`. Without attachments the instructions say that image and vector content are unavailable.
- [ ] Run, expect failure.
- [ ] Implement: instructions `INSTRUCTIONS + manifest(attachments)`; images = Base64 of each preview. Change INSTRUCTIONS "Do not include image, audio, video, or external assets." → "Do not include audio, video, or external assets; use images only through the provided attachment ids."
- [ ] Run, expect pass.

### Task 4: Service and job wiring

**Files:**
- Modify: `AiGenerationService.java` (`generate(AiSettings, String, List<AiAttachment>)`; parse with attachments)
- Modify: `ui/AiActions.java` (`Generator.start(AiSettings, String, List<AiAttachment>, AiUsage)`)
- Modify: `ui/AiNotifications.java` (`start(settings, prompt, attachments, generator)`; Job shows `Attachments: N` in `providerDetails` when N > 0)
- Modify: `ui/MainWindow.java` (pass the attachments to `aiClient.generate` and `generator.generate`)
- Modify the existing tests to the new signatures; add an `AiActionsTest` case for the "Attachments: 2" statistics line.

- [ ] Write the failing statistics test, run, implement, run.

### Task 5: Dialog attachments strip

**Files:**
- Create: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/AiAttachmentsView.java`
- Modify: `ui/AiActions.java`, `editor.css`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ui/AiAttachmentsViewTest.java`

**Interfaces:**
- Produces: `final class AiAttachmentsView extends VBox` with `ObservableList<AiAttachment> getAttachments()` (read-only), `void add(List<Path> files)`, `String getError()` (via `#ai-attachment-error` label). Ids: `#ai-attachments`, `#ai-add-attachments`, chips `.ai-attachment-chip` with a thumbnail, the name and a remove button `.ai-attachment-remove`.

- [ ] Write tests: adding two valid files shows two chips; removing one removes it; a sixth file is rejected ("At most 5 attachments."); a WebP shows the WebP message and nothing is added; dropping files (simulate via `add`) works.
- [ ] Run, expect failure. Implement (FileChooser with an extension filter; drag over/drop on the view accepting files). Add it to the generate dialog below the description; pass `List.copyOf(view.getAttachments())` to `notifications.start`.
- [ ] Run `AiAttachmentsViewTest,AiActionsTest`, expect pass; take a screenshot.

### Task 6: Verification, docs, commit

- [ ] Full editor suite with `clean test`; 0 failures, 0 CSS warnings.
- [ ] Update `docs/USER_MANUAL.md` (AI generation section) with attachments.
- [ ] `git diff --check`, commit, push `feature/ai-generation`.
