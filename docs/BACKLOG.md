# Backlog

Offene Punkte mit den Ergebnissen, die zu ihnen geführt haben. Stand: 03.11.2026.

## Offene Entscheidungen

| Frage | Empfehlung | Hängt an |
|---|---|---|
| Satellit im Auto anbieten? | Erst mit hellem Ring um die Stationen | Nachtkarte, siehe unten |
| PDOK für die Niederlande ergänzen? | Ja, sobald Satellit im Auto kommt | Löst Deutschland nicht |
| Live-Belegung für Deutschland bauen? | Erst nach einem Probe-Abo entscheiden | Mobilithek-Zugang |

## Live-Belegung für Deutschland

Grundsätzlich möglich über die [Mobilithek](https://mobilithek.info), den nationalen Zugangspunkt. Die Betreiber müssen seit April 2025 liefern, seit dem 14.04.2026 im Format DATEX II (Profile `AFIR-Recharging-Static-01-00-00` und `AFIR-Recharging-Dynamic-01-00-00_Delta`). Die Daten sind kostenlos. Die App kann Belegung schon anzeigen und filtern, es fehlt nur die Quelle.

Nächste Schritte:

1. Bei der Mobilithek registrieren und ein Probe-Abo abschließen, am besten EnBW. Das kann nur der Nutzer selbst.
2. Mit dem Zertifikat echte Daten abrufen und die Zuordnung zu unseren Stationen messen.
3. Danach über den Server entscheiden.

Bekannte Hürden:

- **Zugang:** Organisationskonto, je Angebot ein Abo, beim geprüften Angebot mit Freigabe durch den Anbieter. Ob Privatpersonen zugelassen sind, ist ungeklärt.
- **Abruf:** Laut einer Drittquelle mTLS mit Client-Zertifikat über `https://mobilithek.info:8443`, Adresse je Abo. Ein Zertifikat sollte nicht im öffentlichen Repo landen.
- **Kein zentraler Feed:** Im Mai 2026 angekündigt, am 01.11.2026 laut Drittquelle nicht gefunden. Aggregatoren wie MOBIDROM und MobiData BW könnten mehrere Betreiber bündeln.
- **Server:** Es kommen vor allem Änderungen, überschlägig 12 je Sekunde bundesweit, komplette Stände nur etwa alle 6 Stunden. Das spricht für einen kleinen Sammeldienst, zum Beispiel auf dem TrueNAS ([SERVER.md](SERVER.md)). Fällt er aus, fehlt nur die Belegung.
- **Zuordnung:** Nur 31,3 % der 209.141 Ladepunkte im Register haben eine Kennung (EnBW 18,5 %, Tesla 37,0 %, Aral Pulse und Mercedes 0 %, Wirelane 100 %). Ob sie den EVSE-IDs der Live-Daten entspricht, ist ungeprüft. Die Registernummer der Bundesnetzagentur ist im Live-Format nur optional. Es braucht also zusätzlich die Stammdaten der Mobilithek und einen Abgleich über die Position.
- **Abdeckung:** EnBW und Tesla liefern nachweislich. EWE Go, Aral und Ionity tauchen in Statusdaten auf, zu Shell ist nichts bekannt. AutomatenWegweiser sah am 02.11.2026 frische Daten für 47 % der Schnelllader, ChargePointRadar nennt 175.701 Ladepunkte. Die Zahlen sind nicht direkt vergleichbar.
- **Lizenz:** Geprüfte Angebote stehen unter CC BY 4.0, die Datengeber sind einzeln zu nennen.
- **Status deuten:** `available`, `charging` und `reserved` wechseln im Normalbetrieb. Das Feld `isAvailable` der Station heißt nicht, dass ein Stecker frei ist.

Ungeklärt: wie ein Abrufer Änderungen lückenlos bekommt, Datenmengen, Abruflimits. Vieles stammt aus der [Recherche von ha_curbcharge](https://github.com/matthijssn/ha_curbcharge/blob/main/docs/provider-research/germany.md), die keine echten Antworten dokumentiert. Die [offizielle Schnittstellenbeschreibung](https://mobilithek.info/cms/downloads/tssb-de) und die Abo-FAQ der Mobilithek sind noch nicht ausgewertet. Antworten auf Betreiberfragen stehen in der [FAQ zum Datenprofil](https://github.com/MobilithekDE/AFIR-DATEX-II-Recharging-Profil/wiki/FAQ--zur-Datenbereitstellung-durch-Betreiber-%C3%B6ffentlich-zug%C3%A4nglicher-Ladeinfrastruktur-im-DATEX%E2%80%90II%E2%80%90Standard).

## Satellit

Getestet am 02.11.2026 über dieselbe Darstellung wie im Auto. Das Bild liegt seitdem unter den Beschriftungen der Grundkarte.

| Quelle | Gebiet | Ergebnis |
|---|---|---|
| EOxCloudless | weltweit | Bis etwa Zoom 13 brauchbar, bei Zoom 16 unscharf (10 m je Pixel). Bleibt in der App |
| PDOK Luchtfoto | Niederlande | Sehr scharf, CC BY 4.0. URL: `https://service.pdok.nl/hwh/luchtfotorgb/wmts/v1_0/Actueel_orthoHR/EPSG:3857/{z}/{x}/{y}.jpeg` |
| Esri World Imagery | weltweit | Scharf, in der Eifel mit Wolken, Lizenz widersprüchlich. Nicht verwenden, solange das ungeklärt ist |
| Länderdienste | je Bundesland | Einzelne offene Dienste, Lizenzen verschieden, nicht getestet. Eine bundesweite offene Quelle ist nicht bekannt |

Für Satellit im Auto brauchen die Stationen einen hellen Ring: Der dunkle verschwindet auf dem Bild, und der graue Punkt für unbekannte Leistung ist kaum zu sehen. Der Zoom bei Auswahl einer Station liegt mit 17 weit über dem, was EOX zeigt.

## Kartenansichten

Am 02.11.2026 auf dem Telefon gerendert, noch nicht eingebaut:

- **Nachtkarte:** OpenFreeMap `dark`. `carContext.isDarkMode` wird noch nicht ausgewertet, die Autokarte ist auch nachts hell. Braucht denselben hellen Ring wie Satellit.
- **Relief:** `HillshadeLayer` mit Höhendaten von Mapterhorn, ohne Schlüssel. Beste Allround-Ansicht im Vergleich, aber ein Ersatz für die entfernte Geländekarte ist bisher nicht beauftragt. Die Nutzungsbedingungen von Mapterhorn sind ungeprüft.
- **Höhenfarben:** `ColorReliefLayer`, die grünen Täler konkurrieren mit grünen Stationen.
- **swisstopo:** Offizielle Wanderwege, nur Schweiz, Quellenangabe nötig.

Verworfen: 3D-Gelände, weil MapLibre Native es nicht kann und alle Karten 2D bleiben sollen. Maptoolkit, weil die Bedingungen ein dauerhaftes Logo und anklickbare Links verlangen und Navigation ausschließen. Höhenlinien aus Höhendaten, weil es `maplibre-contour` nur für GL JS gibt.

## Eigene Rad- und Wanderwege

- **Mehr Länder:** Die Datei deckt nur Deutschland und die Niederlande ab. Ganz Europa (35 GB Rohdaten) passt nicht auf den Mac; das Docker-Volume liegt in der Docker-VM mit rund 40 GB frei.
- **Aktualität:** Die Datei ist ein Stand vom 02.11.2026 und wird nur von Hand erneuert, siehe [DATA_SOURCES.md](DATA_SOURCES.md).
- **Datei fehlt:** Die Schalter bleiben dann ohne Hinweis wirkungslos. Die Datei liegt nur auf dem Telefon und ist nicht im Repo. Bei einer Neuinstallation der App muss sie neu kopiert werden.
- **Unmarkierte Wege:** Die `footpaths` aus runair fehlen, weil sie die Protomaps-Grundkarte voraussetzen. Mit OpenFreeMap ginge es über `transportation`, aber nur bis zu den dort enthaltenen Wegen; bei Zoom 13 fehlen kleine Wege.
- **Rad und Satellit:** Das dunkle Blau der Radwege dürfte auf Satellit schlecht zu sehen sein, getestet ist das nicht.

## Tests

- Echtes Android Auto steht aus: Bisher lief alles über die Darstellung auf dem Telefon, nicht in Headunit oder Auto.
- Die neuen Schalter sind nicht von Hand durchgespielt.
- Die Testmatrix in [TESTING.md](TESTING.md) ist noch offen.
