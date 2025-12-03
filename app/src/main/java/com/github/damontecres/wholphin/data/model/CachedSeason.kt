package com.github.damontecres.wholphin.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Cached season metadata for a TV series.
 *
 * Stores season information (name, number, episode count) to enable
 * immediate rendering of season tabs without waiting for API.
 *
 * Cache validity: 24 hours (season structure rarely changes)
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
        Index("userId", "seriesId"),
        Index("userId", "seasonId", unique = true)
    ]
)
data class CachedSeason(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val userId: Int,
    val seriesId: UUID,
    val seasonId: UUID,
    val name: String?,
    /** Season number (1, 2, 3...) or null for specials */
    val indexNumber: Int?,
    val imageUrl: String?,
    val episodeCount: Int?,
    val lastUpdated: Long = System.currentTimeMillis()
)
