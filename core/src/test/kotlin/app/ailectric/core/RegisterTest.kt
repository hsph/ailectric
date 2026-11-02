package app.ailectric.core

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import app.ailectric.core.provider.RegisterProvider
import app.ailectric.core.provider.StationQuery
import app.ailectric.core.search.mergeStations
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegisterTest {
    private val vattenfall = "1099667\tVattenfall Smarter Living GmbH\t\tHufelandweg 16\t10117\tBerlin\t52.527469\t13.376976\t1\t\t\t" +
        "DE*VAT*E1~TYPE2:22|~TYPE2:22+CCS2:150"
    private val inMaintenance = "2\tMuster\tParkhaus\t\t\t\t52.6\t13.4\t0\tMo-Fr 06:00-20:00\tNur für Kunden/Besucher\t~OTHER_DC:"
    private val provider = RegisterProvider("2026-09-01") { name -> if (name == "105_26.tsv") "$vattenfall\n$inMaintenance\n" else null }

    @Test fun `a line becomes a station with its charge points`() {
        val s = provider.parse(vattenfall)!!
        assertThat(s.id).isEqualTo("bnetza:1099667")
        assertThat(s.operator).isEqualTo("Vattenfall Smarter Living GmbH")
        assertThat(s.name).isNull()
        assertThat(s.address.singleLine()).isEqualTo("Hufelandweg 16, 10117 Berlin, DE")
        assertThat(s.pointCount).isEqualTo(2)
        assertThat(s.maxPowerKw).isEqualTo(150.0)
        assertThat(s.devices[0].reference).isEqualTo("DE*VAT*E1")
        assertThat(s.devices[1].connectors.map { it.type }).containsExactly(ConnectorType.TYPE2, ConnectorType.CCS2).inOrder()
        assertThat(s.devices[1].connectors[1].currentType).isEqualTo(CurrentType.DC)
        assertThat(s.status).isEqualTo(OperationalStatus.OPERATIONAL)
        assertThat(s.source.license).isEqualTo("CC BY 4.0")
        assertThat(s.lastUpdated).isEqualTo("2026-09-01")
    }

    @Test fun `unknown values stay unknown`() {
        val s = provider.parse(inMaintenance)!!
        assertThat(s.status).isEqualTo(OperationalStatus.NOT_OPERATIONAL)
        assertThat(s.maxPowerKw).isNull()
        assertThat(s.pricing).isNull()
        assertThat(s.connectors.single().type).isEqualTo(ConnectorType.OTHER)
    }

    @Test fun `search reads the tiles around the query and honours the radius`() = runTest {
        val r = provider.search(StationQuery(GeoPoint(52.52, 13.40), 5_000.0))
        assertThat(r.stations.map { it.id }).containsExactly("bnetza:1099667")
        assertThat(provider.search(StationQuery(GeoPoint(48.0, 11.0), 5_000.0)).stations).isEmpty()
    }

    @Test fun `a broken line is skipped`() {
        assertThat(provider.parse("kaputt")).isNull()
    }
}

class MergeTest {
    private fun at(id: String, lat: Double, lon: Double, operator: String?): Station =
        station(CCS_350).copy(id = id, location = GeoPoint(lat, lon), operator = operator)

    @Test fun `the same site in a later source is dropped, the earlier source wins`() {
        val register = at("bnetza:1", 52.5000, 13.4000, "Vattenfall Smarter Living GmbH")
        val ocm = at("ocm:1", 52.5005, 13.4000, "Vattenfall") // about 55 m away
        assertThat(mergeStations(listOf(listOf(register), listOf(ocm)))).containsExactly(register)
    }

    @Test fun `a station only one source knows is kept`() {
        val register = at("bnetza:1", 52.5, 13.4, "EnBW")
        val ocm = at("ocm:1", 52.6, 13.4, "EnBW")
        assertThat(mergeStations(listOf(listOf(register), listOf(ocm)))).containsExactly(register, ocm)
    }

    @Test fun `neighbours of different operators stay apart`() {
        val a = at("bnetza:1", 52.5, 13.4, "EnBW")
        val b = at("ocm:1", 52.5, 13.4001, "Aral pulse")
        assertThat(mergeStations(listOf(listOf(a), listOf(b)))).containsExactly(a, b)
    }

    @Test fun `without an operator only the same spot counts`() {
        val a = at("bnetza:1", 52.5, 13.4, "EnBW")
        val close = at("osm:1", 52.5001, 13.4, null) // 11 m
        val away = at("osm:2", 52.5005, 13.4, null) // 55 m
        assertThat(mergeStations(listOf(listOf(a), listOf(close, away)))).containsExactly(a, away)
    }

    @Test fun `stations within one source are never merged`() {
        val a = at("bnetza:1", 52.5, 13.4, "EnBW")
        val b = at("bnetza:2", 52.5, 13.4, "EnBW")
        assertThat(mergeStations(listOf(listOf(a, b)))).containsExactly(a, b)
    }
}
