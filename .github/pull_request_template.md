# Description of Changes

<!--
What changed, why, and anything worth calling out to a reviewer.
Closes #(issue number).
-->

---

## Checklist

### General

- [ ] I have read the [Contributing Guide](https://github.com/Stirling-Tools/JPDFium/blob/main/CONTRIBUTING.md)
- [ ] I have performed a self-review of my own code
- [ ] My changes generate no new warnings
- [ ] Notable user-facing changes are recorded under `## [Unreleased]` in `CHANGELOG.md`

### Library changes

- [ ] Javadoc covers any new or changed public type or member
- [ ] Public API changes are additive, or `.github/workflows/compatibility.yml` excludes the break with a reason
- [ ] Version references stay consistent across the README, `docs/installation.md`, and the POMs

### Native or FFM changes

- [ ] Rebuilt the bridge with `bash native/build-real.sh`
- [ ] Ran `./gradlew :jpdfium:integrationTest`
- [ ] Updated `native/bridge/include/jpdfium.h` and the ABI notes for any ABI change

### Testing

- [ ] Added or updated tests that cover the change
- [ ] `./gradlew test` passes
