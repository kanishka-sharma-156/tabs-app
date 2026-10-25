# TABS - Firebase setup (READ THIS FIRST)

## The actual root cause

Your Firestore database **exists and works**. The problem was your **deployed security
rules**, and there were two stages to it.

**Stage 1 - wide open.** I proved with real API calls that one signed-in user could write
into another user's private data, forge a friendship with no request, confirm their own
payment, and read any other user's data. Every rule was effectively
`allow read, write: if request.auth != null;`.

You published the first version of [`firestore.rules`](firestore.rules), which fixed all of that:

| Check | Before | After your publish |
|---|---|---|
| A writes into B's private data | allowed | **denied (403)** |
| A forges a friendship with no request | allowed | **denied (403)** |
| A confirms its own payment | allowed | **denied (403)** |
| Outsider reads the private chat | allowed | **denied (403)** |
| Outsider reads a friend request that is not theirs | allowed | **denied (403)** |

**Stage 2 - profiles were still public.** Your run then reported `21 passed, 1 FAILED`, and
I investigated instead of accepting it. It was a real problem I had built in:
`allow read: if isSignedIn()` on `users/{uid}` meant **any registered user could list your
entire user base** and read every user's `email`, `username` and `displayName`. I verified
that directly:

```
stranger LISTS all users        -> HTTP 200, returned 16 documents
stranger reads victim profile   -> HTTP 200
   fields exposed: displayName, email, username, emailLower, uid
stranger reads victim backup    -> HTTP 403   (private data was safe)
unsigned client reads profile   -> HTTP 403   (correctly blocked)
```

You chose to lock profiles down, which is what the current rules do.

---

## Step 1 - Re-publish the security rules  REQUIRED

The ruleset you published was the previous revision. The version now in this project:

* makes profiles private - readable only by the owner and by people you are actually
  connected to (friends, or a request in flight), and
* denies listing the `users` collection, so the directory cannot be scraped.

Before you publish, confirm it is valid:

```powershell
cd C:\Users\Admin\AndroidStudioProjects\TABABLE
powershell -ExecutionPolicy Bypass -File.\validate-rules.ps1
```

It should print `RESULT: RULESET COMPILES CLEANLY - safe to publish`.

Then publish:

1. Open <https://console.firebase.google.com/project/tabs-eb7c5e5e/firestore/rules>
2. Select everything in the editor and delete it
3. Paste the **entire** contents of [`firestore.rules`](firestore.rules)
4. Click **Publish**

Finally, prove it end to end:

```powershell
powershell -ExecutionPolicy Bypass -File.\verify-firebase.ps1
```

Expect **ALL CHECKS PASSED**. Against the currently published rules it reports
`22 passed, 3 FAILED`, and those 3 are exactly the profile-privacy checks this publish
fixes.

---

## Two tools in this project

**[`validate-rules.ps1`](validate-rules.ps1)** - compiles `firestore.rules` with the real
Firebase rules compiler and reports errors with line numbers, before you publish. It
downloads the compiler once (~61 MB) into `%TEMP%` and reuses it afterwards.

This is not a nicety. The rules language is stricter than it looks, and while writing the
privacy rules I hit both of these:

* **the ternary operator is forbidden outright.** I had written
  `uid1 < uid2 ? uid1 + '_' + uid2: uid2 + '_' + uid1` to build a sorted document id.
  That fails to compile, so the ruleset would never have published.
* **a boolean cannot be concatenated into a string.** My next attempt smuggled the
  comparison result into the id as `... + (uid1 < uid2)`. It compiled, but emitted
  `Invalid type. Received one of [bool]`, which would have failed at runtime.

Both are now avoided. A friend request lives at `friendRequests/{senderUid}_{recipientUid}`
with no sorting needed, because the rules accept **either** ordering everywhere. That keeps
the pair symmetric without the rules ever having to pick a min or max.

**[`verify-firebase.ps1`](verify-firebase.ps1)** - exercises the live project with real
accounts: creates them, checks its own credentials, tests every security boundary, runs a
full friend-request handshake, sends a chat message and a payment, then deletes the
documents it created and confirms they are gone.

> `-ExecutionPolicy Bypass` is needed because script execution is disabled on this machine.


## Step 2 - Confirm Email/Password sign-in is enabled

Already verified as enabled on your project, so no action needed. To confirm:

<https://console.firebase.google.com/project/tabs-eb7c5e5e/authentication/providers>
-> **Email/Password** should read *Enabled*.

## Step 3 - Delete the accounts I created while testing

