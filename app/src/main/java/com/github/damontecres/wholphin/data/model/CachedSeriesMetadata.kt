package com.github.damontecres.wholphin.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Cached series-level metadata from Jellyfin.
 *
 * Stores basic series information (name, images, season count) to enable
 * quick display of series headers without API calls.
 *
 * Part of the series caching system. See: SeriesCacheService
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
    indices = [Index("userId", "seriesId", unique = true)]
)
data class CachedSeriesMetadata(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    /** Foreign key to JellyfinUser */
    val userId: Int,
    /** Series UUID from Jellyfin */
    val seriesId: UUID,
    val name: String?,
    val overview: String?,
    /** Primary image URL */
    val imageUrl: String?,
    /** Backdrop/banner image URL */
    val backdropUrl: String?,
    /** Total number of seasons */
    val seasonCount: Int,
    /** Timestamp for cache freshness check */
    val lastUpdated: Long = System.currentTimeMillis()
)
