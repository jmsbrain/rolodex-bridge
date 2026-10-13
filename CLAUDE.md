# Rolodex Bridge

Small regular Android app (not a Light SDK tool). Three books: the full list on mailbox.org (Clean Contacts, used by Rolodex Light, jmsbrain/rolodex-light), a one-number-per-card copy (Contacts Light), and LightOS's address book on the phone (a DAVx5 address-book account), which is made to match the copy. Contacts created in LightOS are folded into the full list. CardDav.kt and SyncCard.kt come from the user's Android Rolodex (jmsbrain/Rolodex, `C:\Users\jmsbrain\Downloads\Light Tool`); keep them in step with it.

## Rules

- The full list is the source. Contacts Light and LightOS are derived from it (`Splitter.pieces`); a piece's key is card + number, so other edits don't replace entries.
- Never duplicate: LightOS entries are reused by phone number before anything is added. Only the first build deletes leftover LightOS entries; after that nothing typed on the phone is deleted.
- Write to LightOS's book as an ordinary app (not a sync adapter) so its own sync uploads the changes.
- Nothing long-running: WorkManager content-URI job + hourly job (BridgeWork.kt). The first build runs from the app and resumes if interrupted.
- Cover changes to splitting/labels with `SplitTest`.
- Keep code comments short. Version in `app/build.gradle.kts` (versionCode +1). Tag `vX.Y.Z`.
- Commit as you go. Ask before pushing.
- Test on the emulator with `tools/MockCardDav.java` and made-up contacts. Never with the user's real account; real phone only when asked, always `adb -s <serial>`.
