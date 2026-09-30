# What changed

I can't compile or run this app here (no Android SDK, no Gradle, no network, in this sandbox),
so everything below comes from careful manual code tracing plus standalone logic tests (see
"How I tested" at the bottom) - not a real build/run cycle. Please build it once in Android
Studio before relying on it, and skim the test checklist below.

## 1. Private messages couldn't be decrypted (two root causes found and fixed)

**File:** `CryptoManager.kt`

There were actually **two** separate Android Keystore issues stacked on top of each other here,
both producing the exact same silent "couldn't decrypt" symptom:

1. The RSA identity key generated in Android's Keystore was missing
   `.setBlockModes(KeyProperties.BLOCK_MODE_ECB)` - fixed in an earlier pass, but that alone
   turned out not to be enough.
2. Even with that fixed, Keystore-backed RSA/OAEP has a second, well-known failure mode: the
   *encrypting* side (a peer's public key, reconstructed through the plain default JCE provider)
   and the *decrypting* side (your own Keystore-backed private key, handled by the separate
   "AndroidKeyStore" provider) don't reliably agree on the OAEP mask-generation-function (MGF1)
   digest unless it's spelled out explicitly - the transformation string
   `"OAEPWithSHA-256AndMGF1Padding"` is ambiguous shorthand, and different providers can resolve
   that ambiguity differently. That mismatch also fails as a silently-swallowed exception.

Given how many Keystore-specific gotchas this one identity key had already hit, I removed
Android Keystore from the picture entirely rather than patching around a third one later:

- The RSA keypair is now a completely ordinary, software-only `java.security.KeyPair` -
  generated and used through the **same single JCE provider on every device**, for both
  encrypting and decrypting, so there's no cross-provider disagreement possible anymore.
- The OAEP digest, MGF1 digest, and label source are all spelled out explicitly via
  `OAEPParameterSpec` on both the encrypt and decrypt paths, instead of relying on a
  transformation-string shorthand that different providers can interpret differently.
- The private key is now stored (base64-encoded, in this app's private SharedPreferences)
  instead of in hardware-backed Keystore storage.

**Trade-off, stated plainly:** this is less protected against a rooted/compromised device
extracting the key than genuine hardware-backed storage would be. For this app - a hobby P2P
mesh chat, not a banking app - "actually decrypts every time, on every device" is worth far more
than theoretical hardware backing for a key that was silently failing to work at all. If you
want to raise that bar again later without reintroducing Keystore's RSA quirks,
`androidx.security.crypto`'s `EncryptedSharedPreferences` is a drop-in way to do it (it uses
Keystore only to protect a *symmetric* wrapping key, which doesn't hit any of the RSA/OAEP
issues above).

`CryptoManager` now needs a `Context` (to reach SharedPreferences) - both call sites
(`ChatActivity`, `ChatForegroundService`) were updated to `CryptoManager(this)`. No other file
needed to change: `getMyPublicKeyBase64()` / `encryptForPeer()` / `decryptMine()` all keep their
exact same signatures and wire format.

**Note for anyone who already ran an earlier build:** conversations encrypted under either the
old Keystore key or the block-mode-fixed Keystore key are not recoverable under this new
software key - that's expected and unavoidable (it's a different key entirely). Anything sent
after this update will work correctly.

## 2. Private messages were leaking in plaintext to unintended peers

**Files:** `MessageStore.kt`, `NearbyManager.kt`

