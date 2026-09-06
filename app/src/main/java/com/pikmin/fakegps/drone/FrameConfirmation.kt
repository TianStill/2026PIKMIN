package com.pikmin.fakegps.drone

import com.pikmin.fakegps.cv.DetectedMushroom
import kotlin.math.hypot
import kotlin.math.max

/** Three distinct, consecutive frames must agree; repeated/stale frames never count. */
class FrameConfirmation {
    private var timestamp = -1L
    private var tracks = emptyList<Pair<DetectedMushroom, Int>>()
    fun observe(frameTime: Long, detections: List<DetectedMushroom>): DetectedMushroom? {
        if (frameTime <= timestamp) return null
        if (timestamp >= 0 && frameTime - timestamp > 1000) tracks = emptyList()
        timestamp = frameTime
        val previous = tracks.toMutableList()
        tracks = detections.map { candidate ->
            val match = previous.indexOfFirst { (prior, _) ->
                prior.type == candidate.type && hypot((prior.x - candidate.x).toDouble(),
                    (prior.y - candidate.y).toDouble()) <= max(24.0, candidate.radius * 0.75)
            }
            candidate to if (match >= 0) previous.removeAt(match).second + 1 else 1
        }
        return tracks.firstOrNull { it.second >= 3 }?.first
    }
}
