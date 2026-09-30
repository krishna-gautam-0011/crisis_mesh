# P2P Chat (Kotlin, Android)

A serverless, no-login chat app. Every device generates a random user ID on
first launch. Devices that come within Bluetooth/Wi-Fi range of each other
connect **automatically** (no pairing dialog, no accept/reject step) using
Google's **Nearby Connections API** with the **`P2P_CLUSTER`** strategy, which
is designed exactly for this "everyone discovers and connects to everyone"
use case rather than a single host/client pair.

## How it works

- **Home screen** (`MainActivity`) generates and persists a random user ID
  (`UserXXXX`) the first time the app runs, then shows a button into the chat
  screen.
- **Chat screen** (`ChatActivity`) is split into 4 quadrants:
  1. **Compose** — a message box, a "recipient user id" field, and two
     buttons: **Send Public** (broadcasts to every connected peer) and
     **Send Private** (delivered to the user id typed in the recipient
     field).
  2. **Online users** — live list/count of currently-connected peers.
  3. **Public messages** — every broadcast message seen so far.
  4. **Private messages to me** — every private message addressed to your
     own user id.
- **`NearbyManager`** advertises and discovers simultaneously
  (`Strategy.P2P_CLUSTER`). When two devices come in range they auto-request
  and auto-accept a connection (`onConnectionInitiated` always calls
  `acceptConnection` — there is no authentication step). The moment a
  connection succeeds, both sides send their **entire local message list**
  to each other.
- **`MessageStore`** persists all known messages to `messages.json` in the
  app's private storage and exposes a `mergeIncoming()` union-merge: any
  message (by unique id) either device has ever seen ends up on both
  devices. New messages you compose are also appended here immediately.
- Because merging is a full union and happens on every connection, private
  messages can also "hop" from device to device (gossip-style) until they
  eventually reach the intended recipient, even if the sender and recipient
  were never directly connected.

## Opening the project

1. Unzip and open the `P2PChat` folder in Android Studio (Koala/Iguana or
   newer) — choose "Open" and select the folder, then let Gradle sync.
2. Android Studio will download the Gradle 8.7 distribution and the
   dependencies (Nearby Connections, Gson, AndroidX) automatically on first
   sync — an internet connection is needed for that step only.
3. Build & run on **two physical devices** (Nearby Connections doesn't work
   between two emulators, and needs Bluetooth/Wi-Fi hardware). Grant the
   permissions when prompted on each device.
4. Keep both devices' Bluetooth and Wi-Fi turned on and within a few meters
   of each other — they should connect within a couple of seconds, after
   which "Online users" will show 1 (or more) and messages will start
   syncing.

## Permissions

Handled at runtime in `ChatActivity.requestNeededPermissions()`:
- `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+)
- `NEARBY_WIFI_DEVICES` (Android 13+)
- `ACCESS_FINE_LOCATION` (required by BLE scanning on Android < 13)
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

There is deliberately **no login, pairing PIN, or accept/reject UI** — any
two devices running the app that come into range connect and start syncing
automatically, as requested.

## Distribution control (who can open the app)

Three files are deliberately kept separate from the app logic so you can
hand-edit them (and swap the image) without touching anything else:

- **`app/src/main/java/com/example/p2pchat/access/AccessConfig.kt`** — set
  `CONTROLLER_NAME` and the `AUTHORIZED_DEVICE_HASHES` allow-list.
- **`app/src/main/java/com/example/p2pchat/access/DeviceAccess.kt`** — the
  logic that reads the device's `ANDROID_ID`, hashes it with SHA-256, and
  checks it against `AccessConfig`. You shouldn't need to edit this one.
- **`app/src/main/res/drawable/homepage_image.xml`** — the homepage image.
  Delete it and drop in your own `homepage_image.png`/`.jpg` with the same
  file name (see `access/BrandingConfig.kt`) to rebrand the homepage.

`LauncherActivity` is now the real entry point (set as `LAUNCHER` in the
manifest). It checks the current device's hash before handing off to
`MainActivity` (the homepage). An unauthorized device sees a blocked screen
showing **its own** device code, which you copy into `AUTHORIZED_DEVICE_HASHES`
and then rebuild/redistribute. To onboard your first device, either add its
hash after you see it once, or temporarily flip
`ALLOW_ALL_DEVICES_DEBUG_ONLY = true` in `AccessConfig.kt`.

Two honest caveats: `ANDROID_ID` can change on a factory reset or a re-sign
with a different key, and this gate only lives inside the app's own UI - it's
a convenience control over who you hand APKs to, not tamper-proof DRM.

## Satellite map & turn-by-turn navigation

`MapsActivity` now renders a real satellite map (Google Maps SDK for
Android, `MAP_TYPE_SATELLITE`) instead of the old schematic outline. Tapping
the map (or opening a location someone shared in chat) drops a pin; the
**Turn-by-turn** button hands off to the Google Maps app's own navigation
engine (`google.navigation:` intent, falling back to a generic `geo:` intent
if Google Maps isn't installed) — that's the reliable way to get real,
spoken, live turn-by-turn guidance rather than reimplementing a routing
engine from scratch. The bottom card also shows an offline straight-line
bearing + distance from your GPS fix to the pin, which keeps working with no
internet connection at all.

To make the satellite imagery itself load, add your own Maps SDK API key in
`app/src/main/res/values/google_maps_api.xml` (instructions are in that
file's comments) — without a key you'll still get pin-drop, the offline
bearing/distance, and turn-by-turn hand-off, just a blank grey tile area
instead of imagery.

## Compose section

The compose card always shows **two** fields: the message box, and a
"Recipient user id" field you can type into directly (handy for a peer
that's currently out of Bluetooth/Wi-Fi range but still reachable through
mesh gossip-relay). Tapping a user in the Online list fills the same field;
whichever id is in the field when you tap **Send Private** is who the
message goes to.

## Notes / things you may want to extend

- Nearby Connections requires **Google Play Services**, so it won't work on
  devices without it (e.g. plain AOSP/emulators without Play Services).
- The cluster currently forms fully-meshed direct connections where
  possible; Nearby Connections internally caps simultaneous connections per
  device, which is normal for P2P_CLUSTER with larger groups.
- `messages.json` grows forever right now — you could add a max-age prune in
  `MessageStore` if you want old messages to expire.
