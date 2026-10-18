# Security Policy

## Reporting a Vulnerability

Please report security vulnerabilities privately. **Do not open a public issue.**

Send details to the maintainers via GitHub's private vulnerability reporting:

1. Open the **Security** tab of this repository.
2. Click **Report a vulnerability**.
3. Include:
   - The affected version / commit
   - A minimal reproduction (sample PDF or call sequence) if possible
   - Impact description and any suggested fix

We aim to acknowledge reports within 48 hours and to ship a fix in a
timeframe proportional to the severity.

## Scope

In scope:
- The JPDFium Java library (`jpdfium`, `jpdfium-spring`, `jpdfium-vips`)
- The native bridge (`native/`): PDFium interaction, memory safety, and downcalls
- CI / build / release pipelines (`.github/`, Gradle wrapper, publishing)

Out of scope:
- PDFium engine bugs (report those to the upstream Chromium PDFium issue tracker).
  A vulnerable or outdated engine inside a JPDFium artifact is still in scope here
  for triage, tracking, and dependency updates, and so is every bridge, loader,
  packaging, and integration bug.
- Third-party system packages invoked by external tooling (qpdf, Ghostscript)

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |
| < latest | :x:               |

We maintain only the latest release. If you need a security fix backported,
note the version you depend on when reporting.
