package app.ailectric.core.model

import app.ailectric.core.geo.GeoPoint
import kotlinx.serialization.Serializable

/** Configurable POI category. New ones are data, not code: see [PoiCatalog]. */
data class PoiCategory(
    val id: String,
    val group: String,
    val label: String,
    /** Overpass selectors with element type: `nwr` where the type varies, narrower ones are much cheaper. */
    val osmSelectors: List<String>,
    /** Restrict to these brands (matched on `brand`), e.g. a single supermarket chain. */
    val brands: Set<String> = emptySet(),
)

object PoiCatalog {
    val HIKING_ROUTE = PoiCategory("hiking.route", "Freizeit", "Wanderweg", listOf("""rel["route"="hiking"]"""))
    val HIKING_PATH = PoiCategory("hiking.path", "Freizeit", "Wanderpfad", listOf("""way["highway"="path"]["name"]"""))
    val TRAILHEAD = PoiCategory("hiking.trailhead", "Freizeit", "Wanderweg-Einstieg", listOf("""node["highway"="trailhead"]"""))
    val HIKING_PARKING = PoiCategory("hiking.parking", "Freizeit", "Wanderparkplatz", listOf("""nwr["amenity"="parking"]["hiking"="yes"]"""))
    val VIEWPOINT = PoiCategory("nature.viewpoint", "Freizeit", "Aussichtspunkt", listOf("""node["tourism"="viewpoint"]"""))
    val PEAK = PoiCategory("nature.peak", "Freizeit", "Gipfel", listOf("""node["natural"="peak"]["name"]"""))

    val SUPERMARKET = PoiCategory("shop.supermarket", "Einkaufen", "Supermarkt", listOf("""nwr["shop"="supermarket"]"""))
    val CAFE = PoiCategory("food.cafe", "Gastronomie", "Café", listOf("""nwr["amenity"="cafe"]"""))
    val RESTAURANT = PoiCategory("food.restaurant", "Gastronomie", "Restaurant", listOf("""nwr["amenity"="restaurant"]"""))
    val BAKERY = PoiCategory("food.bakery", "Gastronomie", "Bäckerei", listOf("""nwr["shop"="bakery"]"""))
    val TOILET = PoiCategory("infra.toilet", "Infrastruktur", "Öffentliche Toilette", listOf("""nwr["amenity"="toilets"]"""))

    fun supermarket(brand: String) = SUPERMARKET.copy(id = "shop.supermarket.$brand", label = brand, brands = setOf(brand))

    /** The MVP's hiking module. Other categories above work the same way but have no UI yet. */
    val HIKING = listOf(HIKING_ROUTE, HIKING_PATH, TRAILHEAD, HIKING_PARKING, VIEWPOINT)
}

@Serializable
data class Poi(
    val id: String,
    val categoryId: String,
    val name: String?,
    /** Representative point; prefer [geometry] for distances. */
    val location: GeoPoint,
    /** Polyline(s) for linear POIs such as trails, empty for point POIs. */
    val geometry: List<List<GeoPoint>> = emptyList(),
    val brand: String? = null,
    val hiking: HikingDetails? = null,
    val source: DataSource,
)

/** Trail attributes; every field is null when OSM does not state it and it can't be derived. */
@Serializable
data class HikingDetails(
    val lengthMeters: Double? = null,
    /** True when [lengthMeters] was measured from geometry rather than tagged. */
    val lengthDerived: Boolean = false,
    val ascentMeters: Double? = null,
    val estimatedMinutes: Int? = null,
    val surface: String? = null,
    val difficulty: String? = null,
    /** null = unknown, because a clipped trail can't be judged. */
    val roundTrip: Boolean? = null,
    val signposted: Boolean = false,
    val network: String? = null,
)
