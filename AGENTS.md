# Thor Bili public maintenance

- Read README.md and preserve the author fcl3469, original project URL, LICENSE and third-party notices.
- This repository contains public release snapshots on branch public. Do not import internal branches, internal history, device data, credentials or signing keys.
- Preserve unrelated working changes. Use python3 build.py followed by python3 verify.py; keep builds sequential.
- Keep source/build checks, APK identity, phone runtime and physical Thor acceptance distinct. Report the exact tested APK SHA256.
- Device tests must remain silent and must not change system volume or invoke account write actions.
- No push, upload, visibility change, tagging or release publication is implied by local editing or building.
