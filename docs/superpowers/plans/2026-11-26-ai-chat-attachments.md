# AI Chat Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-message image attachments to AI Chat, allowing users to select project images or attach external image/SVG files for the AI to inspect and reuse in edits.

**Architecture:** Keep AI Generation's `AiAttachment` flow intact and add an AI Chat attachment value that can represent either an external attachment or a reference to an existing project image. Thread these values through the edit provider request and attachment-aware fragment parser, then apply new asset bytes with the animation edit through `EditorDocument.execute(command, newAssets)`. A separate composer view owns selection, file drops, previews, removal, and one-turn lifetime.

**Tech Stack:** Java 27, JavaFX, Maven, JUnit 6, TestFX, existing GemsFX/JavaFX UI patterns.

**Spec:** `docs/superpowers/specs/2026-11-26-ai-chat-attachments-design.md`

## Global Constraints

- Support project `IMAGE` assets and external PNG, JPEG, GIF, and SVG files only.
- At most five attachments per message; external input files are limited to 5 MB each.
- Provider previews are PNG and at most 200 KB each.
- Send only explicitly selected visuals; never send local paths or unselected project asset data.
- Attachment bytes are current-message-only; later turns must reattach them.
- New external assets are embedded only when referenced by an accepted edit and are applied atomically with the edit.
- The editor targets Java 27; do not add dependencies.
- Keep all changes uncommitted for the user.

---

## File map

- Create `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiChatAttachment.java`: immutable request attachment metadata, project-image preview creation, and wrapping of existing `AiAttachment` files.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiAnimation.java`: resolve request-local attachment IDs, reuse selected project asset IDs, and return newly imported external asset bytes.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiEdits.java`: parse add-fragment attachments, collect new assets in `Result`, and disallow attachment references outside the current request manifest.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiProviderClient.java`: add image previews and a manifest to edit requests, using existing provider image content support.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiEditService.java` and `AiVisualReview.java`: carry attachments through validation/repair and make new candidate asset bytes available to visual review.
- Create `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/AiChatAttachmentsView.java`: chat-specific project asset picker, file picker/drop target, attachment chips, error display, and clear behavior.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/AiChat.java`: connect the composer to the edit service, show attached names in the user message, clear per-turn attachment state, and apply new bytes atomically.
- Modify `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/MainWindow.java`: pass the current `AnimationFile` and attachment list from the chat request to `AiEditService`.
- Modify `animatorfx-editor/src/main/resources/com/dlsc/animatorfx/editor/editor.css`: style the chat attachment controls using the existing `ai-attachment-*` styles where applicable.
- Extend `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiAnimationTest.java`, `AiEditsTest.java`, `AiProviderClientTest.java`, and `AiEditServiceTest.java` for model/provider/service behavior.
- Create `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ui/AiChatAttachmentsViewTest.java` and extend `AiChatTest.java` for composer and end-to-end behavior.
- Extend `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/doc/EditorDocumentTest.java` only if its existing `executeWithAssetsAddsAssetsAndLayersAsOneUndoStep` test needs additional coverage for byte restoration on redo.

## Task 1: Add attachment-aware edit parsing and result assets

**Files:**
- Create: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiChatAttachment.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiAnimation.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiEdits.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiAnimationTest.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiEditsTest.java`

**Interfaces:**
- Define `AiChatAttachment(String name, AiAttachment.Kind kind, double width, double height, byte[] preview, Asset projectAsset, AiAttachment external)` with exactly one of `projectAsset` and `external` non-null. Project attachments retain the full `Asset` metadata; external attachments retain their validated source and optional imported SVG document.
- Provide public factories `projectImage(Asset asset, byte[] bytes)` and `external(AiAttachment attachment)` because the chat view is in a separate package; both throw `IOException` for invalid input or a preview over 200 KB. The project factory validates/decodes the source bytes and creates the same bounded PNG preview used by external attachments.
- Provide `AiChatAttachment.id(int index)` by delegating to `AiAttachment.id(index)` so all requests use the established `asset1` … `asset5` IDs.
- Provide `AiAnimation.parseChat(String json, List<AiChatAttachment> attachments) -> AnimationFile`; preserve existing `parse(String)` and `parse(String, List<AiAttachment>)` APIs for generation.
- Extend `AiEdits.Result` with `Map<Asset, byte[]> newAssets`; preserve its current two- and three-argument constructors for existing callers.
- Add `AiEdits.parse(String json, Animation before, List<AiChatAttachment> attachments)`. Preserve `parse(String, Animation)` and delegate it to the new overload with an empty attachment list.

