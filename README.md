# Ailectric

Markenunabhängiger EV-Ladefinder für Europa, primär für Android Auto, mit Filter für Wandermöglichkeiten in der Nähe von Ladestationen. Privates Projekt, kein öffentlicher Betrieb.

## Aufbau

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin ohne Android: Datenmodell, Geo, Filter, Provider (Open Charge Map, OSM/Overpass), Suche, Cache-Regeln, Fahrzeug- und Routing-Schnittstellen |
| `app` | Android: Compose-UI, MapLibre-Karte, Room-Cache, DataStore, Standort, Android-Auto-Screens |

Dokumente: [Architektur und Entscheidungen](docs/ARCHITECTURE.md) · [Android Auto](docs/ANDROID_AUTO.md) · [Datenquellen und Lizenzen](docs/DATA_SOURCES.md) · [TrueNAS](docs/SERVER.md) · [Tests](docs/TESTING.md) · [PRD und Roadmap](docs/PRD.md)

## Einrichtung

Voraussetzungen: JDK 21 (nicht neuer, nicht das Homebrew-`openjdk@21` auf macOS 15.0, siehe unten), Android SDK mit Plattform 37 und Build-Tools 36.1.

```sh
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-21.0.13+9/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :core:test :app:testDebugUnitTest   # JVM-Tests
./gradlew :app:assembleDebug                  # APK
./gradlew :app:connectedDebugAndroidTest      # Karten-Rendering auf Gerät/Emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Das Homebrew-JDK 21 ist für eine neuere macOS-Version gebaut und scheitert unter macOS 15.0 an `libhvf.dylib`, sobald der Room-Annotationsprozessor AWT lädt. Eclipse Temurin 21 funktioniert.

## API-Schlüssel

Die App läuft ohne Schlüssel und nutzt dann OpenStreetMap. Für die bessere Datenbasis einen kostenlosen Open-Charge-Map-Schlüssel eintragen: in der App unter Einstellungen, oder für Builds in `secrets.properties` (per `.gitignore` ausgeschlossen):

```properties
OCM_API_KEY=...
```

## Android Auto testen

Siehe [docs/ANDROID_AUTO.md](docs/ANDROID_AUTO.md#test-mit-der-desktop-head-unit).
