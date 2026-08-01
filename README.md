# Auldwyn Portrait Sync (Android)

Downloads player portraits from the Auldwyn Dropbox folder (including any
nested inside `.zip`, `.7z`, or `.rar` files) and copies the `.tga` files
into a folder you pick on your Android device. Tap a button whenever you
want fresh portraits.

This is the Android counterpart to the
[desktop Auldwyn Portrait Sync tool](https://github.com/Ir0nWaffle/auldwyn-portrait-sync) —
same Dropbox source, same idea, rebuilt natively for Android since NWN:EE
itself runs on Android.

## For players: install and run

1. Go to the [Releases page](../../releases) of this repo and download
   `AuldwynPortraitSync.apk`.
2. Since this isn't on the Play Store, Android will ask you to allow
   installing from this source the first time — approve that for your
   browser/file manager app.
3. Install and open [Shizuku](https://shizuku.rikka.app/) (free, no root
   required) and start it via its wireless-debugging pairing flow — this
   is a one-time setup. See Shizuku's own in-app instructions.
4. Open Auldwyn Portrait Sync, tap **NWN:EE Folder**, and grant it the
   Shizuku permission when prompted. The destination is now set to
   `Android/data/com.beamdog.nwnandroid/files/user/portraits` — the same
   folder Beamdog's own instructions have players use for custom
   portraits.
5. Tap **Sync Now**. The destination is remembered for next time.

Android 11+ blocks every app — including ones with "All files access" —
from reading or writing another app's `Android/data` folder directly;
that restriction can't be granted away via a normal permission dialog.
Shizuku works around it by running the file write through a
shell-privileged helper process instead of the app's own sandboxed
process, which is exempt from that restriction. If you'd rather not use
Shizuku, tap **Choose Folder...** instead and pick any other folder (this
uses Android's normal folder picker) — you'll then need to move files
from there into the NWN:EE folder yourself, e.g. via a computer over
USB.

## For the repo owner: how the build works

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the APK
on GitHub's own servers whenever a tag matching `v*.*.*` is pushed, and
attaches it to a new GitHub Release.

```
git add .
git commit -m "Update sync tool"
git tag v1.0.0
git push origin main --tags
```

### Signing (do this once)

Android requires every release of an app to be signed with the *same*
key, or updates get treated as a different app. The workflow will
generate a throwaway keystore automatically if none is configured, so the
first build works out of the box — but you should switch to a persistent
one:

1. Push a tag (or run the workflow manually via **Actions** → **Build
   APK** → **Run workflow**) with no keystore secrets configured yet.
2. Download the `SAVE-THIS-keystore` artifact from that run.
3. Base64-encode it and add these as **repo secrets** (Settings → Secrets
   and variables → Actions):
   - `ANDROID_KEYSTORE_BASE64` — output of `base64 -w0 keystore-SAVE-ME.jks`
   - `ANDROID_KEYSTORE_PASSWORD` — `auldwyn123` (from the workflow's
     default, unless you regenerate your own keystore with a different
     password)
   - `ANDROID_KEY_ALIAS` — `auldwyn`
   - `ANDROID_KEY_PASSWORD` — `auldwyn123`
4. **Back up that keystore file somewhere safe** — if it's lost, you can
   never publish an update under the same app identity again; players
   would have to uninstall and reinstall from scratch.

From then on, every build reuses the same keystore automatically.

## Notes

- The Dropbox link is hardcoded near the top of `PortraitSync.kt`. If it
  ever changes, update it there, commit, and push a new tag.
- `.zip` uses the JVM's built-in unzip support, `.7z` uses Apache Commons
  Compress, and `.rar` uses junrar — all pure Java/Kotlin, no bundled
  native binary needed (unlike the desktop build, where `.rar` support
  requires shelling out to 7-Zip).
