package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.damontecres.wholphin.data.model.CachedEpisode
import com.github.damontecres.wholphin.data.model.CachedSeason
import com.github.damontecres.wholphin.data.model.CachedSeriesMetadata
import java.util.UUID

/**
 * Data Access Object for series cache operations.
 *
 * Provides CRUD operations for cached series, season, and episode metadata.
 * All queries are scoped to a specific userId for per-user isolation.
 *
 * Cache freshness is determined by callers comparing lastUpdated against thresholds.
 */
@Dao
interface SeriesCacheDao {
    // ─────────────────────────────────────────────────────────────────────
    // Series metadata
    // ─────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM CachedSeriesMetadata WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun getSeriesMetadata(userId: Int, seriesId: UUID): CachedSeriesMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesMetadata(metadata: CachedSeriesMetadata)

    // ─────────────────────────────────────────────────────────────────────
    // Seasons
    // ─────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM CachedSeason WHERE userId = :userId AND seriesId = :seriesId ORDER BY indexNumber ASC")
    suspend fun getSeasonsForSeries(userId: Int, seriesId: UUID): List<CachedSeason>

    /**
     * Get the cache timestamp for seasons. Returns null if not cached.
     * Used to determine if cache is fresh enough to use.
     */
    @Query("SELECT lastUpdated FROM CachedSeason WHERE userId = :userId AND seriesId = :seriesId LIMIT 1")
    suspend fun getSeasonsLastUpdated(userId: Int, seriesId: UUID): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeasons(seasons: List<CachedSeason>)

    // ─────────────────────────────────────────────────────────────────────
    // Episodes
    // ─────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM CachedEpisode WHERE userId = :userId AND seasonId = :seasonId ORDER BY indexNumber ASC")
    suspend fun getEpisodesForSeason(userId: Int, seasonId: UUID): List<CachedEpisode>

    @Query("SELECT lastUpdated FROM CachedEpisode WHERE userId = :userId AND seasonId = :seasonId LIMIT 1")
    suspend fun getEpisodesLastUpdated(userId: Int, seasonId: UUID): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<CachedEpisode>)

    // ─────────────────────────────────────────────────────────────────────
    // Cache status checks (for prefetch decisions)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Quick check if series has ANY cached seasons.
     * Used by prefetch to skip already-cached series.
     */
    @Query("SELECT COUNT(*) > 0 FROM CachedSeason WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun isSeriesCached(userId: Int, seriesId: UUID): Boolean

    // ─────────────────────────────────────────────────────────────────────
    // Incremental updates (avoid full refresh for playback changes)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Update playback state without fetching all episode data.
     * Called after playback position changes.
     */
    @Query("UPDATE CachedEpisode SET playbackPositionTicks = :position, played = :played WHERE userId = :userId AND episodeId = :episodeId")
    suspend fun updatePlaybackState(userId: Int, episodeId: UUID, position: Long, played: Boolean)

    // ─────────────────────────────────────────────────────────────────────
    // Invalidation (targeted cache clearing)
    // ─────────────────────────────────────────────────────────────────────

    @Query("DELETE FROM CachedEpisode WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun invalidateSeriesEpisodes(userId: Int, seriesId: UUID)

    @Query("DELETE FROM CachedSeason WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun invalidateSeriesSeasons(userId: Int, seriesId: UUID)

    @Query("DELETE FROM CachedSeriesMetadata WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun invalidateSeriesMetadata(userId: Int, seriesId: UUID)

    // ─────────────────────────────────────────────────────────────────────
    // Cleanup (remove stale entries to bound cache size)
    // ─────────────────────────────────────────────────────────────────────

    /** Remove episodes not updated since threshold timestamp */
    @Query("DELETE FROM CachedEpisode WHERE lastUpdated < :threshold")
    suspend fun deleteStaleEpisodes(threshold: Long)

    @Query("DELETE FROM CachedSeason WHERE lastUpdated < :threshold")
    suspend fun deleteStaleSeasons(threshold: Long)

    @Query("DELETE FROM CachedSeriesMetadata WHERE lastUpdated < :threshold")
    suspend fun deleteStaleMetadata(threshold: Long)

    // ─────────────────────────────────────────────────────────────────────
    // Debug/stats
    // ─────────────────────────────────────────────────────────────────────

    /** Count of unique series in cache. Useful for debugging cache behavior. */
    @Query("SELECT COUNT(DISTINCT seriesId) FROM CachedSeason WHERE userId = :userId")
    suspend fun getCachedSeriesCount(userId: Int): Int
}
