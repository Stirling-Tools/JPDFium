# Publishing JPDFium to Maven Central

This guide walks through publishing JPDFium artifacts to Maven Central via the
Sonatype **Central Portal** (the replacement for legacy OSSRH, which was sunset
on 2025‑06‑30). It assumes the state of the repo as of the first release cut —
publishing skeleton is already wired in `buildSrc/` and the root
`build.gradle.kts`, but several pre‑flight items still need doing before the
first successful upload.

> **TL;DR** — one‑time namespace verification + GPG key + Central Portal token,
> then `./gradlew publishAllToCentralPortal` from a CI matrix that has each
> platform's prebuilt native libraries copied into
> `jpdfium-natives-<platform>/src/main/resources/natives/<platform>/`.

---

## 1. What gets published

The following coordinates will land under group `com.stirling` (from
`build.gradle.kts` line 2). Note that the Maven `groupId` is independent of
the Java package name (`stirling.software.jpdfium.*`) — the coordinate is
`com.stirling` because that is the namespace Sonatype has verified for us.
All modules share a single version property `jpdfium.version` (defined in
`gradle.properties`).

| Artifact                                  | Kind          | Notes                              |
|-------------------------------------------|---------------|------------------------------------|
| `com.stirling:jpdfium`                    | jar           | Pure Java 25 FFM bindings          |
| `com.stirling:jpdfium-bom`                | pom (BOM)     | Version constraints for all below  |
| `com.stirling:jpdfium-spring`             | jar           | Spring Boot starter                |
| `com.stirling:jpdfium-natives-linux-x64`  | jar (natives) | Resource‑only, `.so` + deps        |
| `com.stirling:jpdfium-natives-linux-arm64`| jar (natives) | Resource‑only, `.so` + deps        |
| `com.stirling:jpdfium-natives-darwin-x64` | jar (natives) | Resource‑only, `.dylib` + deps     |
| `com.stirling:jpdfium-natives-darwin-arm64`| jar (natives)| Resource‑only, `.dylib` + deps     |
| `com.stirling:jpdfium-natives-windows-x64`| jar (natives) | Resource‑only, `.dll` + deps       |

Downstream consumers add **one** native classifier matching their runtime, plus
the pure‑Java `jpdfium` jar (typically via the BOM).

---

## 2. Pre‑flight checklist (one‑time)

### 2.1 Claim the `com.stirling` namespace on Central Portal

Already done — `com.stirling` is verified under the Stirling Tools account.
If you ever need to add a second namespace:

1. Sign in at <https://central.sonatype.com>.
2. **View Namespaces** → **Add Namespace**.
3. Verify via one of:
   - **DNS TXT record** on the apex domain containing the provided code (preferred).
   - **GitHub repo** proof (works well for `io.github.*`).

Until verified, *every* `publish` will fail with `401` or `namespace not owned`.

### 2.2 Generate a Central Portal user token

1. <https://central.sonatype.com> → avatar → **View Account** → **Generate User Token**.
2. You get a `username:password` pair. These are **not** your login credentials —
   they're a scoped token.
3. Store as:
   ```properties
   # ~/.gradle/gradle.properties   (NEVER commit)
   centralPortalUsername=<token user>
   centralPortalPassword=<token pass>
   ```
   Or as env vars `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_PASSWORD` (CI).

### 2.3 Create and publish a GPG signing key

Central Portal requires every artifact signed with PGP, and the public key must
be discoverable on a Ubuntu/MIT/OpenPGP keyserver.

```bash
# Generate (RSA 4096, no expiry or ≥ 5 years)
gpg --full-generate-key

# Export the fingerprint
gpg --list-secret-keys --keyid-format=long

# Publish the public key
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>

# Export armored private key for Gradle in-memory signing
gpg --armor --export-secret-keys <KEY_ID> > signing-key.asc
```

Store in `~/.gradle/gradle.properties`:

```properties
signing.key=<contents of signing-key.asc, newlines escaped as \n OR use a single-line base64>
signing.password=<your GPG passphrase>
```

Or as env vars `GPG_SIGNING_KEY` / `GPG_SIGNING_PASSWORD` (CI). `buildSrc/.../jpdfium.library-conventions.gradle.kts` uses `useInMemoryPgpKeys`, so no keyring file is needed.

---

## 3. Gaps to close in this repo before the first publish

These are live issues that will make the pipeline fail today. Fix each before
running `publish`.

