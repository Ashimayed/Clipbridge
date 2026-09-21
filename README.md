# ClipBridge

Your own Clipt: copy on your PC, paste on your Android phone, and the other way round.
Everything travels through a hidden folder in **your own Google Drive**, so there is no
server to host, no Wi-Fi pairing, and no local network needed.

- Everything is **end-to-end encrypted** with a passphrase only you know. Google and anyone who gets into your Drive see scrambled data, not your clips or filenames.
- Only the **last 10 clips** are kept. Older ones are deleted automatically.
- Text, images, and any other file are supported, with no size cap beyond your Drive space. Big files stream in pieces.
- Notifications are **silent**: no sound, no vibration, no pop-up.
- Works on phones, tablets, foldables, and landscape, in light and dark mode.

Setup takes about 20 minutes, once.

---

## Your fixed IDs (you'll paste these into Google Cloud)

| What | Value |
|---|---|
| Android package name | `app.clipbridge` |
| Android SHA-1 fingerprint | `D2:27:34:F8:50:42:8A:34:E8:09:95:60:23:D7:80:FD:36:7B:ED:CD` |
| Chrome extension ID | `oklffclikimjimhihbkaoddchdndlhdj` |

These stay fixed because the signing key (`android-app/app/clipbridge.keystore`) and the
extension key (inside `manifest.json`) are included. They were generated just for you.
**Keep this folder private.** If you put it on GitHub, make the repository **Private**.

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
2. Click **uploading an existing file** and drag in *everything inside* this `clipbridge` folder,
   including the hidden `.github` folder, then **Commit**. (Turn on "show hidden files" if you can't see `.github`.)
3. Open the **Actions** tab. *Build Android APK* runs by itself in about 5 minutes. When it shows a green tick,
   open the run and download **ClipBridge-apk** at the bottom.
4. Unzip it to get `app-release.apk`, move it to your phone, and tap it to install (allow installs from that source once).

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
| Need an older clip | The last 10 are listed in the app and the popup. Tap one to copy it again. |

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
- **Limits:** the passphrase can't be recovered. If you forget it, choose a new one on both devices, and old
  clips just show as locked until they age out. Anyone using your unlocked PC or phone can use ClipBridge, just like any other app.

Technical format details are at the top of `android-app/.../Crypto.kt` and `chrome-extension/crypto.js`.

---

## Honest limits

- **Phone → PC needs one tap.** Since Android 10, apps can't read the clipboard in the background, so every
  clipboard app (Clipt included) needs a tap or Share step on Android. PC → phone is fully automatic.
- **Speed:** with no server to push updates, each side checks Drive every 3–4 seconds, so expect a 2–6 second delay.
  The phone pauses checking while its screen is off. When the connection drops, it backs off, then catches up
  (including anything sent while offline) as soon as the network returns.
- **Chrome must be running** for the PC side (a minimized window is fine).
- **Images copied on the PC** are detected with a Chrome technique that couldn't be tested outside a real browser.
  If it doesn't pick them up, paste them into the popup. Images over ~26 MB are never auto-sent.
- **Files copied in File Explorer** (Ctrl+C on a file) can't be read by Chrome. Use **Files** in the popup.
- **Very long text** (over 256 KB on the phone, 5 MB in Chrome) is too big for a clipboard, so it's saved as a `.txt` file in Downloads/ClipBridge.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Phone sign-in fails or closes instantly | SHA-1 or package name in the Android OAuth client doesn't match the table. It can take a few minutes after creating it. |
| Chrome says "bad client id" | The Client ID in `manifest.json` is wrong. After editing it, click reload on `chrome://extensions`. |
| Chrome says "user is not signed in" | Sign Chrome itself into your Google account (profile icon). |
| "Access blocked: app has not completed verification" | Add your email under **Test users** (Step 1.4). |
| "A clip couldn't be unlocked" / clips show as **Locked** | The passphrases differ. Compare the key check codes; change the passphrase on one device to match. |
| Phone stops receiving after a while | Battery optimization stopped it. Redo Step 4.4. |
| Nothing arrives anywhere | Same Google account on both devices? Both OAuth clients in the same Cloud project? |

Debug logs: phone uses `adb logcat -s ClipBridge`. PC uses `chrome://extensions`, then ClipBridge, then **service worker**, then Inspect.

---

Font: Onest, © 2021 The Onest Project Authors (github.com/googlefonts/onest), under the SIL Open Font License (`FONT-LICENSE-OFL.txt`, `chrome-extension/fonts/OFL.txt`).
