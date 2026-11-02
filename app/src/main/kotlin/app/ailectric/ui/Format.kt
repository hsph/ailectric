package app.ailectric.ui

import app.ailectric.core.model.Availability
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import app.ailectric.core.search.Freshness

/** Texts shared by phone and car. Missing data always reads "unbekannt", never a guess. */
object Format {
    const val UNKNOWN = "unbekannt"

    fun title(s: Station) = s.name ?: s.operator ?: s.address.street ?: "Ladestation"

    fun power(s: Station) = s.maxPowerKw?.let { "${it.toInt()} kW" } ?: "Leistung $UNKNOWN"

    fun plugs(s: Station) =
        s.connectorTypes.joinToString(", ") { it.label }.ifBlank { "Stecker $UNKNOWN" }

    fun availability(a: Availability) = when (a) {
        Availability.AVAILABLE -> "frei"
        Availability.OCCUPIED -> "belegt"
        Availability.OUT_OF_ORDER -> "außer Betrieb"
        Availability.UNKNOWN -> "Belegung $UNKNOWN"
    }

    fun status(s: OperationalStatus) = when (s) {
        OperationalStatus.OPERATIONAL -> "in Betrieb"
        OperationalStatus.NOT_OPERATIONAL -> "nicht in Betrieb"
        OperationalStatus.PLANNED -> "geplant"
        OperationalStatus.UNKNOWN -> "Betriebsstatus $UNKNOWN"
    }

    /** OSM's `access` values; OCM already sends readable text, which passes through. */
    fun access(raw: String?) = when (raw) {
        "yes", "public" -> "öffentlich"
        "customers" -> "nur für Kunden"
        "permissive" -> "geduldet"
        "private", "no" -> "privat"
        else -> raw
    }

    fun freshness(f: Freshness) = when (f) {
        Freshness.LIVE_FETCH -> "aktuell geladen"
        Freshness.CACHED -> "aus dem Cache"
        Freshness.STALE_OFFLINE -> "offline: ältere Daten"
    }
}
