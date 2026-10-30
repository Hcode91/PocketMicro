# PocketOuya

Play Ouya games on a modern Android phone.

The Ouya ran Android 4.1, so its games are ordinary Android apps – but Android 14 and newer
refuse to install apps built for anything older than Android 6. PocketOuya fixes that:

1. **Add game** – pick an Ouya game's `.apk`.
2. PocketOuya raises the app's target Android version (a single number inside the app's
   manifest), removes the old signature and re-signs it with its own key.
3. Android installs it, and it appears in PocketOuya's library alongside any other app that
   declares itself an Ouya game (`tv.ouya.intent.category.GAME`).

**Download:** the newest APK is on the [latest release](../../releases/tag/latest).

## Notes
- Most Ouya games were designed for a controller; a Bluetooth controller works best.
- Ouya's game code is 32-bit ARM. Phones whose chips can still run 32-bit apps (e.g. Snapdragon 8 Gen 2)
  run them; some newer 64-bit-only phones can't.
- Games that rely on the Ouya store/account services may not start yet – PocketOuya doesn't
  provide those services (and won't bypass purchase checks).
- If you have the original (un-patched) game installed, uninstall it first – the signatures differ.
