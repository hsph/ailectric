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

Die Dokumentation warnt, dass `onClick`, `onFling` und `onScale` auf manchen Fahrzeugsystemen nicht aufgerufen werden. Deshalb gibt es Zoom-Buttons, und Stationen sind über das Raster erreichbar. Keine Funktion hängt allein an einer Geste.

## Umsetzung gegenüber Wunsch

| Wunsch | Android Auto | Umsetzung |
|---|---|---|
| Verschieben, Zoom | möglich | Pan-Modus, Pinch, + und − |
| Frei anpassbare Karten-/Listenaufteilung | Layout bestimmt der Host | Nur auf dem Telefon. Im Auto blendet der Pan-Modus das Inhaltsfenster aus, das ist die Vollbild-Variante; Raster und Details lassen sich einklappen |
| Manuelle Suche | möglich | Aktion „Hier suchen“, danach zurück zum Standort |
| Stationsdetails | möglich | `PaneTemplate` mit bis zu 4 Zeilen, Rest auf dem Telefon |
| Alle Filter | zu viele Eingaben während der Fahrt | Fünf große Optionen im Auto, der volle Filter auf dem Telefon, beide teilen denselben Zustand. Die Mindestleistung ist ein Menü mit den Stufen der Farblegende (Alle, ab 23, ab 150, ab 300 kW) |
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

## Kartenansicht im Auto

Die Auto-Karte hat ihre eigene Ansicht, unabhängig von der Telefon-App. Satellit fehlt bewusst: Das Bild ist bei näherem Zoom grob, und dunkle Ringe um die Stationen verschwinden darauf. Wander- und Radwege erscheinen erst ab Zoomstufe 12, weil die Routen weiter draußen Straßen und Stationen verdecken. Die Quellenangabe steht dicht über dem unteren Rand und wechselt mit der Ansicht; sie hat einen eigenen hellen Grund, weil die Overlays für bloßen Text zu unruhig sind.

## Test mit OpenHeadUnit

Android Auto lässt sich ohne Fahrzeug auf dem Telefon selbst testen, mit OpenHeadUnit (Headunit Revived, `com.andrerinas.headunitrevived`). Das geht nur nach Bestätigung, siehe `TESTING.md`.

- Die Aktionsknöpfe auf der Karte blenden sich nach kurzer Zeit aus. Automatisierte Tipps müssen also direkt nach dem Screenshot kommen.
- `input tap` kam in der Projektion oft nicht an, `input swipe x y x y 120` schon.
- Das Layout wechselt zwischen Sitzungen und mit der Drehung des Telefons. Koordinaten daher immer aus einem frischen Screenshot nehmen.
- Trennt OpenHeadUnit die Sitzung mit `isUserExit` oder `User requested switch driver`, war das eine Bedienaktion, kein Absturz.

## Gesten und Details

Pinch zoomt um die Kartenmitte, gedämpft und je Ereignis begrenzt, und sperrt währenddessen das Verschieben. Die Karte zoomt nicht weiter heraus als Stufe 5. Der Grund: Ungebremst lief man in Open Headunit mit einem Pinch Hunderte Kilometer weit.

Stationsdetails erscheinen ab Car API 7 im selben Inhaltsfenster wie das Raster, die Karte bleibt sichtbar. Sie zoomt zur Station, standardmäßig auf Stufe 17, und kehrt beim Zurückgehen zum vorherigen Ausschnitt zurück. Die gewählte Station erscheint wie ein ausgewähltes Optionsfeld: größerer Punkt in der Gruppenfarbe mit weißem Kern. Das gibt es nur im Auto, weil auf dem Telefon das Detailblatt die Karte verdeckt. Darunter füllen die Details den Bildschirm.

Ab Car API 7 klappt ein Pfeil im Kopf des Inhaltsfensters Raster und Details ein. Das Raster zeigt dann nur die nächste Station, die Details nur Leistung, Stecker und „Navigieren“. Ganz ausblenden geht nicht, weil `MapWithContentTemplate` immer einen Inhalt verlangt und keine Größe vorgeben lässt. Mehrfaches Klappen lief im Praxistest vom 02.11.2026 ohne Abbruch; die Höhe des Fensters richtet sich dort nach dem Inhalt.

## Stationsraster

Die Startseite nutzt ab Car API 7 `GridTemplate`. Jede Kachel ist ein von der App gezeichnetes Bild mit abgerundeten Ecken und diagonalem Farbverlauf: Höchstleistung mit „kW“, darunter freie von allen Ladepunkten, etwa `1/4`, ohne Live-Daten `–/6`, und das Wort „frei“. Die Farbe zeigt die Gruppe wie auf der Karte: bis 22, 23–149, 150–299 und ab 300 kW, grau bei unbekannter Leistung. Eine kleine Legende dazu liegt auf der Karte, im Auto und auf dem Telefon. Unter der Kachel stehen Entfernung, Betreiber und Name. Spaltenzahl und genaue Kachelgröße legt der Host fest.

Kachelgröße, Startzoom (Standard 13), Zoom bei Auswahl sowie die Wege (Waymarked, OSM, Rad) lassen sich an zwei Stellen einstellen, beide teilen denselben Wert: in der Telefon-App unter „Android Auto“ frei per Schieberegler, im Auto über das Zahnrad auf der Karte. Dort öffnet jede Zeile eine Liste mit Voreinstellungen, weil die Templates weder Schieberegler noch Dropdowns kennen: 5 je Zoom, 3 für die Kachelgröße. Ein Zoom vom Telefon zwischen zwei Voreinstellungen wird im Auto als nächstliegende markiert.

Der Startpunkt liegt in der Mitte des freien Kartenbereichs rechts vom Fenster. `MapLibreMap.setPadding` wirkt erst bei der nächsten Kamerabewegung, deshalb setzt der Renderer die Kamera dafür einmal selbst, bis der Fahrer die Karte bewegt.

Ob die App im Vollbild oder geteilt läuft, bestimmt der Host; `MapWithContentTemplate` bietet dafür keine Einstellung.

Die Bilder haben 100 × 100 Pixel in ARGB_8888 und höchstens 18 Kacheln, das sind rund 700 KB, damit das ganze Raster unter 1 MB bleibt und durch einen Binder-Aufruf passt. Der Host darf weniger zulassen (`CONTENT_LIMIT_TYPE_GRID`). Weil die Kacheln nur Text erlauben, entfällt `DistanceSpan`; „Luftlinie“ steht einmal im Kopf. Hosts unter Car API 7 behalten die Liste mit `PlaceListMapTemplate`.
