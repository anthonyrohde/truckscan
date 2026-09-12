# Releasing a build people can install

A debug APK is fine for your own phone and wrong for anyone else's. Android
signs debug builds with a throwaway key that is generated per machine, so an
update built on a different computer — or by CI — will refuse to install over
it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only fix at that point is
uninstalling and losing whatever the app had saved.

A release build fixes that by signing with a key you keep. Every future build
signed with the same key installs cleanly over the last one.

## One-time: make a signing key

Do this **once**, on a machine you control, and then never again. If you lose
this file you cannot ship an update that installs over what your friends
already have — they would have to uninstall first.

    keytool -genkeypair -v \
      -keystore truckscan-release.jks \
      -alias truckscan \
      -keyalg RSA -keysize 4096 \
      -validity 10000 \
      -storetype PKCS12

On Windows, `keytool` ships with the JDK you already installed. Supplying
`-dname` skips six prompts for a name and location that nobody verifies — it
is a self-signed certificate, and Android only cares that the same key signed
both builds:

    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
      -keystore truckscan-release.jks `
      -alias truckscan `
      -keyalg RSA -keysize 4096 `
      -validity 10000 -storetype PKCS12 `
      -dname "CN=Your Name, O=Truck Scan, C=AU"

It then asks for a password, twice. **PKCS12 keystores use one password for
both the store and the key** — keytool will not let them differ — so whatever
you type here is the value for both `KEYSTORE_PASSWORD` and `KEY_PASSWORD`
later.

`-validity 10000` is about 27 years. Shorter is a trap: once the certificate
expires you cannot sign an update that installs over the old one either.

**Back the file up somewhere that is not this repository and not the phone.**
A password manager attachment or an encrypted drive. It is unrecoverable.

## One-time: give CI the key

CI builds the release APK, so it needs the keystore — but the keystore must
never be committed. It goes in as an encrypted secret instead, base64-encoded
because GitHub secrets hold text.

Encode it:

    base64 -w0 truckscan-release.jks > truckscan-release.jks.b64     # Linux
    base64 -i truckscan-release.jks -o truckscan-release.jks.b64     # macOS

PowerShell, straight onto the clipboard so there is no second copy of the key
lying around to forget about:

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("truckscan-release.jks")) `
      | Set-Clipboard

Then in the public repository: **Settings → Secrets and variables → Actions →
New repository secret**, four times:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 text from the step above |
| `KEYSTORE_PASSWORD` | the password you chose |
| `KEY_ALIAS` | `truckscan` |
| `KEY_PASSWORD` | the same password again — PKCS12 permits only one |

If you wrote the base64 to a file rather than the clipboard, delete the file
afterwards. It is the keystore in a form anybody can read.

The workflow writes the keystore to the runner's temporary directory — outside
the checkout, so nothing the build archives can pick it up — and deletes it in
a step marked `if: always()`, which runs even when the build fails.

## Cutting a release

Releases are triggered by pushing a version tag, so publishing is a deliberate
act rather than something that happens on every commit. The rolling
`truckscan-debug` build already covers day-to-day testing.

1. Set the version in `app/build.gradle.kts`:

       versionCode = 2          // must increase every release; Android
                                // refuses to install a lower code over a higher one
       versionName = "0.2.0"    // what people see

2. Commit that, then tag and push:

       git tag v0.2.0
       git push origin main
       git push origin v0.2.0

3. Watch the run under **Actions → Build signed release APK**. It runs the
   reference check and the core protocol tests first, so a broken build fails
   before it can produce an APK anyone downloads.

4. When it finishes, the release appears under **Releases** with
   `truckscan-v0.2.0.apk` attached.

### If you cannot push a tag

Some credentials are allowed to push branches and refused on `refs/tags`
entirely. Two fallbacks, both cutting exactly the same release:

- **Push a release branch.** `git push origin main:refs/heads/release/v1.0.0`
  needs only branch permission. The branch is disposable — delete it once the
  release exists; the tag is what persists.
- **Start the workflow by hand.** **Actions → Build signed release APK → Run
  workflow**, with the version in the box.

Either way the job creates the tag against the commit it built, so the result
is identical to a tag push. The version is checked against a pattern first — a
typo would otherwise become a permanent public tag.

A release that already exists is never overwritten; the run fails instead.
Re-pushing a release branch is an easy accident, and silently replacing an APK
people have already downloaded is not something you can take back.

The workflow verifies the APK is actually signed, with
`apksigner verify --print-certs`, before publishing it. An unsigned or
debug-signed APK fails the run instead of reaching a release page — the
failure mode this whole document exists to prevent.

## What your friends do

Send them the release page URL. On the phone:

1. Tap the `.apk` asset to download it.
2. Android blocks the install and offers a settings toggle — the permission is
   called **Install unknown apps**, granted per-app to whatever is doing the
   installing (usually Chrome or Files). Turn it on, go back, tap the file
   again.
3. Play Protect will warn that the app was not scanned. That warning is about
   distribution channel, not contents: any app not from the Play Store gets it.

Requires Android 8.0 or newer.

Updates are the same steps, and because every build is signed with the same
key they install over the previous version without losing anything.

## Building a signed APK locally

Rarely needed, but the same four variables drive it:

    KEYSTORE_PATH=/path/to/truckscan-release.jks \
    KEYSTORE_PASSWORD=... \
    KEY_ALIAS=truckscan \
    KEY_PASSWORD=... \
    ./gradlew :app:assembleRelease

Without `KEYSTORE_PATH` the release build is simply unsigned rather than
failing, so a clone without the keystore still builds and still runs its tests.
An unsigned APK will not install — that is expected, and is why CI verifies the
signature rather than trusting the build to have applied it.
