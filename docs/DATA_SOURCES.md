# Datenquellen und Lizenzen

Echtzeit-Belegung liefern NDW DOT-NL für die Niederlande und ich-tanke-strom.ch für die Schweiz. Anderswo zeigt der Filter „Frei“ folgerichtig nichts, und die Oberfläche erklärt das. NDW zählt nur freie Ladepunkte, die übrigen heißen deshalb „nicht frei“ statt „belegt“.

| Quelle | Zweck | Abdeckung | Limits | Lizenz | Betrieb |
|---|---|---|---|---|---|
| Ladesäulenregister der Bundesnetzagentur | Stationen in Deutschland | alle abgeschlossen gemeldeten öffentlichen Ladeeinrichtungen, keine Preise | mit der App ausgeliefert, keine Abfragen | CC BY 4.0, Namensnennung „Bundesnetzagentur.de“ | kostenlos, Stand siehe `app/src/main/assets/register/stand.txt` |
| NDW DOT-NL | Stationen und Live-Belegung in den Niederlanden | laut NDW alle öffentlichen Ladepunkte, 80.981 Standorte im Abruf vom 02.11.2026 | je Abfrage 1.000 Treffer, 1 Quadratgrad, dokumentiert 10 pro Sekunde, im Test aber schon bei rund 6 HTTP 429; die App fragt mit 0,4 s Pause; API laut NDW noch in Entwicklung | CC0 1.0, Namensnennung freiwillig | kostenlos, ohne Schlüssel |
| ich-tanke-strom.ch (BFE) | Stationen und Live-Status je Ladepunkt in der Schweiz | 21.034 Ladepunkte an rund 8.000 Standorten im Abruf vom 02.11.2026 | nur landesweite Dateien (etwa 1,1 MB und 0,2 MB komprimiert), keine Grenzen genannt | Opendata BY ASK: freie Nutzung, Quellenangabe Pflicht, kommerziell nur mit Bewilligung | kostenlos, ohne Schlüssel |
| Open Charge Map | Stationen | weltweit, in Europa gut | Fair Use ohne feste Zahl, Schlüssel Pflicht; Sperre bei Missbrauch | CC BY 4.0, Importe je Datenanbieter | kostenlos, eigener Mirror möglich |
| OpenStreetMap über Overpass | Stationen, Wanderwege, POIs | weltweit, Details lückenhaft | Hauptserver: Größenordnung 10.000 Abfragen und 1 GB am Tag, bei regelmäßiger Nutzung ein Hundertstel | ODbL 1.0 | kostenlos, selbst hostbar |
| OpenFreeMap | Kartenkacheln | weltweit | keine Grenzen genannt, kein SLA | Attribution Pflicht; MIT für das Projekt | kostenlos |
| EOxCloudless 2025 | Satellitenbild (Telefon) | weltweit, Sentinel-2 mit 10 m je Pixel, Details nur grob | keine festen Zahlen, Drosselung bei Spitzen | CC BY-NC-SA 4.0, nur nicht kommerziell | kostenlos bei privater Nutzung |
| Waymarked Trails | markierte Wanderrouten als Overlay (Telefon und Auto) | weltweit | moderate Zugriffe, Kacheln cachen | CC BY-SA 3.0 DE, Nennung von OSM und Waymarked Trails | kostenlos |

## Eigene Rad- und Wanderwege