- [ ] **Step 1: Write a failing parser test for reusing a selected project image**

Add a valid v1 fragment with one `image` layer referencing `asset1`, then parse an `add` operation with a selected project attachment. Assert the resulting `ImageLayer.assetId()` equals the existing project asset ID and `result.newAssets()` is empty. Make the existing `AiAnimationTest.document(...)`, `layer(...)`, and `vec(...)` fixture helpers package-visible, then build this local helper in `AiEditsTest`:

```java
private static Map<String, Object> imageFragment(String assetId) {
    return AiAnimationTest.document(AiAnimationTest.layer("image", null,
            Map.of("kind", "image", "asset", assetId, "size", AiAnimationTest.vec(40, 40))));
}
```

In the test, use the existing `AiEditsTest.operation(...)` and `response(...)` helpers:

```java
Map<String, Object> add = operation("add", null, null, JsonWriter.write(imageFragment("asset1")), null);
AiEdits.Result result = AiEdits.parse(response(List.of(add)), before,
        List.of(AiChatAttachment.projectImage(projectAsset, pngBytes)));

ImageLayer image = assertInstanceOf(ImageLayer.class, result.animation().layers().getLast());
assertEquals(projectAsset.id(), image.assetId());
assertTrue(result.newAssets().isEmpty());
```

- [ ] **Step 2: Run the focused test and confirm it fails because chat attachments are not supported**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiEditsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation or assertion failure specifically because `AiChatAttachment` and the attachment-aware `AiEdits.parse` overload do not yet exist.

- [ ] **Step 3: Implement chat attachment values and project-image parsing**

Add the immutable attachment wrapper and route `AiAnimation.parseChat` through the existing conversion/validation machinery. For project images, produce `ImageLayer` values referencing the existing project asset ID and do not return new bytes. Keep IDs request-local using `AiAttachment.id(index)`.

- [ ] **Step 4: Add failing tests for external raster, SVG, unused, and invalid references**

Extend `AiEditsTest` so an external raster referenced from an `add` fragment is present in `result.newAssets()` and the resulting animation includes its metadata. Verify an unreferenced external attachment adds neither metadata nor bytes. In `AiAnimationTest`, verify an SVG attachment becomes editable vector/group content, an unknown ID fails with the attachment path, and raster/vector mismatches fail. Reuse the local `imageFragment(...)` fixture helper added for the preceding test.

```java
Map<String, Object> add = operation("add", null, null, JsonWriter.write(imageFragment("asset1")), null);
AiEdits.Result result = AiEdits.parse(response(List.of(add)), scene(),
        List.of(AiChatAttachment.external(rasterAttachment)));

assertEquals(1, result.newAssets().size());
assertArrayEquals(rasterAttachment.bytes(), result.newAssets().values().iterator().next());
assertEquals(1, result.animation().assets().size());
```

- [ ] **Step 5: Run the focused tests and confirm the new cases fail for missing behavior**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiAnimationTest,AiEditsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failures identify absent chat attachment resolution/result-asset behavior; existing generation attachment tests remain unchanged.

- [ ] **Step 6: Implement result asset collection for add operations**

In `AiEdits.parse`, pass the current attachment manifest into each added-fragment parse, merge fragment asset metadata into the candidate animation without duplicating existing IDs, and collect only referenced external asset bytes. Copy the `newAssets` map and byte arrays in the `Result` compact constructor. Keep the existing parse path attachment-free by default.

- [ ] **Step 7: Re-run parser tests and verify both old and new behavior**

