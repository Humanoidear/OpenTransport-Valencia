# EMT Valencia — Live Bus App (React Native / Expo)

Android app for live EMT Valencia buses, ported from the web version with the
same data, prediction and UX, styled with **Material 3 (Expressive)** via
`react-native-paper`, Google's **Material Icons**, and Android **Material You
wallpaper colors** via Expo UI. Flyouts use expressive M3 bottom sheets, a
segmented search/favorites switcher, tonal surfaces and large native-style
shapes. The map uses **free OpenStreetMap-based vector maps**
(MapLibre GL with CARTO dark-matter / positron styles — no API key).

## OpenTransport Valencia

Aplicación Android nativa para consultar transporte público en València en
tiempo real. El proyecto incluye una aplicación para móvil y una aplicación
independiente para WearOS.

## Funciones

### Aplicación móvil

- Mapa interactivo con EMT, Metrovalencia, Valenbisi, Metrobús y Rodalies.
- Posición de autobuses EMT, recorridos y estimaciones de llegada.
- Búsqueda de paradas, estaciones y líneas.
- Favoritos y avisos para líneas concretas.
- Alertas de servicio de EMT, Metrovalencia, Metrobús y Rodalies.
- Planificador de viajes y localización de paradas cercanas.
- Capas de transporte configurables, guardadas entre reinicios de la aplicación.
- Temas claro, oscuro y automático.

### Aplicación WearOS

- Consulta de paradas cercanas, búsqueda y favoritos.
- Datos de EMT, Metrovalencia, Metrobús, Rodalies y Valenbisi.
- Mapa con marcadores, líneas y zoom mediante la corona del reloj.
- Desplazamiento de las listas mediante la corona.
- Navegación con gesto de retroceso predictivo.
- Complicación y Tile con próximas llegadas y actualización periódica.

Los datos pueden variar según la disponibilidad de los servicios externos.

## Requisitos

- Android Studio reciente.
- JDK 17.
- Android SDK con API 36.
- Un dispositivo o emulador Android para la aplicación móvil.
- Un dispositivo o emulador WearOS para probar el módulo del reloj.

El proyecto usa Gradle Wrapper, por lo que no es necesario instalar Gradle
manualmente.

## Compilar

Desde la raíz del proyecto:

```sh
./gradlew assembleDebug
```

Para generar las versiones release:

```sh
./gradlew assembleRelease
```

Los APK se generan en:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
wear/build/outputs/apk/debug/wear-debug.apk
wear/build/outputs/apk/release/wear-release.apk
```

También se pueden compilar los módulos por separado:

```sh
./gradlew :app:assembleDebug
./gradlew :wear:assembleRelease
```

## Instalar mediante ADB

Con un dispositivo conectado y la depuración USB activada:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Comprueba la conexión con `adb devices` antes de instalar. Para probar la
complicación, instala primero la aplicación WearOS y añádela desde el selector
de complicaciones de la esfera.

## Estructura

```text
app/   Aplicación Android para móvil.
wear/  Aplicación Android independiente para WearOS.
gradle/ Configuración del Gradle Wrapper.
```

La lógica de datos y las pantallas de cada plataforma se encuentran en
`app/src/main` y `wear/src/main`. Los datos estáticos de Rodalies y otros
recursos del reloj están incluidos en `wear/src/main/assets`.

## Fuentes de datos

La aplicación consume servicios públicos y APIs de EMT València,
Metrovalencia, Valenbisi, Metrobús y Renfe/Rodalies. La disponibilidad, los
tiempos y las incidencias dependen de esos servicios y pueden no coincidir
exactamente con la situación en la calle.
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
