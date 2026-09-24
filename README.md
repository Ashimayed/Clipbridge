# ClipBridge

**Copy something on your computer — it's waiting on your phone in a few seconds. Copy on your phone — it's waiting back on your computer.** No cables, no new accounts, and nothing stored on anyone else's server: your clips travel through a hidden folder in **your own Google Drive**, scrambled before they ever leave your device.

- **Private by design.** Everything is end-to-end encrypted with a phrase only you know. Google — and anyone who somehow got into your Drive — would only see scrambled data, never your actual clips or file names.
- **Keeps the last 10 clips**, plus up to 10 you pin so they never age out. A search box finds an older one fast.
- **Text, photos, or any file** — no size limit beyond your own Drive space. Big files stream over in pieces.
- **Quiet by default.** No sound, no vibration, no pop-up when something new arrives.
- **Works everywhere** — phone, tablet, foldable, light mode, dark mode.
- **You control the pace.** Check constantly for instant sync, or back off when idle to save battery.
- **Adding a second phone or laptop later is one QR scan away** — no retyping your phrase.
- **A "copy debug log" button** gives you something safe to hand over if you ever need help — never your phrase or your clips.

**The fastest way to get started:** open **[ashimayed.github.io/Clipbridge](https://ashimayed.github.io/Clipbridge/)** and press the two Download buttons — one for your computer, one for your phone. The rest of this page walks through the same two steps in more detail, for anyone who'd rather stay right here.

Setup takes about 5 minutes, once.

---

## 1. Add it to Chrome (your computer)

ClipBridge isn't on the Chrome Web Store yet, so it installs by hand — it only takes about a minute, and Chrome walks you through it:

1. Download **[ClipBridge-Chrome-Extension.zip](https://raw.githubusercontent.com/Ashimayed/Clipbridge/main/ClipBridge-Chrome-Extension.zip)**.
2. **Unzip it** somewhere you won't delete later — Chrome loads the extension from this folder every time it starts.
3. In Chrome, go to **chrome://extensions** (type or paste that into the address bar and press Enter).
4. Turn on **Developer mode**, top right. This is just Chrome's way of allowing extensions that aren't from the Web Store yet — it doesn't change anything else about your browser.
5. Click **Load unpacked** and select the unzipped **ClipBridge-Chrome-Extension** folder.
6. Click the puzzle-piece icon in Chrome's toolbar and **pin ClipBridge** so it's always one click away. Click it, then **sign in with Google**.

## 2. Add it to your phone (Android)

1. On your phone, open **[this link](https://github.com/Ashimayed/Clipbridge/releases/latest/download/ClipBridge.apk)** to download `ClipBridge.apk`. (Reading this on your computer? Send yourself the link, or email/AirDrop the file over, then open it on the phone.)
2. Tap the downloaded file. Android will warn you it's blocking installs from this source — that's just Android being cautious about anything from outside the Play Store. Tap **Settings**, allow this source, then go back and tap **Install**. You're always free to turn that setting back off afterward.
3. Open **ClipBridge**, tap **Sign in with Google**, and choose the **same account** you used in Chrome.

## 3. Link them with one phrase

The last step ties your computer and phone together with a passphrase you make up yourself — think of it as the key that scrambles your clips before they ever leave your device.

- Pick something at least 10 characters long. A few random words work great (something like `purple couch tuesday` — just don't reuse this exact one). Save it somewhere safe, like a password manager, since **nobody, including us, can recover it if you forget it** — you'd simply set a new one and start fresh.
- In the **Chrome popup**, enter your passphrase. A short check code appears, like `7623-F683`.
- On your **phone**, enter the *exact same* passphrase. Its check code should match Chrome's — that's how both devices confirm they agree.
- Allow notifications when your phone asks. When it asks about running in the background, choose **Allow** — on Xiaomi, Oppo, Vivo, or Samsung phones, it also helps to set the app's battery setting to **No restrictions**, so the phone doesn't pause it to save power.

That's it — copy something on one device, and it should land on the other within a few seconds. 🎉

---

## Using it day to day

| You do this | What happens |
|---|---|
| Copy text on the computer | It's on your phone's clipboard in a few seconds, with a silent notice — just paste. |
| Copy a screenshot on the computer | It arrives on the phone as an image. |
| Copy something on the phone | Swipe down and tap **Send clipboard**, or use the button in the app. |
| Share a photo or file from the phone | **Share → Send with ClipBridge** — it lands in the computer's `Downloads/ClipBridge`. |
| Send a file from the computer | ClipBridge icon → **Files**, or paste an image straight into the popup. |
| Copying a password | Flip **Auto-send** off in the popup first — nothing uploads until you flip it back on. |
| Right-click on a web page | **Send selected text / link / this page to phone** — works even while auto-send is paused. |
| Need an older clip | The last 10 are listed in the app and the popup. Tap one to copy it again, or use **Search**. |
| Want to keep a clip around | Tap its pin icon (up to 10 pinned). Pinned clips never get pushed out by newer ones. |
| Setting up a second phone or computer | In Chrome: **Settings → Set up another device** shows a QR code — scan it from the new phone's passphrase screen instead of retyping. |
| Want it to check more or less often | **Settings → Sync speed**: Instant, Balanced, or Battery saver. |
| Something's not syncing and you'd like help | **Settings → Copy debug log** (phone) or **Copy debug info** (Chrome) — safe to paste into an email, it never contains your passphrase or clip content. |

## How fast it syncs

There's no server pushing updates to your devices — each one checks Drive on its own, at a pace you choose in **Settings → Sync speed**:

| Mode | While you're using it | While it's quiet |
|---|---|---|
| **Instant** | Every 3 seconds | Every 3 seconds, always |
| **Balanced** (default) | Every 3 seconds | Slows to every 20 seconds after 10 quiet minutes |
| **Battery saver** | Every 4 seconds | Slows to every 45 seconds after 2 quiet minutes |

Balanced suits most people. Pick **Battery saver** for a phone you're trying to stretch through the day, or **Instant** if a few extra seconds ever bothers you.

## Adding another phone or computer later

Once you already have ClipBridge working on one phone and in Chrome, adding another device takes one scan — no retyping your passphrase.

1. On the computer that's already set up, open the ClipBridge popup → **Settings → Set up another device**. A QR code appears (it disappears on its own after 60 seconds either way).
2. On the new phone, open ClipBridge, sign in with the **same Google account**, and on the passphrase screen tap **Or scan the QR code from Chrome**.
3. Google's own scanner opens — ClipBridge never asks for camera access itself. Point it at the code.
4. The phone checks the code matches the account you just signed in with, then finishes on its own.

Nothing is sent to Google or anywhere else during the scan — the code only carries what's needed to unlock your own clips.

---

## How your privacy is protected

In plain language, no computer science degree required: **the only thing that can unlock your clips is your passphrase.** Google, Drive, and anyone who somehow gets into your Google account see nothing but random bytes, a random file name, and a file size.

- **Encryption:** AES-256, with a key built from your passphrase (PBKDF2-SHA256, 310,000 rounds, salted with your account). Every clip — including its real file name and type — is encrypted on your device before it's ever uploaded.
- **Tampering:** every clip is sealed shut. If anyone changed, reordered, or planted something in your Drive folder, both apps notice and quietly refuse it — nobody can slip a fake file into your Downloads.
- **Other people, same computer:** each Google account gets its own hidden folder, reachable only by this app while signed in as you. Someone else's ClipBridge — even on your computer — only ever sees their own clips. Signing out also wipes the passphrase key from that device.
- **On your phone:** the key lives inside Android's hardware-backed Keystore. It's never included in backups or device transfers, and nothing in the app's files can read it out. Other apps can't reach ClipBridge's clipboard-reading code directly — only through the normal Share screen.
- **Nobody else can push you a fake update:** Android only installs updates signed with the same key as the one already on your phone. The Chrome extension's code lives on your computer, so a Windows/Mac password is what protects that side.
- **The debug log is scrubbed** before it's ever shown — no email address, no access token, no file ID, nothing long enough to be a key. Just short, generic notes like "Sent a clip" or "Poll failed: offline." Safe to paste into an email.
- **A pin** is stored as a plain flag in Drive's metadata, next to the size and timestamp Drive already shows — it says a clip is marked to stick around, nothing about what's in it.
- **The QR pairing code** is just your existing key, repackaged — it can't do anything your passphrase couldn't already do, and it's on screen for 60 seconds at most.

## Good to know

A few honest limits, so nothing catches you off guard:

- **Phone → computer needs one tap.** Since Android 10, no app can read the clipboard silently in the background — every clipboard app needs a tap or a Share step on Android. Computer → phone is fully automatic.
- **Speed** depends on your chosen **Sync speed** (above) — as often as every 3 seconds, slower when nothing's happening, and the phone pauses checking while its screen is off. If the connection drops, it catches up (including anything sent while offline) as soon as it's back.
- **Chrome needs to be running** for the computer side — a minimized window is fine.
- **Screenshots copied on the computer** are picked up automatically in most cases; if one doesn't appear, paste it into the popup by hand. Images over ~26 MB don't auto-send.
- **Files copied in File Explorer** (Ctrl+C on a file, not a copied screenshot) can't be read by Chrome — use **Files** in the popup instead.
- **Very long text** (over 256 KB on the phone, 5 MB in Chrome) is too big for a clipboard, so it's saved as a `.txt` file in `Downloads/ClipBridge` instead.
- **Pinning caps out at 10** — unpin one before pinning another.
- **QR pairing is one-way for now:** Chrome shows the code, the phone scans it. To set up a second computer, type the passphrase there instead.
- **The very first phone scan** needs Google Play Services (already on almost every phone) — it may briefly download its scanner module the first time it's used.

## If something's not working

| Problem | Try this |
|---|---|
| Phone sign-in fails or closes instantly | Give it a few minutes after first installing — Google sign-in can take a moment to catch up. |
| "Access blocked: app has not completed verification" | This app is still in testing — [email us](mailto:ashimayed2@gmail.com) with the Google account you'd like added. |
| "A clip couldn't be unlocked" / clips show as **Locked** | The passphrases don't match on both devices. Compare the check codes, then set the same passphrase on both. |
| Phone stops receiving after a while | Battery optimization likely paused it — recheck the background/battery setting from Step 2. |
| Nothing arrives anywhere | Double-check both devices are signed into the **same** Google account. |
| "Couldn't open the scanner" when scanning a QR code | Update Google Play Services from the Play Store, then try again. |
| QR code says it's for the wrong account | You're signed into ClipBridge with a different Google account than the one that made the code — sign in with the matching one first. |
| "You can pin up to 10 clips" | Unpin one (tap its pin icon again) before pinning another. |

Debug logs, for anyone helping you troubleshoot: phone uses **Settings → Copy debug log** (or `adb logcat -s ClipBridge` for more detail); computer uses **Settings → Copy debug info** in the popup, or `chrome://extensions` → ClipBridge → **service worker** → Inspect for the full console.

---

## Want to run your own separate copy?

Everything above uses one already-configured, shared ClipBridge project — you never need to touch Google Cloud or GitHub. But if you'd rather have a fully independent copy, with your own signing key and your own Google Cloud project, here's how. This part is for developers; nobody installing ClipBridge for themselves needs to read it.

### This copy's fixed IDs

| What | Value |
|---|---|
| Android package name | `app.clipbridge` |
| Android SHA-1 fingerprint | `49:31:AA:9F:FF:7F:F3:85:03:19:7D:81:D2:AB:1A:B5:AC:0B:9E:DC` |
| Chrome extension ID | `oklffclikimjimhihbkaoddchdndlhdj` |

The Android SHA-1 stays fixed because every build uses the same signing key — but the keystore itself is never committed to this repo, and its password never appears in any source file. It lives only in:

- **GitHub Actions secrets** (`CLIPBRIDGE_KEYSTORE_BASE64`, `CLIPBRIDGE_KEYSTORE_PASSWORD`, `CLIPBRIDGE_KEY_ALIAS`) — Settings → Secrets and variables → Actions — used by CI to sign release builds.
- Optionally, a local `android-app/keystore.properties` file (copy it from `keystore.properties.example` and fill in your own values) if you want to build locally. This file is gitignored.

If you fork or copy this project, **generate your own keystore** (`keytool -genkeypair ...`) rather than reusing this one — whoever holds a keystore file plus its password can sign updates that any device already running that app will accept as legitimate.

### Setting up your own Google Cloud project

1. Go to <https://console.cloud.google.com/> and sign in with the Google account you'll use on both devices.
2. Top bar, **Select a project**, then **New project**. Name it `ClipBridge`, click **Create**, then select it.
3. **APIs & Services → Library**, search **Google Drive API**, and click **Enable**.
4. **APIs & Services → OAuth consent screen** (may appear as **Google Auth Platform**):
   - User type: **External**. App name: `ClipBridge`. Support and developer email: yours.
   - **Data access / Scopes**: add `https://www.googleapis.com/auth/drive.appdata`.
   - **Audience / Test users**: add your own Gmail address.
5. **Credentials → Create credentials → OAuth client ID**, twice:
   - **Android**: your own package name and your own keystore's SHA-1.
   - **Chrome Extension**: Item ID `oklffclikimjimhihbkaoddchdndlhdj` (fixed by the extension's manifest key, so it stays the same even for a fork). Copy the **Client ID** it shows.
6. Open `chrome-extension/manifest.json` and replace `PASTE_YOUR_CHROME_CLIENT_ID.apps.googleusercontent.com` with that Client ID.

Both clients must be in the **same project** — that's what lets your phone and Chrome see the same hidden folder.

> **Weekly re-login:** while the consent screen is in *Testing*, Google may expire sign-in after about 7 days. To avoid that, set the publishing status to **In production** — with only the `drive.appdata` scope this normally needs no review. You'll see an "unverified app" screen once; click **Advanced → Go to ClipBridge**.

### Building your own APK

**Option A — let GitHub build it for you.** Fork this repo (or push it to a new one of your own), add the three keystore secrets above under **Settings → Secrets and variables → Actions**, and push a commit. The included GitHub Actions workflow builds and signs the APK automatically and publishes it to that repo's own **Releases** page.

**Option B — Android Studio.** Open the `android-app` folder, wait for the sync, then **Build → Build Bundle(s) / APK(s) → Build APK(s)**, or run `./gradlew assembleRelease`.

Either way, the release build can't be debugged over USB, so someone with your phone and a cable can't peek inside the app.

---

Font: Onest, © 2021 The Onest Project Authors (github.com/googlefonts/onest), under the SIL Open Font License (`FONT-LICENSE-OFL.txt`, `chrome-extension/fonts/OFL.txt`).

QR code generator: © 2009 Kazuhiko Arase, under the MIT License (`chrome-extension/QR-LICENSE.txt`).
