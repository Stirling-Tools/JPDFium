# Native loading

The `jpdfium` artifact is pure Java. At runtime it needs the prebuilt PDFium binaries for the platform it is running on.

Packaging depends on your deployment model; see [deployment.md](deployment.md). This page covers what the loader does once a native module is present.

> A native module must match the operating system and architecture your application runs on. Dependency selection is explicit, so the build host does not matter. A platform missing from the declared set fails at load time with `NativeNotFoundException`.

## Downloading at runtime

As an alternative to bundling, JPDFium can download the matching native artifact from Maven Central the first time it runs and reuse the cached copy afterwards.

This is disabled by default. Enable it only when network access to Maven Central is acceptable at runtime:

```bash
java -Djpdfium.native.download=true --enable-native-access=ALL-UNNAMED -jar app.jar
```

Behavior of the downloader:

- Resolves `com.stirling:jpdfium-natives-<platform>` at the version stamped into the running JAR. Override with `-Djpdfium.native.version=<version>`.
- Downloads over HTTPS only. Plain HTTP is refused except for loopback test servers.
- Rejects snapshot versions.
- Verifies the SHA-256 of each extracted file against the manifest inside the downloaded JAR.
- Caches the result, so later runs work offline.

File checksums detect corruption, or changes relative to the artifact's manifest. The artifact and its manifest both come from the configured repository, so use a repository you trust. The repository URL can be pointed at an internal mirror with `-Djpdfium.native.repo=https://your-mirror/repository/maven-releases`.

## Cache location

Native binaries are extracted once per release into a per-user cache and then reused on later runs. JVMs running as the same user share the extracted libraries. Each load re-verifies the SHA-256 of the cached files it uses.

| Platform | Default cache root |
|---|---|
| Windows | `%LOCALAPPDATA%\jpdfium\native` |
| macOS | `~/Library/Caches/jpdfium/native` |
| Linux | `$XDG_CACHE_HOME/jpdfium/native`, or `~/.cache/jpdfium/native` |

Override the root with `-Djpdfium.native.cacheDir=<dir>`. When no user-private cache location exists, or the cache is not writable (common in containers and read-only home directories), extraction falls back to `java.io.tmpdir`.

## Extraction guarantees

- Extraction is SHA-256 verified.
- Results are published with `StandardCopyOption.ATOMIC_MOVE` where the filesystem supports it and fall back to a non-atomic `Files.move` otherwise. Readers validate the marker and checksums before loading, so an incomplete entry is rejected and re-extracted rather than loaded.
- Concurrent JVMs may extract simultaneously; each verifies the result independently.
- Stale per-JVM temp directories and obsolete cache entries are swept on a best-effort basis. Set `-Djpdfium.native.sweep=false` to disable the sweep.

## Integrity verification

Every load re-checks the SHA-256 of each cached file. Set `-Djpdfium.native.verify=marker` to skip that check:

```bash
java -Djpdfium.native.verify=marker --enable-native-access=ALL-UNNAMED -jar app.jar
```

This weakens cache-integrity verification: a tampered cache entry would be loaded without detection. Use it only when you trust the local filesystem and the cache directory is not writable by other users. It is not recommended for production deployments on shared or multi-tenant hosts.

To extract to a temporary directory on every run instead of using the cache, set `-Djpdfium.native.cache=false`. This is slower at startup and is mainly useful for diagnosing cache problems.

## Native access

JPDFium calls native code through the Foreign Function and Memory API, which Java 25 gates behind a flag. Start the JVM with:

```bash
java --enable-native-access=ALL-UNNAMED -jar app.jar
```

