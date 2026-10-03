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
