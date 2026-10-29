# DropFly v2

What changed
- Big files: sending now runs as a background service, so it keeps going when you leave the app or lock the phone.
- If the Wi-Fi drops mid-way, it reconnects and RESUMES (no starting over). Up to 6 retries.
- Faster: bigger buffers, Wi-Fi high-performance lock while sending, faster laptop discovery.
- Progress: a thin bar in the phone's notification, and a small card with a thin line on the laptop.
- The APK is now signed with a fixed key, so FROM NOW ON you can install updates over the old app.

Updating your existing GitHub repo
1. Replace the android and windows folders in your repo with the ones in this zip
   (leave the notch folder alone). Easiest: upload the new files over the old ones on GitHub.
2. Actions tab -> "Build DropFly" runs -> download the APK and the EXE artifacts.

First-time install of THIS version
- Phone: UNINSTALL the old DropFly first (the signing key changed), then install the new APK.
  Open it, allow notifications, enter the pairing code again.
- Laptop: right-click the old DropFly in the tray > Exit, then run the new DropFly.exe.
  (Your pairing code stays the same.)

Tips for speed
- Use 5 GHz Wi-Fi (the one that ends in -5G) on both devices, and sit reasonably close to the router.
- Received files go to Downloads\DropFly on the laptop.
