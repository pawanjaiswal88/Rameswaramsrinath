# Mandir Manager v2 (Android)

Offline-first app for a colony mandir committee. Kotlin, Room (SQLite), Material Components.

## What's new in v2
- **Login with username and password**, multiple users, Admin and Collector roles, add, edit and remove users.
  Passwords are stored as PBKDF2-SHA256 hashes. The app locks after 10 minutes in the background.
  Optional fingerprint unlock for the phone's owner.
- **Donation deletion needs 3 approvers.** Up to 5 users can be marked as approvers. Each approval =
  that person's own password + a fingerprint scan. Approvals can be given on different phones and
  combine through sync.
- **Permanent deletion history.** Every deleted donation is copied in full with the reason, requester,
  the 3 approvers, times and phone codes. SQLite triggers reject any UPDATE or DELETE on that table,
  and sync and backup import only ever *add* history.
- **Fee plans per member:** Monthly or Yearly with a custom amount (or No fee). Defaults: residents
  monthly, committee members yearly. Separate pending lists for the month and the year.
- **Receipt numbers restart every year** and include the phone code: `REC-2026-A-0001`.
  Deleted numbers are never reused. Vouchers: `V-2026-A-0001`.
- **Google Drive sync** for several phones, plus **export and import of backup files (.mbk)**.
- **Custom mandir logo** on the login screen, home screen and PDF receipts (synced to all phones).

## About the fingerprint requirement
Android does not let any app store, read or compare fingerprints. An app is only told "a fingerprint
enrolled on this phone matched". It cannot tell whose finger it was. So "save 5 fingerprints and
require 3 of them" cannot be enforced with the phone's built-in sensor alone.

How the app makes 3-person approval real anyway:
1. Each approver must type **their own password** (which identifies the person), then
2. scan a fingerprint (which proves someone enrolled on that phone is physically present).

The strongest setup: each approver installs the app on **their own phone** and approves from there.
Their phone's fingerprint is then genuinely theirs, and the approval reaches the others by sync.

To truly capture and match individual fingerprints on one phone, you need an external USB
fingerprint scanner with its own SDK (for example Mantra MFS100 or Morpho MSO 1300). The
approval step lives in `DeletionRequestsActivity.verifyAndApprove()`, where such an SDK can be added.

## Run it
1. Open the folder in Android Studio (Koala 2024.1 or newer), let Gradle sync, press Run.
2. The first screen asks you to create the admin account. Other phones should instead tap
   **Get data from Google Drive** or **Restore from a backup file**, then log in.
3. As admin: Settings → set the mandir name, logo and fees, and give **each phone a different code** (A, B, C…).
4. Users → add collectors and mark up to 5 approvers.

> Upgrading from v1: v1 was a prototype, so its database is reset on first launch of v2.
> If you entered real data in v1, export CSV from v1 first. From v2 on, any schema change must
> ship a Room `Migration` so the deletion history is never lost.

## Google Drive sync: one-time setup (needed before Sync works)
Google requires every app that accesses Drive to be registered:
1. Go to https://console.cloud.google.com → create a project.
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **OAuth consent screen:** External, add the scope `.../auth/drive.file`, and add the committee Gmail
   as a test user (or publish the app).
4. **Credentials → Create credentials → OAuth client ID → Android.** Package name `com.mandir.manager`,
   plus the SHA-1 of your signing key. Get it in Android Studio's terminal with
   `./gradlew signingReport`. Add one client for the debug key and one for the release key.
5. No code change is needed. Google matches the app by package name and SHA-1.

How sync works: sign in with **the same committee Google account on every phone**. Each phone
uploads its own snapshot to the Drive folder *Mandir Manager Sync*, downloads the others and merges
by record. Nothing is overwritten blindly: the newest edit wins, approvals combine, and deletion
history only grows. It syncs automatically when the app opens (every 10 min at most) and on **Sync now**.
Bill photos stay on the phone that took them.

## Project layout
```
data/  Entities.kt (users, members, donations, expenses, deletion requests, deletion log)
       Daos.kt (all SQL), AppDatabase.kt (v2 + history-protection triggers)
util/  Security (password hashing, session, fingerprint), DeletionService (3-approval rule),
       Backup (snapshot + merge), DriveSync (Drive REST + authorisation), Numbering (yearly numbers),
       Logo, ReceiptPdf, WhatsApp, Export (CSV + share), Fmt, Prefs, Constants, Ui
ui/    Login, Main, AddDonation, Donations, Members, AddMember, Expenses, AddExpense, Reports,
       DeletionRequests, DeletionLog, Users, AddUser, Sync, Settings
```

## Security limits to know
- Anyone with full access to the committee Google account could tamper with Drive files. Use
  2-step verification on that account.
- A rooted phone can bypass any app-level protection. Regular exported backups kept by the
  treasurer are the independent check: history in a backup can never be removed by importing.
