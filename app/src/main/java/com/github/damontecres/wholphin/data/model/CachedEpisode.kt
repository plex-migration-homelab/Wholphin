package com.github.damontecres.wholphin.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Cached episode metadata for a TV series.
 *
 * Stores episode information including playback state to enable
 * instant series navigation without blocking on network requests.
 *
 * Cache validity: Dynamic (15 minutes for current season, 1 hour for completed seasons)
 * Invalidation: After playback completes or state changes
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = JellyfinUser::class,
            parentColumns = ["rowId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId", "seasonId"),
        Index("userId", "episodeId", unique = true)
    ]
)
data class CachedEpisode(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val userId: Int,
    val seriesId: UUID,
    val seasonId: UUID,
    val episodeId: UUID,
    val name: String?,
    val overview: String?,
    /** Episode number within season */
    val indexNumber: Int?,
    /** Duration in ticks (10,000 ticks = 1ms) */
    val runTimeTicks: Long?,
    /** Air date as ISO string */
    val premiereDate: String?,
    val imageUrl: String?,
    /** Current playback position in ticks */
    val playbackPositionTicks: Long = 0,
    /** Whether episode is marked as watched */
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
