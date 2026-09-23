# ClipBridge

Your own Clipt: copy on your PC, paste on your Android phone, and the other way round.
Everything travels through a hidden folder in **your own Google Drive**, so there is no
server to host, no Wi-Fi pairing, and no local network needed.

- Everything is **end-to-end encrypted** with a passphrase only you know. Google and anyone who gets into your Drive see scrambled data, not your clips or filenames.
- Only the **last 10 clips** are kept, plus up to **10 you pin** so they never age out. A search box finds an older one fast.
- Text, images, and any other file are supported, with no size cap beyond your Drive space. Big files stream in pieces.
- Notifications are **silent**: no sound, no vibration, no pop-up.
- Works on phones, tablets, foldables, and landscape, in light and dark mode.
- **Sync speed** is adjustable: check constantly, back off when idle to save battery, or something in between.
- A second device can join by **scanning a QR code** in Chrome, no retyping the passphrase.
- A **debug log** button gives you something safe to paste into a bug report — never your passphrase or clip content.

Setup takes about 20 minutes, once.

---

## Your fixed IDs (you'll paste these into Google Cloud)

| What | Value |
|---|---|
| Android package name | `app.clipbridge` |
| Android SHA-1 fingerprint | `49:31:AA:9F:FF:7F:F3:85:03:19:7D:81:D2:AB:1A:B5:AC:0B:9E:DC` |
| Chrome extension ID | `oklffclikimjimhihbkaoddchdndlhdj` |

The Android SHA-1 stays fixed because every build uses the same signing key — but unlike
earlier versions of this project, **the keystore itself is never committed to this repo**,
and its password never appears in any source file. It lives only in:

- **GitHub Actions secrets** (`CLIPBRIDGE_KEYSTORE_BASE64`, `CLIPBRIDGE_KEYSTORE_PASSWORD`,
  `CLIPBRIDGE_KEY_ALIAS`) — Settings → Secrets and variables → Actions — used by CI to sign
  release builds.
- Optionally, a local `android-app/keystore.properties` file (copy it from
  `keystore.properties.example` and fill in your own values) if you want to build locally.
  This file is gitignored.

If you ever fork or copy this project, **generate your own keystore** (`keytool -genkeypair
...`) rather than reusing anyone else's — whoever holds the keystore file + password can
sign updates that any device already running the app will accept as legitimate.

---

## Step 1: Google Cloud project (one time)

> **Already done for you** in project `clipbridge-509311` (account ashimayed2@gmail.com), and the Chrome
> Client ID is already inside `manifest.json`. Skip to Step 2. The steps below are for reference.

1. Go to <https://console.cloud.google.com/> and sign in with the Google account you'll use on both devices.
2. Top bar, **Select a project**, then **New project**. Name it `ClipBridge`, click **Create**, then select it.
3. **APIs & Services → Library**, search **Google Drive API**, and click **Enable**.
4. **APIs & Services → OAuth consent screen** (it may appear as **Google Auth Platform**):
   - User type: **External**. App name: `ClipBridge`. Support and developer email: yours.
   - **Data access / Scopes**: add `https://www.googleapis.com/auth/drive.appdata`.
   - **Audience / Test users**: add your own Gmail address.
5. **Credentials → Create credentials → OAuth client ID**, twice:
   - **Android**: package name `app.clipbridge`, SHA-1 from the table above.
   - **Chrome Extension**: Item ID `oklffclikimjimhihbkaoddchdndlhdj`. Copy the **Client ID** it shows.
6. Open `chrome-extension/manifest.json` and replace
   `PASTE_YOUR_CHROME_CLIENT_ID.apps.googleusercontent.com` with that Client ID.

Both clients must be in the **same project**. That's what lets the phone and Chrome see the same hidden folder.