Run the same `AiAnimationTest,AiEditsTest` Maven command. Expected: all tests pass, including existing no-attachment behavior and the new selected-project/external-asset cases.

## Task 2: Send images through edit requests and preserve candidate bytes

**Files:**
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiProviderClient.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiEditService.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiVisualReview.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ai/AiEdits.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiProviderClientTest.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ai/AiEditServiceTest.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ui/AiChatTest.java` (update its `AiEditService.Request` lambda)

**Interfaces:**
- Add `AiProviderClient.edit(Provider provider, String model, String apiKey, String context, List<AiChatAttachment> attachments)`; retain the text-only overload and delegate with an empty list.
- Change `AiEditService.Request` to `CompletableFuture<String> send(AiProviderClient.Provider provider, String model, String key, String context, List<AiChatAttachment> attachments)`; each `AiRepair` attempt must resend the same current-turn previews and manifest.
- Add `AiEditService.edit(AiSettings settings, AnimationFile file, List<String> selection, double playhead, List<AiEdits.Message> history, String prompt, List<AiChatAttachment> attachments)`. Retain existing overloads and delegate with no attachments.
- Visual review receives candidate asset bytes in its `AnimationFile` and returns a refined `AiEdits.Result` that retains the candidate's `newAssets`.

- [ ] **Step 1: Write a failing provider test for image previews and manifest in an edit request**

Use the existing fake HTTP server and a synthetic `AiChatAttachment`; for both providers, assert that the request has one image block containing its preview and includes `asset1` in its instructions and the attachment name only in structured user-message metadata. Names must be treated as metadata, not instructions. Add an empty-list case asserting the request says no attachments are available and has no image block.

- [ ] **Step 2: Run `AiProviderClientTest` and confirm the missing overload/request behavior**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiProviderClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the new request assertions fail because edit requests currently send text only.

- [ ] **Step 3: Add the multimodal edit request**

Encode only `AiChatAttachment.preview()` values, add them through the existing `ImageContent` path, and append a manifest that says only listed request attachments may be referenced. Update `AiEdits.instructions()` to forbid IDs absent from the current manifest and to say that attachments mentioned in conversation history are unavailable. Keep all existing request size, timeout, and log-redaction behavior.

- [ ] **Step 4: Write a failing service test for attachment-aware validation/repair**

Exercise the `AiEditService` request seam with an edit response whose add-fragment references `asset1`; assert the service validates it using the current attachment list and returns the expected new asset bytes. Exercise the repair path once and assert both initial and correction requests receive the same attachment list. Update existing four-argument `AiEditService.Request` lambdas in `AiEditServiceTest` and `AiChatTest` to accept the fifth list argument.

- [ ] **Step 5: Run the service tests and confirm attachment propagation is missing**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiEditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the test fails because service request and parse callbacks currently lack attachment arguments.

- [ ] **Step 6: Thread attachments through service validation and repair**

Use the attachment-aware `AiEdits.parse` callback and preserve the same attachment list for every bounded repair attempt. Existing overloads pass `List.of()` so all existing callers remain text-only.

- [ ] **Step 7: Add a visual-review regression test for new asset bytes**

Supply a candidate with one new image asset and bytes. Assert the review renderer receives an `AnimationFile` containing both the candidate animation metadata and those bytes; assert the refined result retains `candidate.newAssets()`. Visual review itself continues to send its rendered frames, not the original attachment previews.

- [ ] **Step 8: Preserve candidate bytes through visual review**

Merge candidate new bytes with the original file bytes when constructing the review render input. When review succeeds, preserve the candidate `newAssets` map in the returned result; a review with no operations must not discard the attachment-backed candidate.

- [ ] **Step 9: Run provider, service, and visual-review tests**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiProviderClientTest,AiEditServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests pass. Fake provider servers must use the existing dedicated virtual-thread executor rather than block `ForkJoinPool.commonPool`.

## Task 3: Build the chat attachment tray and project image picker

**Files:**
- Create: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/AiChatAttachmentsView.java`
- Modify: `animatorfx-editor/src/main/resources/com/dlsc/animatorfx/editor/editor.css`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ui/AiChatAttachmentsViewTest.java`

**Interfaces:**
- `AiChatAttachmentsView.getAttachments() -> ObservableList<AiChatAttachment>` returns a read-only list in display/send order.
- `AiChatAttachmentsView.setProject(AnimationFile file)` updates the source list shown by the project-image picker without attaching anything automatically.
- `AiChatAttachmentsView.addFiles(List<Path> files)` delegates validation to `AiAttachment.read` and `AiChatAttachment.external`, enforces the five-item total across both source types, and reports either factory's `IOException` inline.
- `AiChatAttachmentsView.addProjectImage(Asset asset, byte[] bytes)` is the picker selection action; it previews and adds only that image, showing factory `IOException` inline.
- `AiChatAttachmentsView.addAttachment(AiChatAttachment attachment)` appends a validated item if capacity remains; both file/project flows call this same method.
- `AiChatAttachmentsView.clear()` removes all attachments and errors.

- [ ] **Step 1: Write failing tests for the five-item limit and removal**

Create a view, add five valid synthetic attachments through `addAttachment(...)`, attempt a sixth, and assert the list remains at five and an inline error is visible. Remove one chip and assert the list returns to four and the error clears.

- [ ] **Step 2: Run the focused view test and confirm the class/behavior is absent**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiChatAttachmentsViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the chat attachment view and its test do not yet exist.

- [ ] **Step 3: Implement the tray for external files**

Use the generation view as visual precedent, but keep a separate chat view so generation behavior is unaffected. Add file picking, file drag/drop, preview/name chips, remove controls, inline errors, and the shared five-item count.

- [ ] **Step 4: Add failing tests for explicit project image selection**

Provide an `AnimationFile` with one valid `IMAGE`, one `FONT`, and one `AUDIO` asset. Assert the project picker offers only the image asset, and selecting it creates one chat attachment with a preview while no other project asset is attached or exposed.

- [ ] **Step 5: Implement project image selection and clear behavior**

Build project choices from `AnimationFile.animation().assets()` and `AnimationFile.assets()` using only image metadata and byte availability; decode and validate only upon explicit selection. Refresh choices and selected previews on same-document file-content changes, and revalidate every project attachment against the exact submission snapshot before sending. Project attachments, including SVGs, use the existing image asset ID and a bounded PNG preview; do not copy source paths into attachment metadata sent to the provider.

- [ ] **Step 6: Style the attachment tray and validate the focused view tests**

Add compact composer styles in `editor.css`, reusing the existing attachment chip classes where their semantics match. Run `AiChatAttachmentsViewTest`; expected: all file, project-image, limit, removal, and clear cases pass.

## Task 4: Integrate per-message attachments into AI Chat

**Files:**
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/AiChat.java`
- Modify: `animatorfx-editor/src/main/java/com/dlsc/animatorfx/editor/ui/MainWindow.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/ui/AiChatTest.java`
- Test: `animatorfx-editor/src/test/java/com/dlsc/animatorfx/editor/doc/EditorDocumentTest.java`

