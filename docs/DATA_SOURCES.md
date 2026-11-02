# Datenquellen und Lizenzen

Echtzeit-Belegung liefert keine der verwendeten Quellen. Der Filter „Frei“ existiert, zeigt aber folgerichtig nichts, und die Oberfläche erklärt das. OCPI-Feeds wären die richtige Quelle dafür, sind aber Betreibern oder Roaming-Partnern vorbehalten.

| Quelle | Zweck | Abdeckung | Limits | Lizenz | Betrieb |
|---|---|---|---|---|---|
| Open Charge Map | Stationen | weltweit, in Europa gut | Fair Use ohne feste Zahl, Schlüssel Pflicht; Sperre bei Missbrauch | CC BY 4.0, Importe je Datenanbieter | kostenlos, eigener Mirror möglich |
| OpenStreetMap über Overpass | Stationen, Wanderwege, POIs | weltweit, Details lückenhaft | Hauptserver: Größenordnung 10.000 Abfragen und 1 GB am Tag, bei regelmäßiger Nutzung ein Hundertstel | ODbL 1.0 | kostenlos, selbst hostbar |
| OpenFreeMap | Kartenkacheln | weltweit | keine Grenzen genannt, kein SLA | Attribution Pflicht; MIT für das Projekt | kostenlos |

## Lizenzpflichten

- **Open Charge Map:** Datenanbieter und Lizenz je Station sichtbar machen. Geschieht in den Stationsdetails. Importierte Daten liegen beim jeweiligen Anbieter und fallen nicht unter CC BY; `opendata=true` schränkt auf offen lizenzierte Daten ein, falls das je nötig wird.
- **OSM:** „© OpenStreetMap-Mitwirkende“ sichtbar in Einstellungen, Details und Karte. Eine öffentliche Weitergabe abgeleiteter Datenbanken löst die ODbL-Weitergabepflicht aus; im privaten Betrieb greift sie nicht.
- **OpenFreeMap:** Attribution im Auto als Einblendung auf der Karte, auf dem Telefon über MapLibre. Die Nutzungsbedingungen setzen ein Mindestalter von 18 Jahren voraus.

## Datenqualität aus dem Praxistest

Im Großraum München lieferte OSM ohne Schlüssel 316 Stationen im 50-km-Radius, bei den nächstgelegenen fehlten aber Stecker und Leistung. Das zeigt, dass ein OCM-Schlüssel den Leistungsfilter erst richtig brauchbar macht: Ohne Angabe fällt eine Station bei aktivem Filter heraus, außer „Stationen ohne Leistungsangabe behalten“ ist eingeschaltet.

## Datenschutz

Für die Suche gehen Koordinaten an Open Charge Map und Overpass, für die Karte Kachelanfragen an OpenFreeMap (ggf. über Cloudflare). Es gibt keine Registrierung und kein eigenes Tracking. Einstellungen und der API-Schlüssel liegen lokal; die Backup-Funktion ist deaktiviert.
