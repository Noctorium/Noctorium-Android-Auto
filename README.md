# Noctorium for Android Auto

Noctorium in the car: the menu Android Auto shows for the [Noctorium](https://github.com/Noctorium/Noctorium-Mobile)
phone app, and what picking something in it does.

> Noctorium is an independent third-party client. It is not affiliated with Google, YouTube, SoundCloud or Android
> Auto.

## What the car shows

- **Home** — the rows Noctorium's own Home has: what you pinned, *Jump back in*, and the services' rows. Each opens to
  its songs; a song plays as it would on the phone's Home, with what Settings say plays after it.
- **Library** — *Recently played*, your playlists on the phone, and your playlists on the services you are signed in
  to, read when you open them.
- **Queue** — the queue, the song playing marked *Playing now*, and what autoplay has lined up after it. Picking one
  jumps there.
- **Radio** — the most listened-to stations in the country chosen in the app's Radio.
- **Search** — the car's search box, and its voice: *"play Daft Punk on Noctorium"* plays the first song found, and
  *"play some music on Noctorium"* picks up the queue where it was, or else Home's first row.

Play, pause, skipping, seeking and repeat are the phone's own buttons, as on the lock screen. The car and the phone are
one player: what is picked in one shows in the other, and whatever plays on the phone shows in the car.

## How it works

Android Auto shows what a phone app's media session offers, so this is not an app of its own: it is an Android library
that Noctorium-Mobile includes, as `:auto`, and builds its session with.

- [`CarLibrary`](auto/src/main/kotlin/app/noctorium/auto/CarLibrary.kt) is the menu: every item has an id that says
  where it is (`home/2/5` is the sixth song of Home's third row), and picking one plays it through the same call the
  phone's screen makes for that list, so the car never builds a queue of its own.
- [`CarLibraryCallback`](auto/src/main/kotlin/app/noctorium/auto/CarLibraryCallback.kt) is Media3's
  `MediaLibrarySession.Callback` over it: browsing, paging, search, picks, voice and picking up again.
- [`CarMusic`](auto/src/main/kotlin/app/noctorium/auto/CarMusic.kt) is what the menu needs of Noctorium, answered by
  the app's `AppState` from Noctorium-Base.
- [`CarArtwork`](auto/src/main/kotlin/app/noctorium/auto/CarArtwork.kt) hands Android Auto the covers, which it only
  shows from `content://` addresses. It serves only covers the menu itself listed, so no other app can have Noctorium
  fetch an address of its choosing.
- The library's manifest declares Noctorium a media app to Android Auto; the phone app's session service answers
  `MediaLibraryService` and `MediaBrowserService`.

## Trying it

In a car, or with Google's Desktop Head Unit. Android Auto shows media apps from Google Play; for Noctorium installed
from a release, turn on *Unknown sources* in Android Auto's developer settings (tap its version number in Android
Auto's settings ten times to reach them).

## Building and testing

Built inside Noctorium-Mobile, which finds this repository beside it as `../Noctorium-Android-Auto`, or as its `auto/`
submodule. On its own:

```bash
./gradlew :auto:testDebugUnitTest
```

finds Noctorium-Base beside it, or as its `base/` submodule, and tests the menu and, with Robolectric, the session as
a car's browser sees it: browsing, a pick playing through the queue, search, voice, paging and picking up again.

`RealAppTest` does the same against Noctorium itself, installed on a phone or an emulator, from another app as
Android Auto connects:

```bash
./gradlew :auto:connectedDebugAndroidTest
```

## Licence

GPL-3.0-only, as Noctorium.
