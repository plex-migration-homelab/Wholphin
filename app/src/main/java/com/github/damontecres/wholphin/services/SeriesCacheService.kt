package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.SeriesCacheDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.CachedEpisode
import com.github.damontecres.wholphin.data.model.CachedSeason
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.ui.SlimItemFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import com.github.damontecres.wholphin.data.model.BaseItem
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages targeted caching for TV series metadata.
 *
 * IMPORTANT: This is NOT a full library sync. We only cache:
 * - Series the user navigates to
 * - Series with episodes in Next Up / Continue Watching
 * - Series the user explicitly focuses on
 *
 * Cache is bounded by time (stale entries expire after 7 days), not by syncing everything.
 *
 * ## Cache TTL Strategy
 *
 * Different seasons have different cache lifetimes:
 *
 * | Season | TTL | Why |
 * |--------|-----|-----|
 * | Latest/current | 15 min | New episodes may be added weekly |
 * | Older/completed | 1 hour | Content is finalized |
 *
 * This balances freshness (seeing new episodes quickly) with efficiency
 * (not re-fetching completed seasons constantly).
 */
@Singleton
class SeriesCacheService @Inject constructor(
    private val api: ApiClient,
    private val seriesCacheDao: SeriesCacheDao,
    private val serverRepository: ServerRepository,
    @IoCoroutineScope private val scope: CoroutineScope,
) {
    companion object {
        private const val SEASONS_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24 hours
        private const val EPISODES_CACHE_MAX_AGE_MS = 60 * 60 * 1000L      // 1 hour
        /**
         * Current/latest season cache validity.
         * Shorter because new episodes may be added weekly.
         */
        private const val CURRENT_SEASON_CACHE_MAX_AGE_MS = 15 * 60 * 1000L    // 15 minutes
        private const val STALE_CACHE_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
        private const val PREFETCH_DELAY_MS = 300L
        private const val MAX_PREFETCH_QUEUE_SIZE = 20
        private const val INTER_SEASON_DELAY_MS = 50L

        // Fields for seasons
        private val SeasonFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.CHILD_COUNT,
            ItemFields.SEASON_USER_DATA
        )

        // Fields for episodes - must match SeriesViewModel requirements
        private val EpisodeFields = listOf(
            ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.OVERVIEW,
            ItemFields.CUSTOM_RATING,
            ItemFields.TRICKPLAY,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO
        )
    }

    private val userId: Int
        get() = serverRepository.currentUser.value?.rowId ?: -1

    private val prefetchingSeriesIds = ConcurrentHashMap.newKeySet<UUID>()
    private val prefetchQueue = Channel<PrefetchRequest>(MAX_PREFETCH_QUEUE_SIZE, BufferOverflow.DROP_OLDEST)

    /**
     * Determine the appropriate cache max age for a season.
     *
     * Current/latest season uses shorter TTL since new episodes may appear.
     * Completed seasons use longer TTL since they won't change.
     *
     * @param seasonIndexNumber The season's index (1, 2, 3...)
     * @param maxSeasonIndex The highest season index for this series
     * @return Cache max age in milliseconds
     */
    private fun getEpisodeCacheMaxAge(seasonIndexNumber: Int?, maxSeasonIndex: Int?): Long {
        // If we can't determine, use shorter TTL to be safe
        if (seasonIndexNumber == null || maxSeasonIndex == null) {
            return CURRENT_SEASON_CACHE_MAX_AGE_MS
        }

        val isCurrentSeason = seasonIndexNumber >= maxSeasonIndex
        return if (isCurrentSeason) CURRENT_SEASON_CACHE_MAX_AGE_MS else EPISODES_CACHE_MAX_AGE_MS
    }

    init {
        scope.launch {
            for (request in prefetchQueue) {
                if (prefetchingSeriesIds.add(request.seriesId)) {
                    try {
                        prefetchSeriesInternal(request.seriesId, request.prioritySeasonId)
                    } catch (e: Exception) {
                        Timber.w(e, "Prefetch failed for ${request.seriesId}")
                    } finally {
                        prefetchingSeriesIds.remove(request.seriesId)
                    }
                    delay(PREFETCH_DELAY_MS)
                }
            }
        }
    }

    fun getSeasonsFlow(seriesId: UUID, forceRefresh: Boolean = false): Flow<List<CachedSeason>> = flow {
        val cached = seriesCacheDao.getSeasonsForSeries(userId, seriesId)
        val lastUpdated = seriesCacheDao.getSeasonsLastUpdated(userId, seriesId) ?: 0
        val isFresh = (System.currentTimeMillis() - lastUpdated) < SEASONS_CACHE_MAX_AGE_MS

        if (cached.isNotEmpty() && isFresh && !forceRefresh) {
            emit(cached)
            return@flow
        }

        if (cached.isNotEmpty()) {
            emit(cached)
        }

        try {
            val freshSeasons = fetchSeasonsFromApi(seriesId)
            emit(freshSeasons)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch seasons for $seriesId")
            if (cached.isEmpty()) throw e
        }
    }.flowOn(Dispatchers.IO)

    fun getEpisodesFlow(
        seriesId: UUID,
        seasonId: UUID,
        seasonIndexNumber: Int? = null,
        maxSeasonIndex: Int? = null,
        forceRefresh: Boolean = false
    ): Flow<List<CachedEpisode>> = flow {
        val cached = seriesCacheDao.getEpisodesForSeason(userId, seasonId)
        val lastUpdated = seriesCacheDao.getEpisodesLastUpdated(userId, seasonId) ?: 0

        // Use appropriate TTL based on whether this is the current season
        val maxAge = getEpisodeCacheMaxAge(seasonIndexNumber, maxSeasonIndex)
        val isFresh = (System.currentTimeMillis() - lastUpdated) < maxAge

        if (cached.isNotEmpty() && isFresh && !forceRefresh) {
            emit(cached)
            return@flow
        }

        if (cached.isNotEmpty()) {
            emit(cached)
        }

        try {
            val freshEpisodes = fetchEpisodesFromApi(seriesId, seasonId)
            emit(freshEpisodes)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch episodes for season $seasonId")
            if (cached.isEmpty()) throw e
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getEpisodesCached(
        seriesId: UUID,
        seasonId: UUID,
        seasonIndexNumber: Int? = null,
        maxSeasonIndex: Int? = null
    ): List<CachedEpisode>? {
        val cached = seriesCacheDao.getEpisodesForSeason(userId, seasonId)
        val lastUpdated = seriesCacheDao.getEpisodesLastUpdated(userId, seasonId) ?: 0

        val maxAge = getEpisodeCacheMaxAge(seasonIndexNumber, maxSeasonIndex)
        val isFresh = (System.currentTimeMillis() - lastUpdated) < maxAge
        return if (cached.isNotEmpty() && isFresh) cached else null
    }

    suspend fun isSeriesCached(seriesId: UUID): Boolean {
        val lastUpdated = seriesCacheDao.getSeasonsLastUpdated(userId, seriesId) ?: return false
        return (System.currentTimeMillis() - lastUpdated) < SEASONS_CACHE_MAX_AGE_MS
    }

    /**
     * Intentionally fire-and-forget. The launched coroutine is lightweight
     * (cache check + channel send) and self-limiting via the bounded queue.
     */
    fun queuePrefetch(seriesId: UUID, prioritySeasonId: UUID? = null) {
        scope.launch {
            if (prefetchingSeriesIds.contains(seriesId)) {
                Timber.v("Series $seriesId already prefetching, skipping")
                return@launch
            }

            if (isSeriesCached(seriesId)) {
                Timber.v("Series $seriesId already cached, skipping prefetch")
                return@launch
            }

            val sent = prefetchQueue.trySend(PrefetchRequest(seriesId, prioritySeasonId)).isSuccess
            if (sent) {
                Timber.d("Queued prefetch for series $seriesId (priority season: $prioritySeasonId)")
            }
        }
    }

    fun prefetchForVisibleEpisodes(episodes: List<BaseItem>) {
        val seriesEpisodes = episodes
            .filter { it.type == BaseItemKind.EPISODE }
            .mapNotNull { episode ->
                episode.data.seriesId?.let { seriesId ->
                    seriesId to episode.data.seasonId
                }
            }
            .distinctBy { it.first }

        seriesEpisodes.forEach { (seriesId, seasonId) ->
            queuePrefetch(seriesId, seasonId)
        }

        if (seriesEpisodes.isNotEmpty()) {
            Timber.d("Queued prefetch for ${seriesEpisodes.size} series from visible episodes")
        }
    }

    fun prefetchOnFocus(seriesId: UUID, seasonId: UUID?) {
        if (prefetchingSeriesIds.contains(seriesId)) return

        scope.launch {
            if (isSeriesCached(seriesId)) return@launch

            if (prefetchingSeriesIds.add(seriesId)) {
                try {
                    Timber.d("Priority prefetch (focus) for series $seriesId")
                    prefetchSeriesInternal(seriesId, seasonId)
                } finally {
                    prefetchingSeriesIds.remove(seriesId)
                }
            }
        }
    }

    suspend fun invalidateSeriesCache(seriesId: UUID) {
        withContext(Dispatchers.IO) {
            seriesCacheDao.invalidateSeriesEpisodes(userId, seriesId)
            Timber.d("Invalidated episode cache for series $seriesId")
        }
    }

    suspend fun updateEpisodePlaybackState(episodeId: UUID, positionTicks: Long, played: Boolean) {
        withContext(Dispatchers.IO) {
            seriesCacheDao.updatePlaybackState(userId, episodeId, positionTicks, played)
        }
    }

    suspend fun cleanupStaleCache() {
        withContext(Dispatchers.IO) {
            val threshold = System.currentTimeMillis() - STALE_CACHE_THRESHOLD_MS
            seriesCacheDao.deleteStaleEpisodes(threshold)
            seriesCacheDao.deleteStaleSeasons(threshold)
            seriesCacheDao.deleteStaleMetadata(threshold)

            val remaining = seriesCacheDao.getCachedSeriesCount(userId)
            Timber.d("Cache cleanup complete. $remaining series remain cached.")
        }
    }

    private suspend fun prefetchSeriesInternal(seriesId: UUID, prioritySeasonId: UUID?) {
        val startTime = System.currentTimeMillis()

        val seasons = fetchSeasonsFromApi(seriesId)

        if (prioritySeasonId != null) {
            fetchEpisodesFromApi(seriesId, prioritySeasonId)
        }

        seasons
            .filter { it.seasonId != prioritySeasonId }
            .forEach { season ->
                fetchEpisodesFromApi(seriesId, season.seasonId)
                delay(INTER_SEASON_DELAY_MS)
            }

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("Prefetched series $seriesId (${seasons.size} seasons) in ${elapsed}ms")
    }

    private suspend fun fetchSeasonsFromApi(seriesId: UUID): List<CachedSeason> {
        val response = api.tvShowsApi.getSeasons(seriesId, fields = SeasonFields)
        val seasons = response.content.items.map { season ->
            CachedSeason(
                userId = userId,
                seriesId = seriesId,
                seasonId = season.id,
                name = season.name,
                indexNumber = season.indexNumber,
                imageUrl = api.imageApi.getItemImageUrl(season.id, ImageType.PRIMARY),
                episodeCount = season.childCount,
                lastUpdated = System.currentTimeMillis()
            )
        }
        seriesCacheDao.insertSeasons(seasons)
        return seasons
    }

    private suspend fun fetchEpisodesFromApi(seriesId: UUID, seasonId: UUID): List<CachedEpisode> {
        val response = api.tvShowsApi.getEpisodes(
            seriesId = seriesId,
            seasonId = seasonId,
            fields = EpisodeFields
        )
        val episodes = response.content.items.map { episode ->
            CachedEpisode(
                userId = userId,
                seriesId = seriesId,
                seasonId = seasonId,
                episodeId = episode.id,
                name = episode.name,
                overview = episode.overview,
                indexNumber = episode.indexNumber,
                runTimeTicks = episode.runTimeTicks,
                premiereDate = episode.premiereDate?.toString(),
                imageUrl = api.imageApi.getItemImageUrl(episode.id, ImageType.PRIMARY),
                playbackPositionTicks = episode.userData?.playbackPositionTicks ?: 0,
                played = episode.userData?.played ?: false,
                isFavorite = episode.userData?.isFavorite ?: false,
                lastUpdated = System.currentTimeMillis()
            )
        }
        seriesCacheDao.insertEpisodes(episodes)
        return episodes
    }

    private data class PrefetchRequest(
        val seriesId: UUID,
        val prioritySeasonId: UUID?
    )
}
