# TrueNAS: Vorschlag

**Noch nichts installiert, und es ist für das MVP nichts nötig.** Die konkreten Ressourcenzahlen hängen von Angaben ab, die noch fehlen:

- TrueNAS-Version (SCALE oder CORE) und Containerfunktion
- CPU, RAM, freier Speicher, möglichst SSD
- Vorhandene Container und bestehende Cloudflare-Tunnel

## Stufenplan

| Stufe | Dienst | Nutzen | Bedarf |
|---|---|---|---|
| 0 | keiner | App läuft eigenständig | keiner |
| 1 | Overpass mit Extrakt (DACH oder Zielländer) | Wandersuche unabhängig von überlasteten Servern, Hauptnutzen | hoch, bei Europa auch im Speicher; vor Festlegung Extrakt wählen |
| 2 | Valhalla mit Extrakt | Echte Gehstrecken, Grundlage für Routing | hoch, Berechnung der Graphen ist aufwendig |
| 3 | PostGIS mit eigener API | Nur bei Bedarf, etwa eigener Datenbestand oder OCM-Mirror | mittel |

Empfehlung: Mit Stufe 1 beginnen, weil dort heute der einzige echte Engpass liegt. PostGIS und eigene API erst, wenn ein konkreter Bedarf da ist.

## Zugriff

- Im Heimnetz direkt, von unterwegs über Cloudflare Tunnel, ohne offene Ports.
- Der Tunnel ersetzt keine Authentifizierung. Davor gehört Cloudflare Access mit Service-Token oder ein API-Token im Reverse-Proxy.
- Die App sendet heute noch keinen Auth-Header an den eigenen Overpass-Endpoint. Das muss vor einem Betrieb über den Tunnel ergänzt werden.

Fällt der Server aus, greifen die öffentlichen Endpoints und der lokale Cache.

## Beispiel Stufe 1

Vorlage, ungetestet. Abbild und Variablen vor dem Einsatz prüfen.

```yaml
services:
  overpass:
    image: wiktorn/overpass-api
    restart: unless-stopped
    ports: ["127.0.0.1:12345:80"]
    environment:
      OVERPASS_MODE: init
      OVERPASS_META: "no"
      OVERPASS_PLANET_URL: https://download.geofabrik.de/europe/dach-latest.osm.bz2
    volumes:
      - /mnt/POOL/ailectric/overpass:/db
```

Die URL dann in der App unter Einstellungen als eigener Overpass-Server eintragen.
