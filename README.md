# Rolodex Bridge (1.0.0 beta)

Keeps the Light Phone III's own contacts (LightOS) in step with your full mailbox.org address book, so [Rolodex Light](https://github.com/jmsbrain/rolodex-light) and LightOS show the same people.

LightOS holds one phone number per contact. The bridge gives it exactly that: a contact with several numbers becomes one entry per number, named by the kind of number:

- Ada Lovelace (Mobile 1)
- Ada Lovelace (Mobile 2)
- Ada Lovelace (Work)

A contact with one number is labelled too ("Abbie Vaughn (Mobile)"), so any entry without a label in LightOS's list belongs to another app (WhatsApp, Molly). Contacts with no number are left out of LightOS.

A small regular Android app, since Light SDK tools can't read the phone's contacts.

## Three address books

- **Full list** (e.g. *Clean Contacts* on mailbox.org): one contact per person, every number. Rolodex Light uses it. Edit and delete contacts here.
- **One number per entry** (e.g. *Contacts Light* on mailbox.org): a copy of the full list in LightOS's shape, kept up to date by the bridge. Emails, addresses and notes go on each person's first entry.
- **LightOS's address book** on the phone: made to match the one-number copy.

## How it works

- About once an hour (and on CHECK NOW), changes to the full list — new people, new or removed numbers, edits, deletions — are copied to the other two.
- When you add a contact in LightOS, Android starts a short job: the contact is added to the full list (entries like "Joe (cell)" and "Joe (work)" become one contact, Joe, with both numbers), and its LightOS entry is renamed to match.
- Nothing runs in between. The app keeps your sign-in, a copy of the full list for comparing, and which entry belongs to which number.
- Changes made to these entries in LightOS aren't copied back; they're replaced the next time the contact changes in the full list.

## First build

Pick the three address books, then BUILD:

1. LightOS contacts with numbers the full list doesn't have are added to it.
2. The one-number book is filled (anything already in it is removed).
3. LightOS's address book is made to match it: entries with the same number are reused and renamed, missing ones are added, and leftover duplicates are removed.

Back up LightOS's contacts first.

## Permissions

Contacts (read and change) and internet. Contacts go only to the server you sign in to. The password is encrypted with an Android Keystore key.

## Build

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Emulator testing: `java tools/MockCardDav.java`, then sign in with any email/password and server `http://10.0.2.2:5233/` (debug builds allow plain http to 10.0.2.2 only).

## License

MIT