**Interfaces:**
- Change `AiChat.Request.edit(AiSettings settings, AnimationFile file, List<String> selection, double time, List<AiEdits.Message> history, String prompt, List<AiChatAttachment> attachments)`; update the `MainWindow` request lambda to pass `workspace.getActiveDocument().getFileContent()` and the supplied attachment list to `AiEditService.edit(...)`.
- `AiChat` owns one `AiChatAttachmentsView`; on active-document changes it clears the draft attachments and updates the project's available images.
- On submit, snapshot the current attachment list, show attachment names in the user transcript, pass the immutable list to the request, and clear the composer so subsequent turns cannot reuse the previous turn's bytes.
- On accepted response, call `EditorDocument.execute(Commands.setDocument("AI Edit", ignored -> result.animation()), result.newAssets())`.

- [ ] **Step 1: Write a failing chat test for current-turn-only submission**

Extend `AiChatTest.Fixture` with `List<List<AiChatAttachment>> submitted` and a request lambda that appends `List.copyOf(attachments)` before returning a completed empty edit result. Attach one project image through the view, send a prompt, wait for FX events, and assert the request receives it, the composer is empty, and the transcript shows its name. Send a follow-up and assert its attachment list is empty.

```java
AiChatAttachmentsView view = (AiChatAttachmentsView) fixture.chat.lookup("#ai-chat-attachments");
view.addProjectImage(projectAsset, pngBytes);
fixture.send("Use this image");
WaitForAsyncUtils.waitForFxEvents();
assertEquals(1, submitted.getFirst().size());
assertTrue(fixture.transcript().contains("logo.png"));

fixture.send("Make it move");
WaitForAsyncUtils.waitForFxEvents();
assertTrue(submitted.getLast().isEmpty());
```