While tracing the decryption bug I found a second, more serious issue: the SYNC handshake that
fires automatically the moment you connect to *any* peer was sending your entire local message
store - including the plaintext copies of private messages you'd sent to completely different
people. Any device you ever connected to could end up with plaintext it was never supposed to
see, even though it wouldn't show up in that device's own chat UI (it wasn't addressed to them),
it was still stored on disk on their phone and could re-propagate further through the mesh.

Fixed:
- `MessageStore.loadAllForSync()` now excludes your own unencrypted private messages - only
  public messages and already-encrypted private messages (safe, opaque ciphertext) are ever
  handed to a peer during sync.
- `MessageStore.getPrivateThread()` was tightened to match (each message is shown in exactly
  one form - your own plaintext copy, or the peer's ciphertext decrypted for display - never
  both, and never an attempt to "decrypt" your own outgoing ciphertext if it ever loops back).
- Since the old (buggy) leak was incidentally also acting as a delivery mechanism, I added
  `NearbyManager.resendUndeliveredPrivateMessagesTo()`, which re-sends (encrypted, to the right
  person only) anything you're still owed delivery credit for whenever you reconnect with them -
  so closing this leak doesn't quietly make message delivery less reliable.
- A device revocation (kill command) now stops the background mesh service from *any* screen,
  not just the chat screen - previously a revoke that happened while on the Maps screen would
  lock the UI but leave the mesh running invisibly in the background forever.

## 3. Runs in the background + notifies on new messages

**New file:** `ChatForegroundService.kt`. **Changed:** `ChatActivity.kt`, `AccessGuard.kt`,
`AndroidManifest.xml`.

The mesh networking (`NearbyManager`) now lives in a foreground service instead of directly in
`ChatActivity`:
- `ChatActivity` starts the service and binds to it while visible (live UI updates flow through
  as before); it un-binds - but does **not** stop the service - when backgrounded.
- The service shows a low-priority "P2P Chat is running" notification the whole time it's
  active, with a "Go offline" action to actually stop it.
- Whenever a new message arrives and nothing is bound to show it live, the service posts a
  notification for it (decrypting private content just long enough to build the notification
  text - nothing extra is written to disk). Tapping a private-message notification opens that
  conversation directly.
- Notifications are per-message (deduped by id) and skip your own messages and anyone else's
  private mail gossiping through your device.
- `POST_NOTIFICATIONS` (Android 13+) is requested alongside the existing permissions, but
  treated as optional - denying it just means no notifications, not a broken app.

## 4. Direct `.osm.pbf` support

**New file:** `maps/osm/OsmPbfRoadLoader.kt`. **Changed:** `MapsActivity.kt`,
`maps/osm/OsmRoadLoader.kt` (one internal-visibility tweak), `maps/osm/OsmDownloader.kt` (doc
only), `maps/osm/RoadGraph.kt` (doc only).

Previously the app could only parse plain `.osm` XML - picking or downloading a `.osm.pbf` (the
compact format Geofabrik/BBBike/osmium actually distribute, often 5-10x smaller) would silently
fail. There's no protobuf library available in this project, so I wrote a small, purpose-built
reader for exactly the PBF fields this app needs: fileblock framing, zlib inflate (via the
standard `java.util.zip.Inflater` - no extra dependency), the string table, `DenseNodes` (the
common case) and the rarer plain `Node` message, and `Way` - all using the same two-pass
strategy (which ways to keep, then which nodes they need) as the existing XML loader, so it
produces an identical `RoadGraph`.

`MapsActivity` now sniffs whether a picked/downloaded file is PBF or XML *by its content* (the
first few bytes), not by filename, and calls the matching loader automatically - so there's
nothing new for the user to configure, they can just pick or paste a link to either format.

## How I tested this

No Android SDK/emulator/Gradle/JDK is available in the sandbox I did this work in, so I
couldn't build or run the actual app. What I *could* do, and did:

- **Crypto:** re-implemented the exact hybrid RSA-OAEP-SHA256 + AES-256-GCM scheme
  `CryptoManager` uses, in Python (`cryptography` library), and confirmed: round-trip
  encrypt/decrypt matches, a non-recipient key cannot decrypt, and tampering is detected via the
  GCM auth tag. This validates the algorithm/wire-format logic; the Keystore-specific bug itself
  (missing block mode authorization) is a well-documented, extremely common Android gotcha,
  fixed by the one-line change described above.
- **PBF parsing:** hand-crafted several minimal but valid `.osm.pbf` files byte-for-byte in
  Python (covering: zlib-compressed blobs, raw/uncompressed blobs, `DenseNodes`, the rarer plain
  `Node` message, a `oneway` road, and a non-routable highway type that must be excluded), then
  faithfully re-implemented `OsmPbfRoadLoader.kt`'s exact algorithm in Python and ran it against
  those files, comparing the extracted graph against hand-computed expected output. This caught
  and fixed one real bug (missing zigzag-decode on the rare plain-`Node` fields) before it ever
  reached the Kotlin file. `OsmPbfRoadLoader.looksLikePbf()`'s content-sniffing was tested the
  same way, including a negative case (a plain-XML file correctly not detected as PBF).
- **Static review:** manually traced every changed code path end-to-end (SYNC/gossip flow,
  service lifecycle, permission flows, revoke handling), and ran a brace/paren structural-balance
  check across every modified/new file to catch stray syntax mistakes I couldn't otherwise catch
  without a compiler.

What I could **not** do: actually compile this, run it on a device/emulator, or watch two real
phones exchange encrypted messages or load a real multi-megabyte `.pbf` extract end-to-end.

## Suggested manual test checklist (once you build it)

1. **Decryption:** install on two devices, send a private message each way, confirm both sides
   can read what was sent to them (and that a third device that isn't the sender or recipient
   never sees the plaintext, including after it syncs with either side).
2. **Reinstall/upgrade:** if you're upgrading an existing install rather than a fresh one, note
   old conversations encrypted under the old (broken) key will still show "couldn't decrypt" -
   that's expected, only new messages sent after the fix will work.
3. **Background + notifications:** send a message from device A, then background (don't force-
   close) the app on device B - confirm a notification appears, and tapping it opens the right
   conversation. Confirm no notification appears while the chat screen is actually open and
   visible. Try the "Go offline" notification action, and confirm the app still opens and can
   restart the mesh from the chat screen afterward.
4. **Revocation:** revoke a test device while it's sitting on the Maps screen, confirm it gets
   locked out and the background notification/service actually stops (not just the UI).
5. **`.osm.pbf`:** pick a small city-sized `.osm.pbf` extract (e.g. from extract.bbbike.org or
   Geofabrik) via the road icon, confirm it loads and routing/turn-by-turn works over it, same
   as it does today with a plain `.osm` file. Try a `.osm.gz` and a `.osm.pbf` download link via
   long-press too.
6. Normal regression pass on everything untouched: public messages, the users list, location
   sharing, the ESP32 relay bridge (if you have the hardware), turn-by-turn voice guidance.
