# Tests

## Automatisiert

| Bereich | Wo |
|---|---|
| Entfernung, Grenzfälle der Koordinaten, Beschriftung Luftlinie | `core`: `GeoTest` |
| Filterlogik, Kombination mit Wandern, unbekannte Werte | `core`: `FilterTest` |
| API-Verarbeitung, fehlende Daten, Schlüssel, Overpass-Ausfall, Lücken in Geometrie | `core`: `ProviderTest` |
| Cache, GPS-unabhängige Suche, Offline, Provider-Wechsel, POI-Zuordnung | `core`: `SearchTest`, `CacheAndVehicleTest` |
| Room | `app`: `RoomSearchCacheTest` |
| Car-Templates, Host-Grenzen, Navigation-Intent, Fallback alter Hosts | `app`: `CarTemplatesTest` |
| Standort-Provider | `app`: `LocationProvidersTest` |
| Karte auf Car-Surface, Pan und Zoom (Gerät nötig) | `app`: `CarMapRendererTest` |

```sh
./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

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

1. App startet, Standortabfrage funktioniert, Karte erscheint rechts neben der Liste.
2. Karte verschieben und zoomen, im Pan-Modus und mit den Buttons.
3. „Hier suchen“ lädt neue Stationen, die Standort-Schaltfläche kehrt zurück.
4. Station antippen, Details vollständig, „unbekannt“ bei fehlenden Daten.
5. „Navigieren“ öffnet die Navigations-App mit dem richtigen Ziel.
6. Filter ändern, die Liste aktualisiert sich, Telefon zeigt denselben Stand.
7. Flugmodus: alte Daten sind als offline markiert, kein Absturz.
8. Heller und dunkler Modus lesbar.
