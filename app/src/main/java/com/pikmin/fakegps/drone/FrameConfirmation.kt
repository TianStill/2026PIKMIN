package com.pikmin.fakegps.drone

import com.pikmin.fakegps.cv.DetectedMushroom
import com.pikmin.fakegps.cv.MushroomType
import kotlin.math.hypot
import kotlin.math.max

/** Three distinct positive frames must agree; only water animation may tolerate one missed frame. */
class FrameConfirmation {
    val hasPendingCandidate: Boolean get() = tracks.isNotEmpty()
    private var timestamp = -1L
    private data class Track(val candidate: DetectedMushroom, val hits: Int, val misses: Int)
    private var tracks = emptyList<Track>()
    fun observe(frameTime: Long, detections: List<DetectedMushroom>): DetectedMushroom? {
        if (frameTime <= timestamp) return null
        if (timestamp >= 0 && frameTime - timestamp > 1000) tracks = emptyList()
        timestamp = frameTime
        val previous = tracks.toMutableList()
        val updated = detections.map { candidate ->
            val match = previous.indexOfFirst { (prior, _, _) ->
                prior.type == candidate.type && hypot((prior.x - candidate.x).toDouble(),
                    (prior.y - candidate.y).toDouble()) <= max(24.0, max(prior.radius, candidate.radius) * 0.9)
            }
            val prior = if (match >= 0) previous.removeAt(match) else null
            Track(candidate, (prior?.hits ?: 0) + 1, 0)
        }
        // Water effects pulse while the map is loading. Keep a water candidate across one missing
        // frame, but require poison and every other type to remain present for three consecutive frames.
        tracks = updated + previous.filter {
            it.candidate.type == MushroomType.LARGE_WATER && it.misses == 0
        }.map { it.copy(misses = 1) }
        return tracks.firstOrNull { it.hits >= 3 }?.candidate
    }
}