> **Weekly re-login:** while the consent screen is in *Testing*, Google may expire the sign-in
> after about 7 days, and ClipBridge shows a silent "Sign in again" notice. To avoid that, set the
> publishing status to **In production**. With only the `drive.appdata` scope this normally
> needs no review. You'll see an "unverified app" screen once; click **Advanced → Go to ClipBridge**.

---

## Step 2: Install the Chrome extension

1. Chrome must be **signed in** to the same Google account (profile icon, top right).
2. Open `chrome://extensions` and turn on **Developer mode** (top right).
3. Click **Load unpacked** and select the `chrome-extension` folder. Check the ID is `oklffclikimjimhihbkaoddchdndlhdj`.
4. Pin ClipBridge (puzzle-piece icon, then pin it), click it, and **Sign in with Google**.
5. **Set your passphrase.** At least 10 characters; a few random words work well. Write it down somewhere safe.
   The popup then shows a **key check** code, such as `7623-F683`.

Leave the folder where it is. Chrome loads the extension from it every time.

---

## Step 3: Get the Android app

**Option A: let GitHub build it (no software to install)**

1. At <https://github.com>, click **New repository**, choose **Private**, then **Create**.
2. Click **uploading an existing file** and drag in *everything inside* this `clipbridge` folder, then **Commit**.
   GitHub's web uploader **refuses folders that start with a dot** (it says "This file is hidden"), so the `.github`
   folder will not upload this way. Add it in the next step instead.
   **2b.** In the repo, click **Add file → Create new file**. In the name box type `.github/workflows/build-android.yml`
   (typing the slashes creates the folders). Open `.github/workflows/build-android.yml` from your folder in Notepad,
   copy everything, paste it into GitHub, and click **Commit changes**.
3. Open the **Actions** tab. *Build Android APK* runs by itself in about 5 minutes. When it shows a green tick,
   either download **ClipBridge-apk** from the run's Artifacts, or grab `ClipBridge.apk` from the
   repo's **Releases** page (each successful build publishes one, numbered 0.1, 0.2, …).
4. Move `ClipBridge.apk` to your phone and tap it to install (allow installs from that source once).

**Option B: Android Studio.** Open the `android-app` folder, wait for the sync, then **Build → Build Bundle(s) / APK(s) → Build APK(s)**,
or run `./gradlew assembleRelease`.

The release build can't be debugged over USB, so someone with your phone and a cable can't peek inside the app.

---

## Step 4: Set up the phone

1. Open **ClipBridge**, tap **Sign in with Google**, and choose the same account as Chrome.
2. Enter the **same passphrase** as in Chrome. The phone shows its key check code, which **must match Chrome's**.
   If the phone finds clips from Chrome that this passphrase can't open, it warns you before saving.
3. Allow notifications when asked.
4. **Settings → Run in background → Allow.** On Xiaomi, Oppo, Vivo, Samsung and similar phones, also set the app's
   battery setting to **No restrictions / Unrestricted**, or the phone may stop it.
