# Rolodex Bridge

Copies new contacts between the Light Phone III's own address book (LightOS) and a mailbox.org (CardDAV) address book, so [Rolodex Light](https://github.com/jmsbrain/rolodex-light) sees contacts added in LightOS, and LightOS (calls, texts) sees contacts added in Rolodex Light.

A small regular Android app, since Light SDK tools can't read the phone's contacts.

## How it works

- **LightOS → mailbox.org:** when the phone's contacts change, Android starts a short job. New contacts in LightOS's address book are uploaded to the chosen mailbox.org address book.
- **mailbox.org → LightOS:** about once an hour (and on CHECK NOW), new mailbox.org contacts are added to LightOS's address book, the way LightOS itself adds them, so LightOS's own sync picks them up.
- Nothing runs in between. The app keeps no contact list: only your sign-in and a note of which contacts it has already seen.
- **New contacts only.** Contacts already on either side when you tap START are left alone. Edits and deletions aren't copied.
- **No duplicates:** a contact is only copied if nobody on the other side shares its name, a phone number or an email.
- **One-time copy:** FIND MISSING counts contacts that are on one side but not the other, and can add them.

## Setup

1. Install, open, ALLOW ACCESS (contacts).
2. Sign in with your mailbox.org email and password (an app password if you use two-factor sign-in).
3. Pick the mailbox.org address book, and LightOS's address book (the first listed holds the newest contact). START.

## Permissions

Contacts (read and add) and internet. Contacts go only to the server you sign in to. The password is encrypted with an Android Keystore key.

## Build

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Emulator testing: `java tools/MockCardDav.java`, then sign in with any email/password and server `http://10.0.2.2:5233/` (debug builds allow plain http to 10.0.2.2 only).

## License

MIT
