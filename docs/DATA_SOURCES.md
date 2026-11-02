# Datenquellen und Lizenzen

Echtzeit-Belegung liefert keine der verwendeten Quellen. Der Filter „Frei“ existiert, zeigt aber folgerichtig nichts, und die Oberfläche erklärt das. OCPI-Feeds wären die richtige Quelle dafür, sind aber Betreibern oder Roaming-Partnern vorbehalten.

| Quelle | Zweck | Abdeckung | Limits | Lizenz | Betrieb |
|---|---|---|---|---|---|
| Ladesäulenregister der Bundesnetzagentur | Stationen in Deutschland | alle abgeschlossen gemeldeten öffentlichen Ladeeinrichtungen, keine Preise | mit der App ausgeliefert, keine Abfragen | CC BY 4.0, Namensnennung „Bundesnetzagentur.de“ | kostenlos, Stand siehe `app/src/main/assets/register/stand.txt` |
| Open Charge Map | Stationen | weltweit, in Europa gut | Fair Use ohne feste Zahl, Schlüssel Pflicht; Sperre bei Missbrauch | CC BY 4.0, Importe je Datenanbieter | kostenlos, eigener Mirror möglich |
| OpenStreetMap über Overpass | Stationen, Wanderwege, POIs | weltweit, Details lückenhaft | Hauptserver: Größenordnung 10.000 Abfragen und 1 GB am Tag, bei regelmäßiger Nutzung ein Hundertstel | ODbL 1.0 | kostenlos, selbst hostbar |
| OpenFreeMap | Kartenkacheln | weltweit | keine Grenzen genannt, kein SLA | Attribution Pflicht; MIT für das Projekt | kostenlos |

## Zusammenführung

Die App fragt alle Quellen parallel ab und zeigt Ergebnisse, sobald die erste antwortet. Vorrang hat das Register, dann Open Charge Map, dann OSM. Eine Station einer späteren Quelle entfällt, wenn eine frühere sie schon hat: bis 100 m Abstand bei gleichem Betreiber, bis 30 m wenn ein Betreiber fehlt. Unterschiedliche Betreiber bleiben getrennt. Die Dublette entfällt ganz, damit jedes Feld von der angezeigten Quelle stammt; ein Preis aus Open Charge Map geht dabei verloren.

Im Vergleich vom 02.11.2026 (Register-Stand 01.09.2026, 30 km Radius) hatten von 4.053 Register-Standorten in Berlin 3.183 keinen OCM-Eintrag und 2.183 keinen OSM-Eintrag im Umkreis von 75 m, in Hamburg 1.818 und 1.373 von 2.706. Von den 77 Vattenfall-Standorten in Berlin fehlten 76 bei OCM und 42 bei OSM. Das ist eine Obergrenze, weil Quellen dieselbe Station auch weiter entfernt verorten können.

## Register aktualisieren

Die Bundesnetzagentur veröffentlicht die Liste regelmäßig neu. CSV von der [Ladesäulenkarte](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html) laden, `tools/build_register.py <datei.csv>` ausführen, App neu bauen. Das Verfahren ist manuell, weil die Dateinamen das Datum tragen und die öffentliche Schnittstelle keine veröffentlichte Adresse hat.

## Lizenzpflichten

- **Ladesäulenregister:** Namensnennung „Bundesnetzagentur.de“ in den Stationsdetails und Einstellungen.
- **Open Charge Map:** Datenanbieter und Lizenz je Station sichtbar machen. Geschieht in den Stationsdetails. Importierte Daten liegen beim jeweiligen Anbieter und fallen nicht unter CC BY; `opendata=true` schränkt auf offen lizenzierte Daten ein, falls das je nötig wird.
- **OSM:** „© OpenStreetMap-Mitwirkende“ sichtbar in Einstellungen, Details und Karte. Eine öffentliche Weitergabe abgeleiteter Datenbanken löst die ODbL-Weitergabepflicht aus; im privaten Betrieb greift sie nicht.
- **OpenFreeMap:** Attribution im Auto als Einblendung auf der Karte, auf dem Telefon über MapLibre. Die Nutzungsbedingungen setzen ein Mindestalter von 18 Jahren voraus.

## Datenqualität aus dem Praxistest

Im Großraum München lieferte OSM ohne Schlüssel 316 Stationen im 50-km-Radius, bei den nächstgelegenen fehlten aber Stecker und Leistung. Das zeigt, dass ein OCM-Schlüssel den Leistungsfilter erst richtig brauchbar macht: Ohne Angabe fällt eine Station bei aktivem Filter heraus, außer „Stationen ohne Leistungsangabe behalten“ ist eingeschaltet.

## Datenschutz

Das Register liegt in der App und sendet nichts. Für die Suche gehen Koordinaten an Open Charge Map und Overpass, für die Karte Kachelanfragen an OpenFreeMap (ggf. über Cloudflare). Es gibt keine Registrierung und kein eigenes Tracking. Einstellungen und der API-Schlüssel liegen lokal; die Backup-Funktion ist deaktiviert.