### 3.1 Native binaries are not wired into the resource jars

Each `jpdfium-natives-*` module today is literally:

```kotlin
plugins {
    id("jpdfium.natives-conventions")
}
```

…with an empty `src/`. `NativeLoader.java` expects binaries at
`/natives/<platform>/libjpdfium.<ext>` (+ `libpdfium.<ext>` + a `native-libs.txt`
manifest listing every file to extract). Nothing in the Gradle build copies the
outputs of `native/build-real.sh` / `setup-pdfium.sh` into those resource
directories — so the jars will publish **empty**.

**Fix options:**

- **Option A (recommended):** add a task to `jpdfium.natives-conventions` that
  copies from a conventional location (e.g.
  `native/dist/<platform>/`) into `src/main/resources/natives/<platform>/`
  and regenerates `native-libs.txt` from the directory listing. CI drops binaries
  into `native/dist/<platform>/` on each OS runner before `publish`.
- **Option B:** commit the prebuilt binaries under each module's
  `src/main/resources/natives/<platform>/`. Simpler to publish but bloats the
  git repo and tangles LFS policy.

Example task to add to `jpdfium.natives-conventions.gradle.kts`:

```kotlin
val platform = project.name.removePrefix("jpdfium-natives-")
val distDir = rootProject.file("native/dist/$platform")
val resourceDir = layout.projectDirectory.dir("src/main/resources/natives/$platform")

val stageNatives by tasks.registering(Copy::class) {
    from(distDir)
    into(resourceDir)
    include("libjpdfium.*", "libpdfium.*", "*.so.*", "*.dll", "*.dylib")
}

val writeNativeManifest by tasks.registering {
    dependsOn(stageNatives)
    doLast {
        val out = resourceDir.file("native-libs.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            resourceDir.asFile.listFiles()
                ?.filter { it.isFile && it.name != "native-libs.txt" }
                ?.map { it.name }
                ?.sorted()
                ?.joinToString("\n") ?: ""
        )
    }
}

tasks.named("processResources") { dependsOn(writeNativeManifest) }
```

### 3.2 Native modules are missing sources & javadoc jars

Central Portal **requires** a `-sources.jar` and `-javadoc.jar` alongside every
main jar. `jpdfium.library-conventions` declares `withJavadocJar()` +
`withSourcesJar()`, but `jpdfium.natives-conventions` does not. Publishing will
fail validation with `Missing sources/javadoc for <artifact>`.

**Fix:** in `jpdfium.natives-conventions.gradle.kts`, add:

```kotlin
java {
    withSourcesJar()   // will be empty but satisfies the validator
    withJavadocJar()
}
```

(Empty jars are fine. Central Portal only requires their presence.)

### 3.3 Snapshot repository URL

`jpdfium.library-conventions` points releases at
`https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`
and snapshots at `https://central.sonatype.com/repository/maven-snapshots/`.
The releases URL is correct for the OSSRH Staging API shim. Double‑check the
snapshots URL against the live Central Portal docs on the day you publish —
Sonatype has been iterating on these endpoints.

### 3.4 Version bump

`gradle.properties` has `jpdfium.version=1.0.0-SNAPSHOT`. For a real release:

```bash
./gradlew publishAllToCentralPortal -Pjpdfium.version=1.0.0
```

Or edit `gradle.properties` before tagging. Never publish a non‑`-SNAPSHOT`
version twice — Central Portal is immutable.

### 3.5 BOM coverage

`jpdfium-bom/build.gradle.kts` correctly constrains all publishable modules.
Nothing to fix here, just: **don't add a new publishable module without also
adding it to the BOM** — easy to forget.

---

## 4. Local smoke test (no upload)

Before any real publish, dry‑run to `~/.m2` and inspect the artifacts.

```bash
./gradlew publishToMavenLocal -Pjpdfium.version=1.0.0-test

# Inspect what landed
find ~/.m2/repository/com/stirling -type f \( -name "*.jar" -o -name "*.pom" -o -name "*.asc" \) | sort

# Each published artifact should have:
#   <name>-<version>.jar
#   <name>-<version>-sources.jar
#   <name>-<version>-javadoc.jar
#   <name>-<version>.pom
#   (plus .asc signatures and .md5/.sha1/.sha256/.sha512 checksums)
```

