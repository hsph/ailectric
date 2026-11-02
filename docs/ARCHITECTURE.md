# Architektur und Entscheidungen

Stand: November 2026. Die Android-Auto-Fakten stammen aus der offiziellen Dokumentation und wurden gegen die Car App Library 1.7.0 geprüft.

## Überblick

```
Android Auto (Car-Screens)   Telefon (Compose)
            \                 /
             SearchSession (ein gemeinsamer Zustand)
                      |
             ChargerSearch  ─  Filter, Wander-Kombination
              /            \
   StationRepository     PoiProvider (Overpass)
     /        \
 Provider     SearchCache (Room)
 OCM → OSM
```

Die Logikmodule aus dem Auftrag sind als Pakete in `core` und `app` umgesetzt, nicht als einzelne Gradle-Module. Zwei Module reichen für die wichtige Grenze, nämlich Logik ohne Android-Abhängigkeit und damit JVM-Tests. Zwölf Module wären beim jetzigen Umfang reiner Mehraufwand.

## Entscheidungen

### ADR 1: Android-Auto-Kategorie POI mit eigener Karte

**Entscheidung:** Kategorie `androidx.car.app.category.POI`, Haupt-Screen `MapWithContentTemplate` (ab Car API 7), darunter `PlaceListMapTemplate`.

| Option | Karte | Pan/Zoom | Bewertung |
|---|---|---|---|
| POI + `MapWithContentTemplate` | von der App gezeichnet | ja, über `SurfaceCallback` und `Action.PAN` | gewählt |
| POI + `PlaceListMapTemplate` | vom Host gezeichnet | nicht dokumentiert | Fallback für alte Hosts |
| Navigation | von der App gezeichnet | ja | Kategorie verlangt Turn-by-Turn, das MVP navigiert nicht selbst |

Die Annahme aus dem Auftrag, eine interaktive Karte sei eventuell nur in einer Navigations-App möglich, trifft nicht zu: Die Dokumentation erlaubt POI-Apps das Zeichnen auf dem Surface. Die Kategorie `CHARGING` ist seit Library 1.3 zugunsten von POI veraltet. Details: [ANDROID_AUTO.md](ANDROID_AUTO.md).

### ADR 2: Kartenbibliothek MapLibre Native, Kacheln von OpenFreeMap

MapLibre Android SDK 13.7.1 mit dem Stil `liberty`. OpenFreeMap verlangt keinen Schlüssel und keine Registrierung. Die OSMF-Rasterkacheln sind keine Alternative: Offline-Nutzung ist dort untersagt, und Verfügbarkeit gibt es nur nach bestem Bemühen. OpenFreeMap hat ebenfalls kein SLA. Fällt es aus, funktioniert die Liste weiter, und die Stil-URL liegt an einer Stelle (`StationLayers.STYLE_URL`).

Im Auto hostet eine `Presentation` auf einem `VirtualDisplay` über dem Surface eine normale `MapView`. Das ist deutlich weniger Code als ein eigener GL-Renderer und liefert dieselbe Karte wie auf dem Telefon.

### ADR 3: Ladestationen von Open Charge Map, OSM als Fallback

OCM ist die Hauptquelle, braucht aber einen Schlüssel und liefert keine Live-Belegung. OSM (`amenity=charging_station`) springt ohne Schlüssel oder bei OCM-Ausfall ein, sodass die App immer nutzbar ist. Quellen werden nicht vermischt: Eine Station je Quelle würde sonst doppelt erscheinen, und eine Dublettenerkennung wäre für das MVP unverhältnismäßig.

### ADR 4: POI-Daten von OSM über Overpass, Endpoint konfigurierbar

Kategorien sind Daten (`PoiCatalog`): Selektor plus optionale Marke. Supermärkte und Ketten brauchen dadurch keinen neuen Code; die Abfrage ist getestet, eine Oberfläche dafür gibt es noch nicht.

Öffentliche Overpass-Server sind oft überlastet (im Test HTTP 502) und nennen als Zweck ausdrücklich menschliche Nutzung. Daher: mehrere Endpoints mit Fallback, höchstens 24 geprüfte Stationen je Suche, 30 Tage Cache, optionaler eigener Server. Die Wander-Abfrage nutzt eine gemeinsame Bounding Box mit typisierten Selektoren und prüft Entfernungen auf dem Gerät; je Station eine `around`-Abfrage wurde von Servern abgelehnt.

### ADR 5: Datenmodell

`Station` → `ChargingDevice` → `Connector`. Unbekannt ist immer `null` beziehungsweise `UNKNOWN`, nie ein Ersatzwert. Zwei Dinge sind bewusst getrennt:

- `OperationalStatus` (Registerdaten, langlebig) und `Availability` (Echtzeit). „Operational“ bei OCM wird nie zu „frei“.
- `Distance` trägt immer ihre Art (`AIRLINE`, `DRIVING`, `WALKING`), die Oberfläche kann sie nicht ohne Bezeichnung anzeigen.

Der Leistungs-, Stecker- und Stromfilter gilt für denselben Connector: „Typ 2 ab 150 kW“ trifft nicht auf eine Station mit 300 kW CCS und 22 kW Typ 2 zu.

### ADR 6: Caching

Room speichert Stationen je Provider sowie die abgedeckten Kreise. Jede Abfrage holt 25 km mehr als nötig, sodass Fahrten bis zu dieser Strecke keine API-Abfrage auslösen. Abdeckungen gelten 7 Tage, POIs 30 Tage. Scheitern alle Quellen, zeigt die App alte Daten, sichtbar als „offline: ältere Daten“ markiert.

### ADR 7: Zunächst kein eigenes Backend

Telefon und Auto sprechen direkt mit den Quellen. Grundfunktionen hängen damit nie am TrueNAS-Server. Der erste konkrete Bedarf wäre ein eigener Overpass-Dienst, siehe [SERVER.md](SERVER.md).

### ADR 8: Framework-Standort statt Play Services

`LocationManager` reicht im Auto und spart eine Abhängigkeit. Registriert werden alle Provider, weil `fused` existieren und trotzdem deaktiviert sein kann.

## Vorbereitet, nicht implementiert

- **Fahrzeugdaten:** `VehicleValue` mit Wert, Einheit, Quelle, Zeitstempel und Status; bisher nur die manuelle Quelle. Das Profil beeinflusst die Suche noch nicht.
- **Routing:** `RoutingProvider`, bisher nur die Luftlinie. Fußwege sind deshalb nicht geprüft, und die App sagt das.
- **Navigation:** Eine einzige Stelle (`Navigation`) übergibt das Ziel.
- **Map Matching:** Bewusst nichts vorweggenommen; es wäre ein neuer Provider neben `RoutingProvider`.