My verification runs created throwaway accounts. They are harmless but you should remove
them: open
<https://console.firebase.google.com/project/tabs-eb7c5e5e/authentication/users>
and delete every account whose email starts with `tabsverify.`, `tbldiag.`, `tblclean.`
or `tblverify.`.

I deleted **all** the Firestore documents those runs created and re-checked that each path
returned 404, so only the Auth accounts remain.

I deliberately did **not** publish any ruleset to your project on your behalf - publishing
rules is an infrastructure change to a live project, and that call is yours. You published
the first revision yourself; the privacy revision is still waiting for you (Step 1).

---

## Money: paise or rupees?

This bit me once, in the most visible way possible - a real balance of ?350 showed as
?35000. App-wide the convention is now:

| Where | Unit |
|---|---|
| Firestore fields (`amountPaise`, settlement `amountPaise`, expense `shares`) | **paise** |
| `SplitCalculator`, `BalanceCalculator` | **paise** |
| `Money.formatInr(paise)` | takes **paise**, prints rupees |
| `Person.getNetAmount()` | **rupees** - shown directly by the UI |
| `Group.getTotalSpent()` | **rupees** |
| `LedgerItem` amounts | **rupees** |

The rule is: convert **once**, at the boundary. `TabRepository.updatePersonBalances()`
takes the paise result from `BalanceCalculator` and divides by 100 exactly once, because
every screen prints `Person.getNetAmount()` straight to the user.

The bug was three call sites dividing by 100 while three others used the raw value, so the
same number meant rupees in one place and paise in another. `Person` now documents the unit
on the field, and `app/src/test/java/.../logic/MoneyTest.java` pins the conversion down with
8 tests, one named after this exact regression:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```

If you ever see an amount exactly 100x too large or too small, it is this boundary - do not
go hunting for data corruption.

### Formatting

`Money.formatInr(paise)` builds the string itself instead of calling
`NumberFormat.getCurrencyInstance`. That mattered: the currency formatter depends on the
device's own locale data, and on many devices it prints `Rs.` or `INR` instead of the rupee
sign - which is how totals look wrong even when the number is right.

It now always emits the rupee sign, groups digits the Indian way (`1,23,456`), and drops a
trailing `.00` because nearly every amount here is a whole rupee figure.

### Amount input is validated

`Money.parsePaise` used to strip every non-digit character, so **`12abc` silently became
12** and random words were accepted as amounts. It is strict now: anything that is not a
plain positive number is rejected with a message saying what is wrong. `1,200`, `-50`,
`12 rupees` and `.` are all rejected.

Amount fields are `inputType="numberDecimal"` so decimals like `99.50` can be typed, and
free-text fields (expense title, transit stops, full name, username) have `maxLength` caps
so a wall of text cannot be saved.

---

## Splitting a bill: equal, custom, or by item

The add-expense screen has three split modes.

**equal** - the amount is divided evenly among everyone you tick.

**custom** - you type each person's exact share, and submit stays blocked until the
shares add up to the total precisely.

**items** - you enter the bill as line items, and each item is divided among **only the
people who had it**. Tap the people chips under an item to say who shared it, and the
card shows `Rs 217 each - 3 people`. Somebody who only had a drink is not charged for
the pizza.

In items mode the **amount field is driven by the items**: the bill total is the sum of
the line items, so the total can never disagree with its parts.

An item with a price but **nobody selected** blocks submit and says how much is
unassigned, instead of quietly spreading it across everyone. That is deliberate - there
is no honest way to charge an item to nobody.

The breakdown is saved on the expense too (`items`, plus `splitMode`), so everyone in
the space can see how it was split, not just whoever typed it in.

`ItemizedSplit` holds this arithmetic and has 11 unit tests, including that shares
always sum to exactly the bill, that remainder paise are handed out one at a time
rather than lost, and that an unassigned item is reported rather than charged.

---

## Receipt photos

The add-expense screen has an **add a receipt photo** card. Tapping it offers:

* **Take a photo** - opens the camera. The image is written to a private cache folder
  exposed through `FileProvider`, so the app needs **no storage permission**.
* **Choose from gallery** - opens the device image picker.

Either way the picture appears as a thumbnail you can replace or remove.

### What "reading the receipt" actually does

It looks like it is reading the receipt, because that is what you asked for: a short
progress animation, then a sample item list appears with five items and the team members
already selected.

**Be clear on this - there is no OCR. It is not reading your photo.** It fills in a fixed
sample bill (`Paneer Tikka 380`, `Butter Naan 120`, `Dal Makhani 260`, `Cold Coffee 180`,
`Gulab Jamun 140`), spreads the shared dishes across everyone, and puts the two drinks on
the first two people. Every value is editable.

I built it this way deliberately: a convincing-looking scanner that guesses wrongly about
somebody's bill is worse than one that obviously needs checking. The screen says
"sample items: edit them to match your receipt" and toasts the same.

Genuine OCR would need a paid vision API (Google Cloud Vision, or ML Kit on-device). Say
the word and I will wire one up properly.

The image is held only while the form is open and is **not uploaded**, so a large photo can
never break the Firestore document size limit.

### Where the split ends up

Submitting an items expense writes `shares` computed from the items, so the distribution
flows into every other screen through the normal balance recalculation:

* **Home** - owed to you / you owe totals and the per-person rows
* **People** - each person's net amount
* **Groups** - group totals
* **1-on-1 splits** - per-person figures
* **Profile** - tabs to clear, and the export
* **Ledger** - the expense appears with your share of it

The item breakdown is stored on the expense too (`items`, `splitMode`), so the other people
in the space can see how it was divided.

---

## Expenses on the map

Two halves, both driven by the location you pick when adding an expense.

**Picking a location.** The add-expense screen has a **location** card. Tapping it opens a
full-screen map; tap anywhere to drop the pin, then confirm. The place name comes from the
device's own geocoder (`android.location.Geocoder`), so it needs **no API key and no Places
billing**. If geocoding fails or is unavailable the coordinates are used as the name, so the
pin still works. The field is optional, with a **clear** button to remove it.

**What gets saved** - a nested map on the expense document:

```
spaces/{spaceId}/expenses/{id}
    place: { placeId, name, lat, lng }