Verify **each** native jar actually contains `natives/<platform>/libjpdfium.*`
(unzip it and look). This is the most common "publish succeeds but consumers
get `NativeNotFoundException`" bug.

---

## 5. Publishing a release

The root build already defines `publishAllToCentralPortal`, which runs
`publishAllPublicationsToCentralPortalRepository` on every module that applies
`maven-publish`, then triggers `finalizePortalDeployment` to POST the deployment
through the OSSRH Staging API.

```bash
# Human-reviewed release (upload → Central Portal UI → manually click Publish)
./gradlew publishAllToCentralPortal -Pjpdfium.version=1.0.0

# Fully automated release (skips the manual review step)
./gradlew publishAllToCentralPortal -Pjpdfium.version=1.0.0 -PautoRelease=true
```

After upload, Sonatype runs validation (signatures, POM metadata, sources/javadoc presence, license, SCM) — 10–30 minutes. If everything passes:

- `user_managed` mode: you go to <https://central.sonatype.com/publishing/deployments> and click **Publish**. Use this the first few times until you trust the pipeline.
- `automatic` mode: artifacts go live on `repo.maven.apache.org/maven2` automatically.

Artifacts typically take another 10–30 minutes to propagate to
`search.maven.org` and be resolvable from consumer builds.

---

## 6. The cross‑platform native build problem

Because each native module ships binaries for a specific OS/arch, you need to
build on five different host environments (or cross‑compile). JPDFium's
`native/setup-pdfium.sh` + `native/build-real.sh` assume a bash environment —
easy on Linux/macOS, works on Windows via MSYS2/git‑bash with care.

The sustainable path is a GitHub Actions release workflow that fans out to a
matrix, uploads each platform's artifacts separately, then does one final
"assemble + sign + publish" job on a single runner.

### 6.1 Minimum viable release workflow

Create `.github/workflows/release.yml`:

```yaml
name: Release to Central Portal

on:
  push:
    tags: ['v*.*.*']
  workflow_dispatch:
    inputs:
      version:
        required: true

permissions:
  contents: read

jobs:
  build-natives:
    strategy:
      fail-fast: false
      matrix:
        include:
          - { platform: linux-x64,     runner: ubuntu-latest,       artifact-glob: 'libjpdfium.so libpdfium.so *.so.*' }
          - { platform: linux-arm64,   runner: ubuntu-24.04-arm,    artifact-glob: 'libjpdfium.so libpdfium.so *.so.*' }
          - { platform: darwin-x64,    runner: macos-13,            artifact-glob: 'libjpdfium.dylib libpdfium.dylib' }
          - { platform: darwin-arm64,  runner: macos-14,            artifact-glob: 'libjpdfium.dylib libpdfium.dylib' }
          - { platform: windows-x64,   runner: windows-latest,      artifact-glob: 'jpdfium.dll pdfium.dll' }
    runs-on: ${{ matrix.runner }}
    steps:
      - uses: actions/checkout@v4
      - name: Build PDFium + bridge
        shell: bash
        run: |
          ./gradlew buildPdfium buildRealBridge
      - name: Stage binaries
        shell: bash
        run: |
          mkdir -p native/dist/${{ matrix.platform }}
          # paths below depend on build-real.sh output — adjust
          cp native/build/*.{so,dylib,dll} native/dist/${{ matrix.platform }}/ || true
      - uses: actions/upload-artifact@v4
        with:
          name: natives-${{ matrix.platform }}
          path: native/dist/${{ matrix.platform }}/

  publish:
    needs: build-natives
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '25' }
      - uses: actions/download-artifact@v4
        with: { path: native/dist, pattern: natives-* }
      - name: Flatten artifact layout
        run: |
          # download-artifact puts each in natives/dist/natives-<platform>/ — move up
          for d in native/dist/natives-*; do
            p=${d##*natives-}
            mv "$d" "native/dist/$p"
          done
      - name: Publish
        env:
          CENTRAL_PORTAL_USERNAME: ${{ secrets.CENTRAL_PORTAL_USERNAME }}
          CENTRAL_PORTAL_PASSWORD: ${{ secrets.CENTRAL_PORTAL_PASSWORD }}
          GPG_SIGNING_KEY:         ${{ secrets.GPG_SIGNING_KEY }}
          GPG_SIGNING_PASSWORD:    ${{ secrets.GPG_SIGNING_PASSWORD }}
        run: |
          VERSION=${GITHUB_REF_NAME#v}
          ./gradlew publishAllToCentralPortal -Pjpdfium.version="$VERSION"
```

