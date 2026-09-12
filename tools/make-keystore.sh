#!/usr/bin/env bash
#
# Creates the Truck Scan release signing key and puts its base64 on the
# clipboard, ready to paste into the KEYSTORE_BASE64 repository secret.
#
# Written to be run on a phone under Termux, where typing a six-line keytool
# invocation on a touch keyboard is its own failure mode. Nothing here leaves
# the device: it runs keytool locally and copies text to the clipboard.
#
# Usage:  bash make-keystore.sh
#
set -euo pipefail

KEYSTORE=truckscan-release.jks
ALIAS=truckscan

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool is not installed. Under Termux:" >&2
  echo "  pkg install -y openjdk-17" >&2
  exit 1
fi

# Never silently replace an existing key. If one is already in use, a new one
# cannot sign updates that install over what people already have.
if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists here." >&2
  echo "If it is the key you are already publishing with, keep it - a" >&2
  echo "replacement cannot update the app on anyone's phone. Move it aside" >&2
  echo "deliberately if you really want a new one." >&2
  exit 1
fi

echo "This creates the signing key for Truck Scan releases."
echo
echo "Use the same password you put in the KEYSTORE_PASSWORD secret -"
echo "the build tries that one. Nothing is echoed as you type."
echo

read -r -s -p "Password: " PW1; echo
read -r -s -p "Again:    " PW2; echo
echo

if [ "$PW1" != "$PW2" ]; then
  echo "Those do not match. Nothing was created; run it again." >&2
  exit 1
fi
if [ ${#PW1} -lt 6 ]; then
  echo "keytool requires at least 6 characters. Nothing was created." >&2
  exit 1
fi

export KS_PW="$PW1"
unset PW1 PW2

# -dname is supplied so keytool does not ask six questions nobody verifies;
# this is a self-signed certificate and Android only checks that the same key
# signed both builds. 10000 days because an expired certificate cannot sign
# an update that installs over the old one either.
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=Truck Scan, O=Truck Scan, C=AU" \
  -storepass:env KS_PW

# Prove it before telling anyone it worked.
keytool -list -keystore "$KEYSTORE" -storepass:env KS_PW -alias "$ALIAS" \
  >/dev/null
unset KS_PW

echo "Created $KEYSTORE"
echo

B64=$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')

if command -v termux-clipboard-set >/dev/null 2>&1; then
  printf '%s' "$B64" | termux-clipboard-set
  echo "Its base64 is on the clipboard (${#B64} characters)."
else
  printf '%s' "$B64" > "$KEYSTORE.b64"
  echo "termux-clipboard-set is not installed, so the base64 is in"
  echo "$KEYSTORE.b64 instead. Under Termux you can get the clipboard with:"
  echo "  pkg install -y termux-api"
  echo "  base64 -w0 $KEYSTORE | termux-clipboard-set"
fi

# A copy somewhere durable. Termux's own directory is wiped if the app is
# uninstalled, and this file cannot be regenerated.
if [ -d /sdcard/Download ] && cp "$KEYSTORE" /sdcard/Download/ 2>/dev/null; then
  echo "A copy is in /sdcard/Download - visible in the Files app."
elif command -v termux-setup-storage >/dev/null 2>&1; then
  echo "To copy it somewhere you can reach from the Files app, run:"
  echo "  termux-setup-storage && cp $KEYSTORE /sdcard/Download/"
fi

cat <<'NEXT'

Next:

  1. Paste the clipboard into the KEYSTORE_BASE64 secret, replacing what
     is there:
     github.com/anthonyrohde/truckscan/settings/secrets/actions

  2. Back the keystore file up - password manager, encrypted drive,
     anywhere that is not just this phone. It cannot be recreated, and
     without it no future version can install over this one.
NEXT
