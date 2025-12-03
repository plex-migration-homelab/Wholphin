package com.github.damontecres.wholphin.ui.detail.series

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.ItemPlaybackRepository
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CachedEpisode
import com.github.damontecres.wholphin.data.model.CachedSeason
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.preferences.ThemeSongVolume
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ExtrasService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PeopleFavorites
import com.github.damontecres.wholphin.services.SeriesCacheService
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.detail.ItemViewModel
import com.github.damontecres.wholphin.ui.equalsNotNull
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setValueOnMain
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetEpisodesRequestHandler
import com.github.damontecres.wholphin.util.LoadingExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemUserData
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import java.util.function.Predicate
import javax.inject.Inject

@HiltViewModel
class SeriesViewModel
    @Inject
    constructor(
        api: ApiClient,
        @param:ApplicationContext val context: Context,
        val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
        private val itemPlaybackRepository: ItemPlaybackRepository,
        private val themeSongPlayer: ThemeSongPlayer,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val peopleFavorites: PeopleFavorites,
        private val trailerService: TrailerService,
        private val extrasService: ExtrasService,
        private val seriesCacheService: SeriesCacheService,
    ) : ItemViewModel(api) {
        private lateinit var seriesId: UUID
        private lateinit var prefs: UserPreferences
        val loading = MutableLiveData<LoadingState>(LoadingState.Loading)
        val seasons = MutableLiveData<List<BaseItem>>(listOf())
        val episodes = MutableLiveData<EpisodeList>(EpisodeList.Loading)

        val trailers = MutableLiveData<List<Trailer>>(listOf())
        val extras = MutableLiveData<List<ExtrasItem>>(listOf())
        val people = MutableLiveData<List<Person>>(listOf())
        val similar = MutableLiveData<List<BaseItem>>()

        private var episodesJob: Job? = null
        private var seasonsJob: Job? = null
        private var currentSeasonId: UUID? = null

        fun init(
            prefs: UserPreferences,
            itemId: UUID,
            seasonEpisodeIds: SeasonEpisodeIds?,
            loadAdditionalDetails: Boolean,
        ) {
            this.seriesId = itemId
            this.prefs = prefs
            viewModelScope.launch(
                LoadingExceptionHandler(
                    loading,
                    "Error loading series $seriesId",
                ) + Dispatchers.IO,
            ) {
                val item = fetchItem(seriesId)

                // Load additional details in parallel if requested
                if (loadAdditionalDetails) {
                    loadDetails(item, itemId)
                }

                // Progressive loading of seasons
                seriesCacheService.getSeasonsFlow(seriesId).collect { cachedSeasons ->
                    val seasonsList = cachedSeasons.map { it.toBaseItem(api) }

                    withContext(Dispatchers.Main) {
                        this@SeriesViewModel.seasons.value = seasonsList
                        if (loading.value != LoadingState.Success && seasonsList.isNotEmpty()) {
                            loading.value = LoadingState.Success
                        }
                    }

                    // Determine initial season and load episodes if needed
                    val currentEps = episodes.value
                    if (currentEps is EpisodeList.Loading || currentEps is EpisodeList.Error) {
                        val initialSeason =
                            if (seasonEpisodeIds != null) {
                                seasonsList.firstOrNull {
                                    equalsNotNull(it.id, seasonEpisodeIds.seasonId) ||
                                        equalsNotNull(it.indexNumber, seasonEpisodeIds.seasonNumber)
                                }
                            } else {
                                seasonsList.firstOrNull()
                            }

                        initialSeason?.let {
                            loadEpisodes(
                                it.id,
                                seasonEpisodeIds?.episodeId,
                                seasonEpisodeIds?.episodeNumber
                            )
                        } ?: run {
                            if (seasonsList.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    episodes.value = EpisodeList.Error("Could not determine season for selected episode")
                                }
                            }
                        }
                    } else if (seasonsList.isNotEmpty() && loading.value != LoadingState.Success) {
                        // Ensure success state if seasons loaded
                         withContext(Dispatchers.Main) {
                             loading.value = LoadingState.Success
                         }
                    }
                }
            }
        }

        private suspend fun loadDetails(item: BaseItem, itemId: UUID) {
             viewModelScope.launchIO {
                val trailers = trailerService.getTrailers(item)
                withContext(Dispatchers.Main) {
                    this@SeriesViewModel.trailers.value = trailers
                }
            }
            viewModelScope.launchIO {
                val people = peopleFavorites.getPeopleFor(item)
                this@SeriesViewModel.people.setValueOnMain(people)
            }
            viewModelScope.launchIO {
                val extras = extrasService.getExtras(item.id)
                this@SeriesViewModel.extras.setValueOnMain(extras)
            }
            if (!similar.isInitialized) {
                viewModelScope.launchIO {
                    val similar =
                        api.libraryApi
                            .getSimilarItems(
                                GetSimilarItemsRequest(
                                    userId = serverRepository.currentUser.value?.id,
                                    itemId = itemId,
                                    fields = SlimItemFields,
                                    limit = 25,
                                ),
                            ).content.items
                            .map { BaseItem.from(it, api, true) }
                    this@SeriesViewModel.similar.setValueOnMain(similar)
                }
            }
        }

        /**
         * If the series has a theme song & app settings allow, play it
         */
        fun maybePlayThemeSong(
            seriesId: UUID,
            playThemeSongs: ThemeSongVolume,
        ) {
            viewModelScope.launchIO {
                themeSongPlayer.playThemeFor(seriesId, playThemeSongs)
                addCloseable {
                    themeSongPlayer.stop()
                }
            }
        }

        fun release() {
            themeSongPlayer.stop()
        }

        fun loadEpisodes(seasonId: UUID, episodeId: UUID? = null, episodeNumber: Int? = null) {
            currentSeasonId = seasonId
            // Cancel previous job if any to avoid race conditions
            episodesJob?.cancel()

            // Calculate season indices for TTL
            val seasonList = seasons.value ?: emptyList()
            val maxSeasonIndex = seasonList.maxOfOrNull { it.indexNumber ?: 0 }
            val currentSeason = seasonList.find { it.id == seasonId }
            val seasonIndexNumber = currentSeason?.indexNumber

            // Set loading state? No, avoid flashing.

            episodesJob = viewModelScope.launchIO(ExceptionHandler(true)) {
                seriesCacheService.getEpisodesFlow(
                    seriesId,
                    seasonId,
                    seasonIndexNumber,
                    maxSeasonIndex
                ).collectLatest { cachedEpisodes ->
                    val items = cachedEpisodes.map { it.toBaseItem(api) }
                    val cachedList = CachedList(items)

                    val initialIndex =
                        if (episodeId != null || episodeNumber != null) {
                             cachedList.indexOfBlocking {
                                equalsNotNull(it?.id, episodeId) ||
                                    equalsNotNull(it?.indexNumber, episodeNumber)
                            }.coerceAtLeast(0)
                        } else {
                            0
                        }

                    withContext(Dispatchers.Main) {
                        this@SeriesViewModel.episodes.value = EpisodeList.Success(cachedList, initialIndex)
                    }
                }
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
            listIndex: Int?,
        ) = viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            favoriteWatchManager.setWatched(itemId, played)
            listIndex?.let {
                refreshEpisode(itemId, listIndex)
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
            listIndex: Int?,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            if (listIndex != null) {
                refreshEpisode(itemId, listIndex)
            } else {
                val item = fetchItem(seriesId)
                viewModelScope.launchIO {
                    val people = peopleFavorites.getPeopleFor(item)
                    this@SeriesViewModel.people.setValueOnMain(people)
                }
            }
        }

        fun setSeasonWatched(
            seasonId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            setWatched(seasonId, played, null)
            loadSeasons()
        }

        private fun loadSeasons() {
            seasonsJob?.cancel()
            seasonsJob = viewModelScope.launchIO {
                seriesCacheService.getSeasonsFlow(seriesId, forceRefresh = true).collectLatest { cachedSeasons ->
                    val seasonsList = cachedSeasons.map { it.toBaseItem(api) }
                    withContext(Dispatchers.Main) {
                        this@SeriesViewModel.seasons.value = seasonsList
                    }
                }
            }
        }

        fun setWatchedSeries(played: Boolean) =
            viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
                favoriteWatchManager.setWatched(seriesId, played)
                loadSeasons()
            }

        fun refreshEpisode(
            itemId: UUID,
            listIndex: Int,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            val eps = episodes.value
            if (eps is EpisodeList.Success) {
                if (eps.episodes is ApiRequestPager<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (eps.episodes as ApiRequestPager<GetEpisodesRequest>).refreshItem(listIndex, itemId)
                } else if (eps.episodes is CachedList) {
                    currentSeasonId?.let { seasonId ->
                         // Force reload to update cache with new state
                         loadEpisodes(seasonId)
                    }
                }

                withContext(Dispatchers.Main) {
                    episodes.value = eps
                }
            }
        }

        /**
         * Play whichever episode is next up for series or else the first episode
         */
        fun playNextUp() {
            viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
                val result by api.tvShowsApi.getNextUp(seriesId = seriesId)
                val nextUp =
                    result.items.firstOrNull() ?: api.tvShowsApi
                        .getEpisodes(
                            seriesId,
                            limit = 1,
                        ).content.items
                        .firstOrNull()
                if (nextUp != null) {
                    withContext(Dispatchers.Main) {
                        navigateTo(Destination.Playback(nextUp.id, 0L))
                    }
                } else {
                    showToast(
                        context,
                        "Could not find an episode to play",
                        Toast.LENGTH_SHORT,
                    )
                }
            }
        }

        fun navigateTo(destination: Destination) {
            release()
            navigationManager.navigateTo(destination)
        }

        val chosenStreams = MutableLiveData<ChosenStreams?>(null)
        private var chosenStreamsJob: Job? = null

        fun lookUpChosenTracks(
            itemId: UUID,
            item: BaseItem,
        ) {
            chosenStreamsJob?.cancel()
            chosenStreamsJob =
                viewModelScope.launchIO {
                    val result = itemPlaybackRepository.getSelectedTracks(itemId, item)
                    withContext(Dispatchers.Main) {
                        chosenStreams.value = result
                    }
                }
        }

        fun savePlayVersion(
            item: BaseItem,
            sourceId: UUID,
        ) {
            viewModelScope.launchIO {
                val result = itemPlaybackRepository.savePlayVersion(item.id, sourceId)
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result)
                    }
                withContext(Dispatchers.Main) {
                    chosenStreams.value = chosen
                }
            }
        }

        fun saveTrackSelection(
            item: BaseItem,
            itemPlayback: ItemPlayback?,
            trackIndex: Int,
            type: MediaStreamType,
        ) {
            viewModelScope.launchIO {
                val result =
                    itemPlaybackRepository.saveTrackSelection(
                        item = item,
                        itemPlayback = itemPlayback,
                        trackIndex = trackIndex,
                        type = type,
                    )
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result)
                    }
                withContext(Dispatchers.Main) {
                    chosenStreams.value = chosen
                }
            }
        }

        /**
         * WARNING: Cached seasons contain display metadata only.
         * Missing fields: backdropImageTags, logoImageTags, etc.
         */
        private fun CachedSeason.toBaseItem(api: ApiClient): BaseItem {
             val dto = BaseItemDto(
                 id = seasonId,
                 name = name,
                 indexNumber = indexNumber,
                 childCount = episodeCount,
                 seriesId = seriesId,
                 type = BaseItemKind.SEASON
             )
             return BaseItem(dto, imageUrl, null, null)
        }

        /**
         * WARNING: Cached episodes contain display metadata only.
         * For playback, fetch complete episode data from API using episodeId.
         * Missing fields: mediaSources, mediaStreams, trickplay data, etc.
         */
        private fun CachedEpisode.toBaseItem(api: ApiClient): BaseItem {
             val dto = BaseItemDto(
                 id = episodeId,
                 name = name,
                 overview = overview,
                 indexNumber = indexNumber,
                 runTimeTicks = runTimeTicks,
                 premiereDate = premiereDate?.let {
                     try {
                         LocalDateTime.parse(it)
                     } catch(e: Exception) {
                         Timber.w(e, "Invalid premiere date format: %s", it)
                         null
                     }
                 },
                 userData = BaseItemUserData(playbackPositionTicks = playbackPositionTicks, played = played, isFavorite = isFavorite),
                 type = BaseItemKind.EPISODE,
                 seriesId = seriesId,
                 seasonId = seasonId
             )
             return BaseItem(dto, imageUrl, null, null)
        }

        private class CachedList(
            private val items: List<BaseItem>
        ) : AbstractList<BaseItem?>(), BlockingList<BaseItem?> {
            override val size: Int get() = items.size

            override fun get(index: Int): BaseItem? = items.getOrNull(index)

            override suspend fun getBlocking(index: Int): BaseItem? = get(index)

            override suspend fun indexOfBlocking(predicate: Predicate<BaseItem?>): Int {
                return items.indexOfFirst { predicate.test(it) }
            }
        }
    }

sealed interface EpisodeList {
    data object Loading : EpisodeList

    data class Error(
        val message: String? = null,
        val exception: Throwable? = null,
    ) : EpisodeList {
        constructor(exception: Throwable) : this(null, exception)
    }

    data class Success(
        val episodes: BlockingList<BaseItem?>,
        val initialIndex: Int,
    ) : EpisodeList
}