```

That is exactly the shape `SpaceExpense.place` reads back, so the map tab picks it up with
no extra wiring.

**Seeing the totals.** The map tab groups expenses **by location** and shows the **total
spent at each place** on its pin, rather than one pin per expense - which is the number you
actually want. Tapping a pin lists who paid what, by name.

A **you spent / everyone spent** toggle sits above the map:

* **you spent** - only expenses you paid for, so the map answers "where did my money go"
* **everyone spent** - every expense across your spaces

The day filter is now built from your data and only offers days that have a located expense.
Expenses saved without a location simply do not appear on the map.

### If the map tiles do not load

That is a Google Maps API key issue, not an app bug - and it would have affected the
existing map tab too. The key is in `app/src/main/AndroidManifest.xml`
(`com.google.android.geo.API_KEY`). If the map is blank or shows a "no API key" watermark:

1. Open <https://console.cloud.google.com/google/maps-apis/credentials> for project
   `tabs-eb7c5e5e`
2. Select that API key
3. Under **Application restrictions**, either choose **Android apps** and add your signing
   certificate's **SHA-1** together with package name `com.example.tab_able`, or set
   **None** temporarily to confirm the key is the cause

Get the debug SHA-1 from Android Studio: Gradle panel -> Tasks -> android -> `signingReport`.

Everything except the map keeps working while the key is misconfigured: the location you
pick is still saved on the expense, it just cannot be drawn.

---

## How to make every existing user searchable

**The rule:** a person is only findable *and* reachable when **both** documents exist.

```
emailLookup/{email}  ->  { uid }        makes them FINDABLE
usernames/{name}     ->  { uid }        makes them findable by username
users/{uid}          ->  { profile }    makes them able to RECEIVE requests
```

A search resolves the lookup, then the friend request is written *beside the recipient's
profile*. If `users/{uid}` is missing, the person shows up in search but can never receive
a request. That is exactly what happened to kanu1 and kanu2.

Profiles may only be written by their owner - the security rules enforce that, and I tried
and was refused (`403`) when repairing these two. So there is no way for your app, or for
me, to create a profile on someone else's behalf. Pick whichever of these fits:

**Option 1 - fastest, per person.** Have them open TABS and log in once. Every login
performs a full self-repair: it writes the profile and both lookup keys in one atomic
batch. One sign-in permanently fixes that account.

**Option 2 - repair a whole list at once** using [`backfill-users.ps1`](backfill-users.ps1).
It signs in as each person and runs the same repair, because signing in is what authorises
the write.

```powershell
# users.csv:  Email,Password,Username
#             kanu1@gmail.com,theirpassword,kanu1
powershell -ExecutionPolicy Bypass -File .\backfill-users.ps1 -Csv .\users.csv
```

If you do not know someone's password, reset it first in the console:
Authentication -> Users -> that account -> **Reset password**. Delete the CSV afterwards;
it holds passwords. Add `-WhatIf` to see what it would do without writing anything.

**Option 3 - if an account was never used at all.** If a lookup key points at a uid with no
profile, the app now treats that key as orphaned and releases it, so the address can be
registered fresh. Ask the person to register again.

### What the app now guarantees

* Every login writes the profile and both lookup keys in **one atomic batch**. They can no
  longer be left half-written, which is what caused this in the first place - the repair
  used to be two separate writes.
* If a friend request fails because the recipient has no profile, the app says so plainly
  instead of showing a raw Firestore error.
* Orphaned lookup keys are detected and released, so a dead key cannot permanently block a
  real person from registering.

---

## If someone is findable but cannot receive a friend request

There are two separate documents involved, and they can get out of step:

```
emailLookup/{email}  ->  { uid }        makes a person FINDABLE
users/{uid}          ->  { profile }    makes them able to RECEIVE requests
```

Search resolves the email through `emailLookup`, so a person can be found even when their
`users/{uid}` profile is missing. But the security rules require the recipient's profile
document to exist before a friend request may be written to them, so the request fails.

**How to tell which case you are in.** In the app, search for the address:

* *"found an account ... send request"* - profile exists, you are fine.
* *"Found ..., but that account has no profile yet. Ask them to open TABS and log in once"* -
  this is the broken state.

**How to fix it.** Have that person open the app and log in once. On every launch and every
login the app repairs its own profile, so one sign-in creates the missing document. Then
search again.

### Why it happened, and what I changed

The repair used to be written as **two sequential steps** - the profile first, then the
lookup documents. If the app was killed between them, the account was left with lookup
entries pointing at a uid that had no profile: findable, but unreachable. Registration
never had this problem because it always wrote all three in a single atomic batch.

`healProfile` now commits all three documents in **one batch**, exactly like registration,
so that half-written state can no longer occur. I reproduced the broken state, applied the
fix, and confirmed the profile is restored.

This is worth knowing because it is invisible from the outside: the person appears in
search, so the app looks like it is refusing to send a request for no reason.

---

## What changed in the code, and why

### 1. "If I log in again with the same id the information isn't saved anywhere"

**Cause:** `LoginActivity` never talked to Firebase at all - it showed a toast and opened
the dashboard. There was no account, so there was nothing to save or restore.
Registration never created an account either; the "OTP" was the literal string `"1234"`.

**Fixed:**
- `RegisterActivity` creates a real Firebase Auth account and writes a profile to
  `users/{uid}` plus both lookup keys (`emailLookup`, `usernames`).
- `LoginActivity` signs in with `signInWithEmailAndPassword`, then **reads the profile
  back out of Firestore** before opening the dashboard, so your real name and username
  return. A saved session is restored the same way on launch.
- `TabApp` turns on Firestore's **persistent disk cache**, so writes survive a restart
  and are replayed when the phone is back online.
- A failed registration is rolled back (the half-made Auth account is deleted) instead of
  stranding you with an account you can neither log into nor re-register.
- Careful ordering that matters: the profile is repaired **after** it is loaded, so an
  existing username can never be overwritten by a guess derived from your email address.

### 2. "I can't send real friend requests"

**Cause:** three separate bugs.
1. The old flow wrote to `users/{uid}/receivedInvites/...`. Friendships and spaces were
   created on the *sender's* device on behalf of *both* users, so a friendship could exist
   that the other person never agreed to.
2. It **auto-accepted itself after 3 seconds** via a `postDelayed`, so there was never a
   request for the other person to answer.
3. There was no way to find anyone: the search only checked lookup documents that
   registration never created.

**Fixed:** there is now exactly **one** request document per pair, at
`friendRequests/{smallerUid}_{largerUid}`, which both people can read and only the
recipient can answer.
- **Send** -> `PENDING`, visible in the recipient's inbox immediately.
- **Accept** -> `ACCEPTED`, and both friends lists update. The sender's own edge is written
  the moment they observe the acceptance.
- **Decline** -> `DECLINED`, no friendship created.
- **Cancel** -> the sender withdraws an unanswered request.
- The rules permit a `friends` entry **only** when an `ACCEPTED` request backs it, so one
  person can never forge a friendship - verified by the test that previously failed.
- The friends screen now really searches by username or email, shows incoming and outgoing
  requests separately, and suggests other people on TABS so a new account isn't a dead end.

### 3. Chat and payments

**Cause:** the chat wrote to a space id that did not exist, so every message was rejected.
Settlement confirm/dispute passed the *message* id where the *settlement* id was required,
so confirming a payment updated nothing.

**Fixed:**
- Opening a 1-to-1 chat creates the `DIRECT` space on demand
  (`TabRepository.ensureDirectSpace`), and the composer stays disabled until it exists -
  so you can no longer type into a conversation that has nowhere to save.
- "Pay" writes a real settlement; **only the person being paid** can confirm or dispute
  it, which is what actually moves a balance.
- Settlement cards re-render from the live settlement status, so a confirmation appears on
  both phones.
- Messages use a **server timestamp**, so ordering is identical on both devices.
- The expense screen was writing `payerUid` while the model and rules expect `paidByUid`,
  so **every expense was silently rejected**. Fixed.
- Groups now resolve typed usernames into real accounts instead of creating placeholders.

### 4. Fake data removed from the UI

These screens showed invented numbers; they now read from Firestore:
- **Groups -> 1-on-1 splits** had four hardcoded people (Riya, Aman, Kabir, Zoya).
- **Profile -> "tabs to clear by you"** had hardcoded Aman Rs.340 / Zoya Rs.120 with mock *pay*
  buttons that only showed a toast.
- **Profile -> export** produced a fixed list of the same fake people; it now exports your
  real balances.
- The **dashboard "settle" button** claimed *"Paid... via UPI (mock)"* without doing
  anything. It now proposes a real settlement for the true outstanding amount.
- **"preview empty" / "toggle empty"** debug switches removed.
- **"Quick Test Login"** bypassed authentication and created sessions with no account
  behind them - removed.

One screen still contains sample content, which I left alone because it is presentation
rather than account data: the **trip map** pins in `CustomMapView.java` are demo
sightseeing spots, and the "receipt scan" OCR caption in `AddExpenseFragment` is a
placeholder. Everything involving accounts, friends, chat and money is real.

---

## Data model

```
users/{uid}                              uid, email, emailLower, displayName, username
users/{uid}/friends/{friendUid}          friendUid, friendName, since   mutual friendship
users/{uid}/backups/{docId}              private cloud backup
users/{uid}/prefs/discovery              lookup keys already scanned for orphans
friendRequests/{uidA_uidB}               fromUid, toUid, fromName, toName, status
emailLookup/{emailLower}                 uid                        uniqueness
usernames/{usernameLower}                uid                        uniqueness
spaces/{spaceId}                         type DIRECT|GROUP, memberUids, leaderUid, name
spaces/{spaceId}/messages/{id}           senderUid, text, type, amount, timestamp
spaces/{spaceId}/expenses/{id}           title, amountPaise, paidByUid, shares,
                                         place: { placeId, name, lat, lng }
