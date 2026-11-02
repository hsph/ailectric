# Android-Auto-Analyse

## Ergebnis

Die interaktive Karte ist in einer POI-App möglich.

| Thema | Stand |
|---|---|
| Kategorie | `androidx.car.app.category.POI`; `CHARGING` ist seit 1.3 veraltet |
| Berechtigungen | `androidx.car.app.MAP_TEMPLATES` und `androidx.car.app.ACCESS_SURFACE` |
| Eigene Karte | `MapWithContentTemplate`, Car API 7 |
| Inhalt daneben | `ListTemplate`, `PaneTemplate`, `GridTemplate`, `MessageTemplate` |
| Gesten | `onScroll`, `onFling`, `onScale` ab API 2, `onClick` ab API 5 |
| Pflicht für Gesten | `Action.PAN` in der Karten-Aktionsleiste, sonst keine Callbacks |
| Aktionsleiste | bis zu 4 Icon-Buttons |

Die Dokumentation warnt, dass `onClick`, `onFling` und `onScale` auf manchen Fahrzeugsystemen nicht aufgerufen werden. Deshalb gibt es Zoom-Buttons, und Stationen sind über die Liste erreichbar. Keine Funktion hängt allein an einer Geste.

## Umsetzung gegenüber Wunsch

| Wunsch | Android Auto | Umsetzung |
|---|---|---|
| Verschieben, Zoom | möglich | Pan-Modus, Pinch, + und − |
| Frei anpassbare Karten-/Listenaufteilung | Layout bestimmt der Host | Nur auf dem Telefon. Im Auto blendet der Pan-Modus die Liste aus, das ist die Vollbild-Variante |
| Manuelle Suche | möglich | Aktion „Hier suchen“, danach zurück zum Standort |
| Stationsdetails | möglich | `PaneTemplate` mit bis zu 4 Zeilen, Rest auf dem Telefon |
| Alle Filter | zu viele Eingaben während der Fahrt | Fünf große Optionen im Auto, der volle Filter auf dem Telefon, beide teilen denselben Zustand |
| Navigation | möglich | `ACTION_NAVIGATE` mit `geo:`-URI an die gewählte Navigations-App |

Hosts unter Car API 7 bekommen `PlaceListMapTemplate`: Karte vom Host, kein Pan und Zoom.

## Test mit der Desktop Head Unit

**Noch nicht durchgeführt.** Die DHU ist unter `$ANDROID_HOME/extras/google/auto` installiert, aber das Emulator-Image enthält nur einen Android-Auto-Stub (`1.2.551000-stub`). Die echte App kommt aus dem Play Store und braucht ein Google-Konto.

Mit Telefon, oder mit Emulator nach Play-Store-Anmeldung und Android-Auto-Update:

1. App installieren, Android Auto öffnen.
2. Unter Einstellungen zehnmal auf „Version“ tippen, um den Entwicklermodus zu aktivieren.
3. Im Überlaufmenü „Unbekannte Quellen“ in den Entwicklereinstellungen erlauben und „Head Unit Server starten“ wählen.
4. Auf dem Rechner:

```sh
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

Prüfpunkte stehen in [TESTING.md](TESTING.md).

Für das reale Auto gilt: Sideload-Apps erscheinen nur mit aktivierten unbekannten Quellen. Ein Debug-Build akzeptiert jeden Host; der Release-Build nur die bekannten Android-Auto-Hosts.

## Veröffentlichung

Für den privaten Einsatz nicht nötig. Für den Play Store wären die Android-Auto-Qualitätsrichtlinien und eine gesonderte Prüfung nötig. Diese Richtlinien wurden hier nicht im Detail geprüft.

## Fahrzeugdaten (Phase 5)

Geprüft in der Referenz der Library 1.7.0, `CarInfo` ab Car API 3:

| Daten | API |
|---|---|
| Akkustand, Restreichweite | `addEnergyLevelListener`: `getBatteryPercent`, `getRangeRemainingMeters` |
| Geschwindigkeit | `addSpeedListener` |
| Hersteller, Modell | `fetchModel` |
| Steckertypen | `fetchEnergyProfile`: `getEvConnectorTypes` |
| Kilometerstand | `addMileageListener` |

Alle Werte sind `CarValue` mit eigenem Status; Fahrzeuge dürfen sie schuldig bleiben. Ein Verbrauchswert existiert in diesen Klassen nicht. Er ließe sich aus Akku- und Kilometeränderung ableiten oder manuell eintragen.

Offen: Die benötigten Laufzeitberechtigungen ließen sich nicht in der Dokumentation bestätigen, die betreffende Seite war nicht erreichbar. Vor Phase 5 prüfen, ebenso welche Fahrzeuge welche Werte liefern.
