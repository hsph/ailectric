# Tests

Getestet wird auf dem echten Telefon über adb. Der Emulator wird nur nach ausdrücklicher Bestätigung benutzt, auch wenn das Telefon gerade nicht erreichbar ist.

Android Auto lässt sich auf dem Telefon selbst über OpenHeadUnit (Headunit Revived) testen. Das geht nur nach Bestätigung, also nach „Bereit“ in der Abfrage unten. Hinweise dazu stehen in `ANDROID_AUTO.md`.

Bevor ein Test das Telefon übernimmt oder die laufende App beendet (auch `installDebug` und `am instrument`), fragt `scripts/ask-phone.sh <serial>` dort nach: drei kurze Vibrationen und eine Benachrichtigung mit „Bereit“, 10 s, 30 s und 1 min. Das Skript gibt die Antwort aus. Die Benachrichtigung gibt es nur im Debug-Build. Antwortet niemand, wird im Chat gefragt.

Bei einem gemeldeten Absturz zuerst die Gründe lesen, das Telefon speichert sie je App-Beendigung: `adb shell dumpsys activity exit-info app.ailectric`. Installationen und Gerätetests beenden die App ebenfalls, das sieht wie ein Absturz aus. Logs nie mit `logcat -c` löschen, sie sind der einzige Beleg für den Zeitraum davor.

## Automatisiert

| Bereich | Wo |
|---|---|
| Entfernung, Grenzfälle der Koordinaten, Beschriftung Luftlinie | `core`: `GeoTest` |
| Filterlogik, Kombination mit Wandern, unbekannte Werte | `core`: `FilterTest` |
| API-Verarbeitung, fehlende Daten, Schlüssel, Overpass-Ausfall, Lücken in Geometrie | `core`: `ProviderTest` |
| Cache, GPS-unabhängige Suche, Offline, Provider-Wechsel, POI-Zuordnung | `core`: `SearchTest`, `CacheAndVehicleTest` |
| Room | `app`: `RoomSearchCacheTest` |
| Car-Templates, Raster, Einklappen, Einstellungen, Host-Grenzen, Navigation-Intent, Fallback alter Hosts | `app`: `CarTemplatesTest` |
| Kartenansicht: Quellenangaben, Speichern der Auswahl | `app`: `MapSettingsTest`, `CarTemplatesTest` |
| Standort-Provider | `app`: `LocationProvidersTest` |
| Karte auf Car-Surface, Pan und Zoom, Startposition neben dem Fenster, Markierung der gewählten Station, Kartenansicht (Gerät nötig) | `app`: `CarMapRendererTest` |

```sh
./gradlew :core:test :app:testDebugUnitTest
```

Gerätetest direkt starten. `connectedDebugAndroidTest` meldete hier trotz abgestürztem Testlauf `BUILD SUCCESSFUL`, die Build-Zeile ist also kein Beleg. Maßgeblich ist `OK (5 tests)`:

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class app.ailectric.CarMapRendererTest app.ailectric.test/androidx.test.runner.AndroidJUnitRunner
```

Der Test legt die gerenderten Bilder unter `/sdcard/Android/data/app.ailectric/files/` ab: `car_map.png` mit Legende, `car_selected.png` mit markierter Station, `car_trails.png` mit Waymarked-Wanderwegen und `car_routes.png` mit eigenen Rad- und Wanderwegen. Dieser Test braucht `overlays.pmtiles` auf dem Gerät, ohne die Datei zeigt das Bild nur die Karte.

Die Antwort in `core/src/test/resources/overpass_hiking_real.json` stammt vom echten Dienst. Sie hat zwei falsche Annahmen aufgedeckt: Abgeschnittene Geometrie enthält `null`-Lücken, und auch `bounds` ist abgeschnitten.

## Manuelle Testmatrix Android Auto

Die DHU-Profile liegen in `$ANDROID_HOME/extras/google/auto/config`, Aufruf mit `desktop-head-unit -c config/<datei>`.

| Profil | Datei | Karte/Liste | Pan, Zoom | Details, Navigation | Status |
|---|---|---|---|---|---|
| 800 × 480 Touch | `default.ini` | | | | offen |
| 720p | `default_720p.ini` | | | | offen |
| 1080p | `default_1080p.ini` | | | | offen |
| Breitbild | `default_wide.ini` | | | | offen |
| 6 Zoll, kompakt | `default_6in.ini` | | | | offen |
| Touchpad | `touchpad.ini` | | | | offen |
| Dreh-Drück-Steller | `rotary.ini` | | | | offen |
| Hybrid | `hybrid.ini` | | | | offen |
| Reales Fahrzeug | | | | | offen |

Je Profil prüfen:

1. App startet, Standortabfrage funktioniert, Karte erscheint rechts neben dem Raster, der Startpunkt liegt mittig im freien Bereich.
2. Karte verschieben und zoomen, im Pan-Modus und mit den Buttons.
3. „Hier suchen“ lädt neue Stationen, die Standort-Schaltfläche kehrt zurück.
4. Station antippen, die Karte zoomt hin und markiert sie, Details vollständig, „unbekannt“ bei fehlenden Daten. Zurück führt zur vorherigen Ansicht.
5. „Navigieren“ öffnet die Navigations-App mit dem richtigen Ziel.
6. Filter ändern, das Raster aktualisiert sich, Telefon zeigt denselben Stand. Einstellungen im Auto ändern, Kachelgröße und Zoom folgen.
7. Flugmodus: alte Daten sind als offline markiert, kein Absturz.
8. Heller und dunkler Modus lesbar.
