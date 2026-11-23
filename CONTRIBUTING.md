# Contributing to AnimatorFX

Bug reports, documentation improvements and focused pull requests are welcome. By participating,
you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Contributions are licensed under
[Apache-2.0](LICENSE).

## Reporting issues

Search [existing issues](https://github.com/dlemmermann/AnimatorFX/issues) first. Include the
AnimatorFX version or commit, operating system and architecture, JDK/JavaFX versions, reproduction
steps, and expected versus actual behavior. A small `.afx`, SVG, Lottie or FXML example is useful;
remove personal, proprietary and third-party material you cannot share.

For suspected vulnerabilities, follow [SECURITY.md](SECURITY.md), not a public issue.
Discuss substantial features or file-format changes in an issue before implementing them.

## Building

Install JDK 27 and set `JAVA_HOME` to it. The Maven wrapper downloads the pinned Maven distribution;
no separate Maven installation is needed.

```bash
./mvnw -B clean install
./mvnw -pl animatorfx-editor javafx:run -Danimatorfx.configDir=/path/to/scratch/config
./mvnw -Pjdeploy -pl animatorfx-editor -am package
```

On Windows, use `mvnw.cmd` in place of `./mvnw`. On Ubuntu 24.04, install `libgtk-3-0t64`
and `libasound2t64` for JavaFX. CI also installs `xvfb` and `xauth` and runs the portable test
suite through `xvfb-run`, providing a virtual display alongside the headless test backends.
All workflows run on `ubuntu-latest`. CI verifies the portable jDeploy payload; releases distribute
the editor through jDeploy rather than building platform-specific application images or archives.

CI excludes the `native-audio` tests: hosted runners do not provide a reliable audio device, and
JavaFX's native media backend can fail or crash the test JVM there. Deterministic audio scheduling,
cue lifecycle and playback-controller tests still run in CI. Native media tests remain
included in local builds; on a machine with a working audio device, run them alone with:

```bash
./mvnw -B -ntp -pl animatorfx-player -am -Dgroups=native-audio test
```

For a local build without an audio device, use `-DexcludedGroups=native-audio` as CI does.

Video scheduling, asset and editor tests are portable. Real video decoding is opt-in and needs
a display and working JavaFX media backend. With JDK 27, run the original red/blue fixture
through both the player and editor:

```bash
./mvnw -B -ntp -pl animatorfx-editor -am \
  '-Dtest=NativeVideoPlaybackTest,NativeVideoImportTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Danimatorfx.test.video="$PWD/animatorfx-player/src/test/resources/com/dlsc/animatorfx/player/video/red-blue.mp4" \
  '-Dsurefire.jvm.args=--enable-native-access=javafx.graphics,javafx.media --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED --add-exports javafx.graphics/com.sun.javafx.application=ALL-UNNAMED --add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED --add-reads org.kordamp.ikonli.core=ALL-UNNAMED -Danimatorfx.nativeVideo=true' test
```

These checks inspect decoded colors, muted picture progression, soundtrack state, independent
instances, cleanup and editor import/save/reopen. The audio-only MP4 refusal case uses macOS
`afconvert` and is skipped when unavailable. The checked-in video is original; its provenance
includes the development-only generation command. Building or running AnimatorFX needs no FFmpeg.

## Pull requests

- Branch from the repository's current default branch. Keep changes focused and explain their purpose.
- Add regression tests for changed behavior; run the affected tests before submitting.
- Editor JavaFX tests use TestFX with JavaFX's bundled Headless backend; the other JavaFX tests
  use TestFX/Monocle. Keep tests silent and use temporary session directories; never access a
  user's real editor configuration.
- Keep `animatorfx-model` independent of JavaFX. Model and player target Java 21; tooling targets
  Java 27. The sample app must remain independent of the tooling.
- Keep model records immutable. Editor document edits go through commands and remain undoable.
- JavaFX property accessors are `final`; property Javadoc belongs on `xxxProperty()` only.
- Use the looked-up colors in `editor.css`, and check UI changes in both themes.
- Preserve `.afx` backward compatibility and update documentation for API or format changes.
- Do not commit generated `target/` files, IDE settings, credentials or signing keys.

See the [README](README.md#development) for targeted test commands and architecture notes.
Maintainers handle publication using the [release guide](docs/RELEASING.md).