5. **Settings → Quick Settings tile** adds the **Send clipboard** tile (on older Android, add it from the shade's pencil button).

---

## Daily use

| You do this | What happens |
|---|---|
| Copy text on the PC | It's on the phone's clipboard in a few seconds, with a silent notice. Just paste. |
| Copy a screenshot on the PC | It's on the phone's clipboard as an image (see limits). |
| Copy on the phone | Swipe down and tap **Send clipboard**, or use the big button in the app. |
| Share a photo or file on the phone | **Share → Send with ClipBridge.** It lands in the PC's `Downloads/ClipBridge`. |
| Send a file from the PC | ClipBridge icon → **Files**, or press Ctrl+V on an image inside the popup. |
| Copy a password on the PC | Flip **Auto-send** off in the popup first. Nothing copied is uploaded until you flip it back. |
| Right-click on a web page | **Send selected text / link / this page to phone** (works even while auto-send is paused). |
| Need an older clip | The last 10 are listed in the app and the popup. Tap one to copy it again, or type in **Search**. |
| Keep a clip around | Tap the pin icon on it (up to 10 pinned). Pinned clips never get pushed out by newer ones. |
| Set up a second phone or PC | In Chrome, **Settings → Set up another device** shows a QR code. On the new phone, tap **Scan the QR code from Chrome** on the passphrase screen instead of typing it. |
| Make it check more or less often | **Settings → Sync speed**: Instant, Balanced, or Battery saver. |
| Something isn't syncing and you want to ask for help | **Settings → Copy debug log** (phone) or **Copy debug info** (Chrome). Safe to paste into an email — it never contains your passphrase or clip content. |

---

## Sync speed

There's no server pushing updates, so each device checks Drive on its own. Three speeds, changeable anytime in Settings:

| Mode | While active | While idle |
|---|---|---|
| **Instant** | Every 3 seconds | Every 3 seconds, always |
| **Balanced** (default) | Every 3 seconds | Slows to every 20 seconds after 10 minutes of nothing happening |
| **Battery saver** | Every 4 seconds | Slows to every 45 seconds after 2 minutes |

"Active" means something happened recently: a copy, a paste, opening the app, or the *other* device
being busy. Balanced suits most people; pick Battery saver on a phone you're trying to make last, or
Instant if a few extra seconds ever bothers you.

---

## Setting up a second device with a QR code

Useful once you already have ClipBridge working on one phone and Chrome, and want to add another
device without typing the passphrase again.

1. On the PC that's already set up, open the ClipBridge popup → **Settings → Set up another device**.
   A QR code appears, with a countdown — it hides itself after 60 seconds either way.
2. On the new phone, open ClipBridge, sign in with the **same Google account**, and on the passphrase
   screen tap **Or scan the QR code from Chrome**.
3. Google's own scanner opens (ClipBridge never requests camera access itself). Point it at the code.
4. The phone checks the code is for the account you just signed in with, then finishes setup on its own.

The code only carries what's needed to unlock your clips — nothing goes to Google or anywhere else
during the scan.

---

## Security, in plain words

**What someone would need to read your clips:** your passphrase. Google, Drive, and anyone
who gets into your Google account only see random bytes, a random filename, and file sizes.

- **Encryption:** AES-256-GCM with a key made from your passphrase (PBKDF2-SHA256, 310,000 rounds, salted with your
  account). Every clip, including its real filename and type, is encrypted on the device before upload.
- **Tampering:** every clip is sealed. If anyone changes, reorders, cuts, or plants a clip in your Drive, the apps
  detect it and refuse it, with a silent warning. They can't slip a fake file into your Downloads.
- **Another person's session:** each Google account has its own hidden folder, and only this app, with your sign-in,
  can reach yours. Someone else's ClipBridge, even on the same computer, sees only their own clips. Signing out
  also erases the passphrase key from that device, so the next person can't inherit it.
- **On the phone:** the key is locked inside Android's hardware-backed Keystore. It's never in backups or
  device-to-device transfers, and it isn't readable from the app's files. The part that reads the clipboard can't be
  started by other apps; they can only hand things to ClipBridge through Share.
- **Changing the code:** Android only installs updates signed with *your* key, so nobody else can replace the app.
  The Chrome extension's code lives on your PC, so protect the PC with a Windows password. Only extension pages can
  talk to the background worker, and it runs no remote code.
- **The debug log:** every line is scrubbed before it's stored — no email address, no access token, no Drive file
  ID, nothing long enough to be a key or a hash. Only short, generic notes like "Sent a clip" or "Poll failed:
  offline". It's meant to be safe to paste into an email or a public bug report.
- **Pinning:** a pin is stored as a plain (unencrypted) flag in Drive's metadata, alongside the size and timestamp
  Drive already shows. It never reveals what a clip contains — only that it's marked to stick around.
- **QR pairing:** the code shown in Chrome is only your existing encryption key, re-packaged — it grants nothing
  beyond what your passphrase already does, and it's on screen for 60 seconds at most.
- **Limits:** the passphrase can't be recovered. If you forget it, choose a new one on both devices, and old
  clips just show as locked until they age out. Anyone using your unlocked PC or phone can use ClipBridge, just like any other app.

Technical format details are at the top of `android-app/.../Crypto.kt` and `chrome-extension/crypto.js`.

---

## Honest limits

- **Phone → PC needs one tap.** Since Android 10, apps can't read the clipboard in the background, so every
  clipboard app (Clipt included) needs a tap or Share step on Android. PC → phone is fully automatic.
- **Speed:** with no server to push updates, each side checks Drive on its own, at a pace set by **Sync speed**
  (see above) — as often as every 3 seconds, slower when nothing's happening. The phone pauses checking while its
  screen is off. When the connection drops, it backs off, then catches up (including anything sent while offline)
  as soon as the network returns.
- **Chrome must be running** for the PC side (a minimized window is fine).
- **Images copied on the PC** are detected with a Chrome technique that couldn't be tested outside a real browser.
  If it doesn't pick them up, paste them into the popup. Images over ~26 MB are never auto-sent.
- **Files copied in File Explorer** (Ctrl+C on a file) can't be read by Chrome. Use **Files** in the popup.
- **Very long text** (over 256 KB on the phone, 5 MB in Chrome) is too big for a clipboard, so it's saved as a `.txt` file in Downloads/ClipBridge.
- **Pinning caps out at 10.** Past that, unpin one before pinning another. Total clips in Drive can reach 20
  (10 recent + 10 pinned) rather than 10.
- **QR pairing only goes one way:** Chrome shows the code, the phone scans it. There's no way yet to show a code
  on the phone for a second PC to scan — set that PC up by typing the passphrase instead.
- **The first phone scan needs Google Play Services.** It provides the scanner Google supplies (no camera
  permission needed by ClipBridge itself); on most phones this is already installed, but it may briefly download
  the scanner module the very first time it's used.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Phone sign-in fails or closes instantly | SHA-1 or package name in the Android OAuth client doesn't match the table. It can take a few minutes after creating it. |
| Chrome says "bad client id" | The Client ID in `manifest.json` is wrong. After editing it, click reload on `chrome://extensions`. |
| Chrome says "user is not signed in" | Sign Chrome itself into your Google account (profile icon). |
| "Access blocked: app has not completed verification" | Add your email under **Test users** (Step 1.4). |
| "A clip couldn't be unlocked" / clips show as **Locked** | The passphrases differ. Compare the key check codes; change the passphrase on one device to match. |
| Actions tab is empty / "Get started" | The `.github/workflows` file is missing. Create it with **Add file → Create new file** (Step 3, 2b). |
| Build fails with `Failed to find package 'tools'` | The workflow is missing `packages: 'platform-tools'` under `setup-android` (already fixed in this version). |
| Phone stops receiving after a while | Battery optimization stopped it. Redo Step 4.4. |
| Nothing arrives anywhere | Same Google account on both devices? Both OAuth clients in the same Cloud project? |
| "Couldn't open the scanner" when scanning a QR code | Update Google Play Services from the Play Store, then try again. |
| QR code says it's for the wrong account | You're signed into ClipBridge with a different Google account than the one that made the code. Sign in with the matching account first. |
| "You can pin up to 10 clips" | Unpin one (tap its pin icon again) before pinning another. |

Debug logs: phone uses **Settings → Copy debug log**, or `adb logcat -s ClipBridge` for more detail. PC uses **Settings → Copy debug info** in the popup, or `chrome://extensions` → ClipBridge → **service worker** → Inspect for the full console.

---

Font: Onest, © 2021 The Onest Project Authors (github.com/googlefonts/onest), under the SIL Open Font License (`FONT-LICENSE-OFL.txt`, `chrome-extension/fonts/OFL.txt`).

QR code generator: © 2009 Kazuhiko Arase, under the MIT License (`chrome-extension/QR-LICENSE.txt`).
