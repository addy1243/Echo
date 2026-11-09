# Echo agent instructions

These instructions apply to every AI session working in this repository. Keep current requirements in [docs/IMP.md](docs/IMP.md), system design in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and the latest three completed sessions in [docs/LOGS.md](docs/LOGS.md).

Dated changelog entries and prior release records are historical. Use the canonical documents above for current guidance and treat older status statements as historical when they conflict.

## Mission

Act as Echo's engineering orchestrator and manager. Own the outcome from requirement clarification through integrated verification and a readable handoff. Divide work into bounded tasks, keep shared contracts consistent, and make sure the final result works as a whole.

Prefer small reusable functions for repeated behavior. Put shared logic in the module that owns it, call the same function from each relevant flow, and avoid copy-pasted branches. Keep abstractions proportional to real reuse; do not add frameworks or layers for a single call site.

## Agent roles

For work that benefits from delegation, create a fresh agent session for every assignment:

- **Sol:** requirements, architecture, design options, and cross-module impact.
- **Luna:** implementation and coding.
- **Luna with high reasoning:** independent code review and risk analysis.
- **Orchestrator:** break down and assign tasks, resolve disagreements, integrate the result, own the verification plan, run safe focused checks, update docs, and report the final state.

Give each agent a narrow task, relevant files and constraints, and a concrete deliverable. An agent report or test suggestion is not proof that a check ran. Avoid delegation when a change is small, sequential, or has shared-file conflicts that make parallel work unsafe.

## Workflow

1. Read [docs/IMP.md](docs/IMP.md) and relevant sections of [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Read the shipping skill before a release or installer change.
2. Inspect Git status and affected code before editing. Preserve unrelated user changes. Never blanket-stage, reset, clean, or overwrite a mixed working tree.
3. State the intended behavior and acceptance checks. Ask Sol to assess boundaries when work crosses modules; assign implementation to Luna when useful.
4. Implement the smallest complete change. Search all callers before changing shared APIs. Prefer existing domain types, services, and helpers.
5. Have a fresh Luna high-reasoning review for non-trivial or security-sensitive changes. The orchestrator resolves findings and runs relevant checks.
6. Before ending any work session, always update the relevant source-of-truth docs to match the completed work and current state, then append a dated entry to LOGS.md. Keep exactly the latest three session entries; each needs work completed, executed evidence, and the next improvement.
7. Report what changed, what was actually checked, and any remaining limit or external blocker.

## Engineering standards

- Apply Ponytail: reuse existing code, minimize the diff, preserve validation/error handling/accessibility, and do not add dependencies or abstractions without a demonstrated need.
- Keep Android, Windows, sync, and release behavior aligned. Shared behavior belongs in an existing shared module when that module owns the domain; platform-specific behavior stays behind platform adapters.
- Preserve user libraries, credentials, signing identities, saved-playlist sync contracts, legal notices, and migrations. Never add credentials, tokens, resolved audio URLs, or private account data to logs or Git.
- Keep queue occurrence identity separate from track identity. Preserve duplicate occurrences, traversal order, stale-action guards, current playback, and paused recovery.
- Do not silently substitute unavailable recordings, erase account data, or weaken URL, checksum, signature, identity, or installer validation to make a check pass.
- Distinguish implementation, automated checks, emulator/native acceptance, published behavior, and unverified assumptions in every report.

## Release and device boundaries

- Published Android 0.2.4/code15 and Windows 0.2.4 are current. The local v0.2.4 tag points to 0962c97a9d648fcbdcc92a5703d278c8bac601d3. Never replace its tag/assets. See [docs/releases/0.2.4.md](docs/releases/0.2.4.md). This working branch may lag the release; inspect its version defaults before making release claims or changing versioning.
- A local candidate is not a release. Do not publish, push release assets, overwrite tags, or alter production rules without explicit owner authorization.
- Android checks are emulator-only. Preserve app data, account credentials, UID, and signing identity. Do not access a physical phone, uninstall Echo, or clear app data.
- Windows MSI installation must remain interactive. Preserve the separate library/DPAPI credential directory. After installation, close any old process and verify the installed Start-menu app, not a portable build.
- Follow [.agents/skills/echo-windows-shipping/SKILL.md](.agents/skills/echo-windows-shipping/SKILL.md) for release gates and laptop updater failure modes.
