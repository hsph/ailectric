package app.ailectric.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.ailectric.core.cache.Coverage
import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Poi
import app.ailectric.core.model.Station
import app.ailectric.core.search.SearchCache
import kotlinx.serialization.json.Json

@Entity(tableName = "station", primaryKeys = ["provider", "id"], indices = [Index("provider", "lat", "lon")])
data class StationRow(val provider: String, val id: String, val lat: Double, val lon: Double, val json: String)

@Entity(tableName = "coverage")
data class CoverageRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val provider: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,
    val fetchedAtMillis: Long,
    val truncated: Boolean,
)

@Entity(tableName = "poi_cache")
data class PoiRow(@PrimaryKey val stationId: String, val fetchedAtMillis: Long, val json: String)

@Dao
interface CacheDao {
    @Query("SELECT * FROM coverage WHERE provider = :provider ORDER BY fetchedAtMillis DESC LIMIT 50")
    suspend fun coverages(provider: String): List<CoverageRow>

    @Query(
        "SELECT * FROM station WHERE provider = :provider " +
            "AND lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east",
    )
    suspend fun stations(provider: String, south: Double, west: Double, north: Double, east: Double): List<StationRow>

    @Upsert suspend fun upsertStations(rows: List<StationRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCoverage(row: CoverageRow)

    @Query("DELETE FROM coverage WHERE fetchedAtMillis < :before") suspend fun pruneCoverage(before: Long)

    @Query("SELECT * FROM poi_cache WHERE stationId = :stationId AND fetchedAtMillis >= :notBefore")
    suspend fun pois(stationId: String, notBefore: Long): PoiRow?

    @Upsert suspend fun upsertPois(row: PoiRow)

    @Transaction
    suspend fun store(rows: List<StationRow>, coverage: CoverageRow, pruneBefore: Long) {
        upsertStations(rows)
        insertCoverage(coverage)
        pruneCoverage(pruneBefore)
    }
}

@Database(entities = [StationRow::class, CoverageRow::class, PoiRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        // Pure cache: rebuilding it on a schema change loses nothing that can't be fetched again.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cache.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

class RoomSearchCache(private val dao: CacheDao) : SearchCache {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun coverages(providerId: String) =
        dao.coverages(providerId).map { Coverage(GeoPoint(it.lat, it.lon), it.radiusMeters, it.fetchedAtMillis) }

    override suspend fun stationsIn(providerId: String, box: BoundingBox) =
        dao.stations(providerId, box.south, box.west, box.north, box.east)
            .map { json.decodeFromString<Station>(it.json) }

    override suspend fun store(providerId: String, coverage: Coverage, stations: List<Station>, truncated: Boolean) {
        dao.store(
            stations.map {
                StationRow(providerId, it.id, it.location.lat, it.location.lon, json.encodeToString(it))
            },
            CoverageRow(
                provider = providerId, lat = coverage.center.lat, lon = coverage.center.lon,
                radiusMeters = coverage.radiusMeters, fetchedAtMillis = coverage.fetchedAtMillis, truncated = truncated,
            ),
            // Stations stay as offline fallback; old coverage rows only force a refresh.
            pruneBefore = coverage.fetchedAtMillis - COVERAGE_RETENTION_MS,
        )
    }

    override suspend fun wasTruncated(providerId: String, coverage: Coverage) =
        dao.coverages(providerId).any {
            it.fetchedAtMillis == coverage.fetchedAtMillis && it.lat == coverage.center.lat &&
                it.lon == coverage.center.lon && it.truncated
        }

    override suspend fun poisFor(stationId: String, notBeforeMillis: Long): List<Poi>? =
        dao.pois(stationId, notBeforeMillis)?.let { json.decodeFromString(it.json) }

    override suspend fun storePois(stationId: String, pois: List<Poi>, nowMillis: Long) =
        dao.upsertPois(PoiRow(stationId, nowMillis, json.encodeToString(pois)))

    private companion object {
        const val COVERAGE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