### 6.2 GitHub secrets to configure

Repository → Settings → Secrets and variables → Actions:

| Secret                    | Value                                                        |
|---------------------------|--------------------------------------------------------------|
| `CENTRAL_PORTAL_USERNAME` | Central Portal user‑token username                           |
| `CENTRAL_PORTAL_PASSWORD` | Central Portal user‑token password                           |
| `GPG_SIGNING_KEY`         | Output of `gpg --armor --export-secret-keys <ID>`            |
| `GPG_SIGNING_PASSWORD`    | GPG key passphrase                                           |

---

## 7. Snapshots (nightly / main branch)

Snapshots are mutable and don't require the manual Publish click. Wire a
`main` branch workflow:

```yaml
on:
  push: { branches: [main] }

# Same matrix as 6.1, but the final publish step uses:
#   ./gradlew publishAllPublicationsToCentralPortalRepository \
#     -Pjpdfium.version="1.0.0-SNAPSHOT"
# (Do NOT run finalizePortalDeployment — snapshots auto-propagate.)
```

Consumers resolve them by adding:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

---

## 8. Consumer‑side verification

After the first release is live, test the happy path from a clean project:

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.0.0"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-linux-x64") // pick per OS
}
```

```java
// Smoke test
public static void main(String[] args) throws Exception {
    NativeLoader.ensureLoaded();
    try (var doc = PdfDocument.open(Path.of("sample.pdf"))) {
        System.out.println("Pages: " + doc.pageCount());
    }
}
```

Run with `--enable-native-access=ALL-UNNAMED`. If `NativeNotFoundException`
fires, the native jar is published but its resources aren't staged —
see §3.1.

---

## 9. Release checklist (copy into every release PR)

- [ ] `gradle.properties` bumped, or `-Pjpdfium.version=...` passed to the workflow
- [ ] `CHANGELOG.md` updated (create it if missing)
- [ ] `./gradlew build` green on all five matrix platforms
- [ ] `./gradlew publishToMavenLocal` inspected — all jars contain what they should, sources/javadoc present
- [ ] BOM includes every publishable module
- [ ] Tag pushed (`git tag v1.0.0 && git push --tags`)
- [ ] Release workflow green
- [ ] Central Portal dashboard → deployment validated (no red badges)
- [ ] Manual **Publish** clicked (or `autoRelease=true` was used)
- [ ] Resolved from a clean Gradle project within 30 min of release
- [ ] GitHub Release created with the same tag

---

## 10. Common failure modes

| Symptom                                                      | Cause                                                                         |
|--------------------------------------------------------------|-------------------------------------------------------------------------------|
| `401 Unauthorized` on upload                                 | User token expired or namespace not verified                                  |
| `Deployment failed: Missing sources/javadoc`                 | Native modules lack `withSourcesJar()` / `withJavadocJar()` — see §3.2        |
| `Deployment failed: No signature found for ...asc`           | `signing.key` / `signing.password` not visible to Gradle; check env           |
| Consumer: `NativeNotFoundException`                          | Native jar published empty — see §3.1                                         |
| Consumer: `UnsatisfiedLinkError: dependent library not found`| `native-libs.txt` manifest missing, so RUNPATH deps aren't extracted          |
| `Cannot redeploy version 1.0.0`                              | Release versions are immutable; bump to `1.0.1`                               |
| Namespace shown as "pending"                                 | DNS TXT record hasn't propagated; wait or re‑check with `dig TXT <namespace>` |

---

## 11. Future improvements

- **Reproducible native builds** — pin PDFium commit SHA, container‑ize the
  Linux build so anyone can reproduce the published binaries bit‑for‑bit.
- **SBOM attachment** — Central Portal accepts `.cdx.json` sidecars; generate
  via `cyclonedx-gradle-plugin` to give consumers a dependency inventory.
- **Provenance / SLSA** — attach `actions/attest-build-provenance` attestations
  to the GitHub Release so consumers can verify binaries came from CI, not a
  laptop.
- **Thin + uber artifact split** — publish an `all-natives` jar that bundles
  every platform for users who don't want to pick per‑OS at build time.
- **Consumer docs** — a `docs/CONSUMING.md` mirroring §8 with Maven/Gradle/SBT
  snippets and a per‑OS troubleshooting matrix.
