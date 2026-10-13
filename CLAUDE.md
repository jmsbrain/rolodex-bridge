# Rolodex Bridge

Small regular Android app (not a Light SDK tool): copies NEW contacts between LightOS's address book (a DAVx5 address-book account on the phone) and a mailbox.org CardDAV book. Rolodex Light (jmsbrain/rolodex-light) syncs that book. The user's daily-driver Android Rolodex (jmsbrain/Rolodex, `C:\Users\jmsbrain\Downloads\Light Tool`) is where CardDav.kt and SyncCard.kt came from; keep them in step with it.

## Rules

- Never create duplicates. Everything copied goes through `Planner` (Plan.kt): match on name, phone (last 10 digits) or email; err toward "already there". Cover changes with `PlanTest`.
- New contacts only; contacts present at START are the baseline. No edits or deletions are mirrored.
- Insert into LightOS's book as an ordinary app (not a sync adapter) so its own sync uploads them.
- Nothing long-running: WorkManager content-URI job + hourly job (BridgeWork.kt).
- Keep code comments short. Version in `app/build.gradle.kts` (versionCode +1). Tag `vX.Y.Z`.
- Commit as you go. Ask before pushing.
- Test on the emulator with `tools/MockCardDav.java` and made-up contacts. Never with the user's real account; real phone only when asked, always `adb -s <serial>`.