- [ ] **Step 2: Run `AiChatTest` and confirm current chat has no attachment lifecycle**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiChatTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation or assertion failure because the request seam and composer are currently text-only.

- [ ] **Step 3: Add the tray to the composer and pass a file snapshot**

Place the attachment view beside the chat input controls, update its project choices from the active document, and extend `AiChat.Request` to pass `target.getFileContent()` plus a copied attachment list. Preserve the existing animation snapshot stale-result guard.

- [ ] **Step 4: Clear sent attachments and preserve transcript-only metadata**

On successful submission, add the user's text and attachment names to the transcript, clear the attachment view, and pass image bytes only through the request. Clear draft attachments on active-document changes. Prior conversation messages remain text-only; system instructions must explicitly say that images referenced in history are unavailable unless included in the current request manifest.

- [ ] **Step 5: Write a failing end-to-end test for atomic asset insertion**

Have the request return an attachment-aware `AiEdits.Result` containing one new `IMAGE` asset, bytes, and an `ImageLayer` referencing it. Assert the document contains both after completion; one undo removes both layer and asset bytes; redo restores both.

```java
assertEquals(1, fixture.document.getAnimation().assets().size());
assertArrayEquals(imageBytes, fixture.document.getFileContent().assets().get(asset.id()));
fixture.document.undo();
assertTrue(fixture.document.getAnimation().assets().isEmpty());
assertTrue(fixture.document.getFileContent().assets().isEmpty());
fixture.document.redo();
assertArrayEquals(imageBytes, fixture.document.getFileContent().assets().get(asset.id()));
```

- [ ] **Step 6: Apply the result through the atomic asset-aware command**

Pass `result.newAssets()` to `EditorDocument.execute` with the same `setDocument` command that applies `result.animation()`. Keep the existing stale-result, cancellation, failure, feedback, and activity behavior unchanged.

- [ ] **Step 7: Verify document switching, cancellation, failure, and no-attachment behavior**

Extend `AiChatTest` to assert that switching active documents clears draft attachments, cancelling/failing a request does not mutate the document, and an ordinary text-only edit still submits an empty attachment list. Keep the existing request-cancellation and stale-project tests passing.

- [ ] **Step 8: Run chat and document tests**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiChatTest,AiChatAttachmentsViewTest,EditorDocumentTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all chat lifecycle, UI, and atomic undo/redo tests pass.

## Task 5: Run the complete focused regression set

**Files:** No new files; validate all implementation tasks together.

- [ ] **Step 1: Run all affected editor tests in one reactor invocation**

Run:

```bash
JAVA_HOME="/Users/lemmi/Library/Java/JavaVirtualMachines/azul-27.0.1.1/Contents/Home" \
  mvn -pl animatorfx-editor -am \
  -Dtest=AiAnimationTest,AiEditsTest,AiProviderClientTest,AiEditServiceTest,AiVisualReviewTest,AiChatAttachmentsViewTest,AiChatTest,EditorDocumentTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Maven exits successfully and every selected test class reports zero failures and errors.

- [ ] **Step 2: Check whitespace and inspect the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the planned source/test changes and the spec/plan files are present. Leave the working tree uncommitted.
