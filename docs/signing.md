# Signing

**PocketOuya itself.** Official builds are signed with a private release key, held only in the
repository's GitHub Actions secrets:

- `POCKETOUYA_KEYSTORE_B64`: the PKCS12 keystore, base64-encoded (key alias `pocketouya`)
- `POCKETOUYA_KEYSTORE_PASSWORD`: the keystore and key password

When those secrets are missing (forks, pull requests from forks, local builds) the build falls
back to `app/ci.keystore`, a public test key with the password `android`. APKs signed with it
are for testing only: anyone can sign an APK with that key. Keep an offline backup of the
release key; if it is lost, existing installs cannot be updated without uninstalling first.

**Converted games.** PocketOuya signs the games it converts with the key in
`app/src/main/assets/games.p12`. It ships inside every PocketOuya APK so converted games keep
the same signature across PocketOuya versions and reconversions (an installed game can be
updated in place and keeps its saves). Because it is public, only install converted games you
made yourself with PocketOuya.