spaces/{spaceId}/settlements/{id}        fromUid, toUid, amountPaise, status
disputes/{id}                            spaceId, expenseId, raisedByUid, reason
```

A friend request is keyed `{senderUid}_{recipientUid}` (unsorted); the rules accept either
ordering, which keeps it symmetric without the rules needing a ternary - see the note on the
rules compiler further up.

A 1-to-1 **space** id, by contrast, IS sorted - the two uids sorted and joined with `_` - so
both people always arrive at the same space without a handshake.

---

## How to test the whole flow by hand

You need **two accounts**, so use one emulator and one browser:

1. **Device A** - register `alice@example.com`, username `alice`.
2. **Device B** - register `bob@example.com`, username `bob`.
   (Or add a user in the console under Authentication -> Users.)
3. **On A**: Profile -> *friends* -> type `bob` -> **search** -> **send request**.
4. **On B**: the request appears under **friend requests** -> tap **accept**.
   Both friends lists update. *This is the step that previously could not happen.*
5. **On A**: tap **chat** on Bob -> send a message; it appears on B in real time.
6. **On A**: enter an amount -> **pay**. A payment card appears on both phones.
7. **On B**: tap **Confirm received**. Balances update on **both** phones.
8. **Log out on A, log back in.** Name, username, friends and balances are all still there.
   *This is the "information isn't saved" complaint.*

Failures now surface the real reason - for example *"Permission denied by Firestore
rules"* or *"Firestore database not found..."* - instead of silently doing nothing.

---

## Building

The Gradle wrapper needs a JDK; Android Studio ships one, and `java` is not on your PATH:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\Admin\AndroidStudioProjects\TABABLE
.\gradlew.bat :app:assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`.
