# EMT Valencia — Live Bus App (React Native / Expo)

Android app for live EMT Valencia buses, ported from the web version with the
same data, prediction and UX, styled with **Material 3 (Expressive)** via
`react-native-paper`, Google's **Material Icons**, and Android **Material You
wallpaper colors** via Expo UI. Flyouts use expressive M3 bottom sheets, a
segmented search/favorites switcher, tonal surfaces and large native-style
shapes. The map uses **free OpenStreetMap-based vector maps**
(MapLibre GL with CARTO dark-matter / positron styles — no API key).

## Features

- **Live bus map** (MapLibre GL) with buses dead-reckoned along their GTFS route
  (extrapolate at measured speed, ease corrections, no snapping) and pinned at
  stops until the API shows them leaving.
- **Reveal a line**: open a stop, tap a line → its route (ida blue / vuelta red)
  and live buses appear; tap again to stop.
- **Follow a bus**: tap a bus → camera follows and rotates to its heading, with
  the next stops + ETAs in a panel.
- **Stops**: all stops plotted (culled by viewport / hidden far out); tap one →
  bottom sheet with arrivals (EMT line badges, sorted closest first, scheduled
  `hh:mm:ss` times), per-line colors, and collapsible **service updates**
  (incidents scraped from EMT's estado del servicio).
- **Search + favorites**: find stops by name or number; star stops (persisted via
  AsyncStorage).
- **Locate me**: nearby stops via GPS.
- **Light / dark Material 3 themes**.

## Run

MapLibre is a native module, so this needs a **development build** (not Expo Go):

```sh
npm install
npx expo prebuild --platform android
npx expo run:android
```

or start the dev server first and press `a`:

```sh
npx expo start
```

> The EMT WSSE token is a static replayed token from a captured request; the
> `estimaciones` endpoint may return empty data when it goes stale (the app falls
> back to ETAs computed from live positions).

## Structure

- `App.tsx` — screen: map, prediction loop, sheets, search, favorites, follow.
- `src/emt.ts` — EMT API client (WSSE auth, buses, estimaciones XML, incidents).
- `src/motion.ts` — geo / dead-reckoning math (projection, predict, stop-hold, ETAs).
- `src/theme.ts` — Material 3 light/dark themes + per-line colors.
- `src/data/` — bundled `stops.json` + `routes.json` from València open data / GTFS.
