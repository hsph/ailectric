# PRD und Roadmap

**Ziel:** Während der Fahrt mit wenigen Eingaben eine passende Ladestation finden, optional mit Wandermöglichkeit für die Pause.

**Nutzer:** Privat, E-Auto-Fahrer auf längeren Strecken in Mittel- und Nordeuropa.

**Prinzipien:** Einfach, schnell, ehrlich. Fehlende Daten werden gekennzeichnet, nie geschätzt ausgegeben. Ein unbekannter Betriebsstatus bleibt ausgeblendet, weil die meisten Quellen keinen melden. Berechnete Werte wie die Gehzeit nach DIN 33466 oder eine gemessene Länge werden als solche bezeichnet.

## Roadmap

| Phase | Inhalt | Stand |
|---|---|---|
| 0 | Analyse, Architektur | fertig |
| 1 | Grundgerüst, Karte, Standort | fertig |
| 2 | Stationsdaten, Cache | fertig, OCM nur gegen Spezifikation getestet |
| 3 | Filter, Oberfläche, Navigation | fertig |
| 4 | Wandern und POIs | fertig bis auf Fußgängerrouting |
| 5 | Fahrzeugdaten | nächste Stufe: Akkustand (SoC) aus dem Fahrzeug; Profil und Schnittstelle vorbereitet |
| 6 | Routenplanung, eigene Navigation | später |
| 7 | Weitere POIs, Kombination von Pause und Aktivität | Kategorien im Katalog, keine Oberfläche |

## Backlog Oberfläche

GPS-Spuren als Karte, als Ersatz für eine Beliebtheits-Heatmap. Die Strava-Heatmap scheidet aus, es gibt keine Lizenz für Dritte. OSM stellt hochgeladene Spuren als Kacheln bereit, aber ohne Sportart und ohne Aussage über Beliebtheit, der Schalter dürfte also nur „GPS-Spuren“ heißen. Vorher auf der echten Karte prüfen, ob die Dichte abseits der Städte reicht, und die Nutzungsbedingungen klären.

Betreiberlogos in den Kacheln. Die Quellen liefern fast keine (bei NDW nur Q-Park und Tesla), also eine kleine Auswahl der häufigsten Betreiber fest in der App, zugeordnet über den Namen. Bei einer Veröffentlichung wären die Markenrechte zu klären.

## Backlog Fahrzeugdaten

Geschätzter SoC bei Ankunft am Lader, ausgehend vom aktuellen SoC (setzt Phase 5 voraus). Weitere Eingangswerte sind noch zu klären: am besten der Verbrauch der letzten Kilometer, falls das Auto ihn meldet, sonst Geschwindigkeit, Temperatur und Höhenunterschied. Der Wert wird in der Oberfläche als Schätzung bezeichnet.

## Länderabdeckung

Eine gute Abdeckung braucht je Land die nationale Quelle, OCM und OSM allein reichen nicht (Messwerte in `DATA_SOURCES.md`).

| Welle | Länder | Stand |
|---|---|---|
| 1 | Deutschland, Niederlande, Schweiz | nationale Quelle eingebunden |
| Backlog | Frankreich, Belgien, Luxemburg, Österreich, Italien | bisher nur OCM und OSM, Quellen recherchiert |
| Backlog, danach | Dänemark, Norwegen, Schweden, UK | nicht begonnen |

## Definition of Done

| Nr. | Kriterium | Stand |
|---|---|---|
| 1 | Projekt baut | erfüllt |
| 2 | Installation auf Android-Gerät | erfüllt auf dem Telefon |
| 3 | Android Auto stellt die App dar | erfüllt in OpenHeadUnit auf dem Telefon; DHU-Matrix und echtes Fahrzeug offen |
| 4 | Standort | erfüllt |
| 5 | Stationen im Umkreis | erfüllt |
| 6 | Karte | erfüllt |
| 7 | Filter | erfüllt, per Tests; in der Oberfläche nur Schnellfilter durchgespielt |
| 8 | Stationsdetails | erfüllt |
| 9 | Übergabe an Google Maps | erfüllt auf dem Telefon, Auto-Weg nur per Test |
| 10 | Wanderwege aus OSM | erfüllt |
| 11 | Wander- und Stationsfilter kombiniert | erfüllt |
| 12 | Ohne Fahrzeugprofil | erfüllt |
| 13 | Fehlende Echtzeitdaten korrekt | erfüllt |
| 14 | Automatisierte Tests | erfüllt |
| 15 | Anleitung | erfüllt |

Weitere offene Punkte: Die Lizenzprüfung der Kartenebenen ist unvollständig: Der rechtsverbindliche Text von EOX und die Originalseite von Waymarked Trails sind ungelesen, die Angaben dazu stammen aus der EOX-Zusammenfassung und einem Suchergebnis. Waymarked Trails bittet um einen korrekten Referrer, den die App nicht sendet. Open Charge Map ist nur gegen die offizielle Spezifikation und Testdaten geprüft, nicht gegen die Live-API. Fußgängerrouting fehlt. Die Testdaten Skandinavien, Frankreich und Benelux stehen noch aus: Live geprüft wurden bisher München, Füssen und Vaals.