`tools/overlays` baut `overlays.pmtiles` aus Geofabrik-Auszügen, nach dem Vorbild von [runair](https://github.com/hsph/runair/blob/main/docs/overlays.md). Docker ist nur Werkbank für `osmium`, `tippecanoe` und `pmtiles`; die App braucht es nicht. Im Build vom 02.11.2026 ergaben Deutschland und die Niederlande rund 2,4 Mio. Wege in 260 MB, die Laufzeit lag bei 15 Minuten. Das Rohmaterial belegt gut 6 GB, deshalb liegt es in einem Docker-Volume:

```sh
docker build -t ailectric-overlays tools/overlays
docker volume create ailectric-osm
docker run --rm -v ailectric-osm:/in --entrypoint sh ailectric-overlays -c \
  'cd /in && for r in germany netherlands; do curl -fsSL -o $r.osm.pbf https://download.geofabrik.de/europe/$r-latest.osm.pbf; done'
docker run --rm -v ailectric-osm:/in:ro -v "$PWD:/out" ailectric-overlays /in/germany.osm.pbf /in/netherlands.osm.pbf
adb push overlays.pmtiles /sdcard/Android/data/app.ailectric/files/overlays.pmtiles
```

Es erscheint nur, was in OSM erfasst ist. Die Daten stehen unter ODbL, die App nennt sie auf der Karte. Die Wegfarben liegen abseits der Leistungsfarben: Rad dunkelblau statt des Teals aus runair, das mit der Gruppe 23–149 kW verwechselbar wäre.

## Zusammenführung

Die App fragt alle Quellen parallel ab und zeigt Ergebnisse, sobald die erste antwortet. Vorrang haben die nationalen Quellen (Register, NDW, ich-tanke-strom.ch), dann Open Charge Map, dann OSM. Eine Station einer späteren Quelle entfällt, wenn eine frühere sie schon hat: bis 100 m Abstand bei gleichem Betreiber, bis 30 m wenn ein Betreiber fehlt. Unterschiedliche Betreiber bleiben getrennt. Die Dublette entfällt ganz, damit jedes Feld von der angezeigten Quelle stammt; ein Preis aus Open Charge Map geht dabei verloren.

Im Vergleich vom 02.11.2026 (Register-Stand 01.09.2026, 30 km Radius) hatten von 4.053 Register-Standorten in Berlin 3.183 keinen OCM-Eintrag und 2.183 keinen OSM-Eintrag im Umkreis von 75 m, in Hamburg 1.818 und 1.373 von 2.706. Von den 77 Vattenfall-Standorten in Berlin fehlten 76 bei OCM und 42 bei OSM. Das ist eine Obergrenze, weil Quellen dieselbe Station auch weiter entfernt verorten können.

NDW-Daten gelten 5 Minuten als aktuell, eine Suche im Umkreis von 75 km kostet rund 27 Abfragen. Ältere Kopien erscheinen nur offline und dann ohne Belegung. Tarife liefert die Gebiets-API nicht; sie stehen in der separaten Datei `charging_point_tariffs_ocpi.json.gz`, die die App noch nicht nutzt.

Rund um Zürich (15 km, 02.11.2026) fehlten von 553 offiziellen Standorten 486 bei OCM und 346 bei OSM, 323 in beiden.

## Register aktualisieren

Die Bundesnetzagentur veröffentlicht die Liste regelmäßig neu. CSV von der [Ladesäulenkarte](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html) laden, `tools/build_register.py <datei.csv>` ausführen, App neu bauen. Das Verfahren ist manuell, weil die Dateinamen das Datum tragen und die öffentliche Schnittstelle keine veröffentlichte Adresse hat.

## Lizenzpflichten

- **Ladesäulenregister:** Namensnennung „Bundesnetzagentur.de“ in den Stationsdetails und Einstellungen.
- **ich-tanke-strom.ch:** Quelle „Bundesamt für Energie BFE“ in Details und Einstellungen. Kommerzielle Nutzung wäre genehmigungspflichtig.
- **NDW DOT-NL:** CC0; die App nennt die Quelle trotzdem in Details und Einstellungen.
- **Open Charge Map:** Datenanbieter und Lizenz je Station sichtbar machen. Geschieht in den Stationsdetails. Importierte Daten liegen beim jeweiligen Anbieter und fallen nicht unter CC BY; `opendata=true` schränkt auf offen lizenzierte Daten ein, falls das je nötig wird.
- **OSM:** „© OpenStreetMap-Mitwirkende“ sichtbar in Einstellungen, Details und Karte. Eine öffentliche Weitergabe abgeleiteter Datenbanken löst die ODbL-Weitergabepflicht aus; im privaten Betrieb greift sie nicht.
- **EOxCloudless, Waymarked Trails:** Nennung als Einblendung auf der Karte, solange die Ansicht aktiv ist, und in den Einstellungen. Eine Veröffentlichung als kostenpflichtige App oder mit Werbung schließt EOxCloudless aus.
- **OpenFreeMap:** Attribution im Auto als Einblendung auf der Karte, auf dem Telefon über MapLibre. Die Nutzungsbedingungen setzen ein Mindestalter von 18 Jahren voraus.

## Datenqualität aus dem Praxistest

Im Großraum München lieferte OSM ohne Schlüssel 316 Stationen im 50-km-Radius, bei den nächstgelegenen fehlten aber Stecker und Leistung. Das zeigt, dass ein OCM-Schlüssel den Leistungsfilter erst richtig brauchbar macht: Ohne Angabe fällt eine Station bei aktivem Filter heraus, außer „Stationen ohne Leistungsangabe behalten“ ist eingeschaltet.

## Datenschutz

Das Register liegt in der App und sendet nichts. Die Kartenebenen fragen ihre Kacheln bei EOX und Waymarked Trails ab, sobald sie eingeschaltet sind; damit sehen diese Dienste den Kartenausschnitt. Für die Suche gehen Koordinaten an Open Charge Map und Overpass, in den Niederlanden und an der Grenze ein Kartenausschnitt an NDW. Die Schweizer Dateien lädt die App nahe der Schweiz ganz, dabei gehen keine Koordinaten an den Server, für die Karte Kachelanfragen an OpenFreeMap (ggf. über Cloudflare). Es gibt keine Registrierung und kein eigenes Tracking. Einstellungen und der API-Schlüssel liegen lokal; die Backup-Funktion ist deaktiviert.
