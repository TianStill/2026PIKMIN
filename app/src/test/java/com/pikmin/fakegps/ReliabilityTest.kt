package com.pikmin.fakegps

import com.pikmin.fakegps.cv.*
import com.pikmin.fakegps.drone.*
import com.pikmin.fakegps.service.SessionGate
import com.pikmin.fakegps.update.*
import com.pikmin.fakegps.utils.GeoUtils
import com.pikmin.fakegps.data.model.DiscoveredMushroomPoint
import com.pikmin.fakegps.data.model.LocationPoint
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    private fun candidate(x: Int = 100, type: MushroomType = MushroomType.LARGE_RED) =
        DetectedMushroom(type, x, 200, 40, 0.8f)

    @Test fun cruiseDefaultsToLargeElementMushroomsOnly() {
        assertEquals(MushroomType.ELEMENT_TARGETS, MushroomType.DEFAULT_CRUISE_TARGETS)
        assertTrue(MushroomType.DEFAULT_CRUISE_TARGETS.all { it.category == MushroomCategory.LARGE_ELEMENT })
        assertFalse(MushroomType.DEFAULT_CRUISE_TARGETS.contains(MushroomType.LARGE_RED))
    }
    @Test fun largeElementLabelsClaimCalibratedSize() {
        val label = MushroomType.getDisplayName(MushroomType.LARGE_WATER)
        assertTrue(label.contains("大水蘑菇"))
        assertFalse(label.contains("大小待確認"))
    }

    @Test fun calibratedElementShapeThresholdsSeparateKnownPairs() {
        assertTrue(MushroomDetector.isLargeElementShape(MushroomType.LARGE_ELECTRIC, 34, 33, 421))
        assertFalse(MushroomDetector.isLargeElementShape(MushroomType.LARGE_ELECTRIC, 37, 22, 521))
        assertTrue(MushroomDetector.isLargeElementShape(MushroomType.LARGE_FIRE, 47, 32, 797))
        assertFalse(MushroomDetector.isLargeElementShape(MushroomType.LARGE_FIRE, 38, 23, 454))
        assertTrue(MushroomDetector.isLargeElementShape(MushroomType.LARGE_WATER, 42, 28, 538))
        assertFalse(MushroomDetector.isLargeElementShape(MushroomType.LARGE_WATER, 23, 51, 313))
        assertTrue(MushroomDetector.isLargeElementShape(MushroomType.LARGE_CRYSTAL, 42, 40, 513))
        assertFalse(MushroomDetector.isLargeElementShape(MushroomType.LARGE_CRYSTAL, 28, 28, 257))
        assertTrue(MushroomDetector.isLargeElementShape(MushroomType.LARGE_POISON, 18, 35, 219))
    }

    @Test fun stopInvalidatesQueuedStart() {
        val gate = SessionGate(); val token = gate.begin(); gate.stop()
        assertFalse(gate.accepts(token))
    }
    @Test fun newStartDoesNotAcceptPreviousSession() {
        val gate = SessionGate(); val first = gate.begin(); gate.stop(); val second = gate.begin()
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second))
    }
    @Test fun threeDistinctFramesConfirmTarget() {
        val tracker = FrameConfirmation()
        assertNull(tracker.observe(100, listOf(candidate())))
        assertNull(tracker.observe(350, listOf(candidate(105))))
        assertNotNull(tracker.observe(600, listOf(candidate(110))))
    }
    @Test fun repeatedFrameNeverConfirms() {
        val tracker = FrameConfirmation()
        repeat(10) { assertNull(tracker.observe(100, listOf(candidate()))) }
    }
    @Test fun candidateAtDeadlineRemainsPendingUntilConfirmedOrMissed() {
        val tracker = FrameConfirmation()
        assertFalse(tracker.hasPendingCandidate)
        assertNull(tracker.observe(2800, listOf(candidate())))
        assertTrue(tracker.hasPendingCandidate)
        assertNull(tracker.observe(3050, listOf(candidate())))
        assertNotNull(tracker.observe(3300, listOf(candidate())))
        tracker.observe(3550, emptyList())
        assertFalse(tracker.hasPendingCandidate)
    }
    @Test fun missedFrameResetsConfirmation() {
        val tracker = FrameConfirmation()
        tracker.observe(100, listOf(candidate())); tracker.observe(350, emptyList())
        assertNull(tracker.observe(600, listOf(candidate())))
        assertNull(tracker.observe(850, listOf(candidate())))
    }
    @Test fun typeChangeDoesNotConfirmOldTarget() {
        val tracker = FrameConfirmation()
        tracker.observe(100, listOf(candidate())); tracker.observe(350, listOf(candidate()))
        assertNull(tracker.observe(600, listOf(candidate(type = MushroomType.LARGE_BLUE))))
    }
    @Test fun longGapResetsConfirmation() {
        val tracker = FrameConfirmation()
        tracker.observe(100, listOf(candidate())); tracker.observe(350, listOf(candidate()))
        assertNull(tracker.observe(2000, listOf(candidate())))
    }
    @Test fun distantCandidateDoesNotInheritTrack() {
        val tracker = FrameConfirmation()
        tracker.observe(100, listOf(candidate())); tracker.observe(350, listOf(candidate()))
        assertNull(tracker.observe(600, listOf(candidate(500))))
    }
    @Test fun previouslyFoundSameTypeNearbyIsSuppressed() {
        val known = listOf(DiscoveredMushroomPoint(MushroomType.LARGE_RED.name, 25.0, 121.0))
        val nearby = LocationPoint(25.0005, 121.0)
        assertTrue(DroneScannerManager.wasPreviouslyFound(MushroomType.LARGE_RED, nearby, known))
    }
    @Test fun differentTypeOrDistantCandidateIsNotSuppressed() {
        val known = listOf(DiscoveredMushroomPoint(MushroomType.LARGE_RED.name, 25.0, 121.0))
        assertFalse(DroneScannerManager.wasPreviouslyFound(MushroomType.LARGE_BLUE, LocationPoint(25.0005, 121.0), known))
        assertFalse(DroneScannerManager.wasPreviouslyFound(MushroomType.LARGE_RED, LocationPoint(25.003, 121.0), known))
    }
    @Test fun yellowStemBelowRedCapIsNotReportedAsYellowMushroom() {
        val redCap = DetectedMushroom(MushroomType.LARGE_RED, 300, 300, 60, 0.8f)
        val yellowStem = DetectedMushroom(MushroomType.LARGE_YELLOW, 305, 360, 50, 0.9f)
        assertEquals(listOf(redCap), MushroomDetector.removeKnownStemFalsePositives(listOf(redCap, yellowStem)))
    }
    @Test fun standaloneYellowCandidateRemainsDetectable() {
        val yellow = DetectedMushroom(MushroomType.LARGE_YELLOW, 300, 300, 50, 0.9f)
        val distantRed = DetectedMushroom(MushroomType.LARGE_RED, 500, 250, 40, 0.8f)
        assertEquals(listOf(yellow, distantRed), MushroomDetector.removeKnownStemFalsePositives(listOf(yellow, distantRed)))
    }
    @Test fun unreliableTopAndSideEdgesAreExcludedFromDetection() {
        assertFalse(MushroomDetector.isWithinReliableDetectionArea(970, 200, 1000, 2000))
        assertFalse(MushroomDetector.isWithinReliableDetectionArea(500, 100, 1000, 2000))
        assertTrue(MushroomDetector.isWithinReliableDetectionArea(500, 1000, 1000, 2000))
    }
    @Test fun clippedWaterRegionTouchingDetectionBoundaryIsRejected() {
        assertTrue(MushroomDetector.touchesDetectionBoundary(800, 950, 300, 800, 1000, 2000))
        assertTrue(MushroomDetector.touchesDetectionBoundary(200, 600, 240, 500, 1000, 2000))
        assertFalse(MushroomDetector.touchesDetectionBoundary(200, 600, 300, 800, 1000, 2000))
    }
    @Test fun semverReleaseBeatsPrerelease() { assertTrue(VersionOrder.compare("1.2.3", "1.2.3-rc.10")!! > 0) }
    @Test fun numericPrereleaseOrdering() { assertTrue(VersionOrder.compare("1.2.3-rc.10", "1.2.3-rc.2")!! > 0) }
    @Test fun buildMetadataIgnored() { assertEquals(0, VersionOrder.compare("v1.2.3+10", "1.2.3+20")) }
    @Test fun invalidVersionFailsClosed() { assertNull(VersionOrder.compare("latest", "1.2.3")) }
    @Test fun abbreviatedVersionSupported() { assertEquals(0, VersionOrder.compare("1.2", "1.2.0")) }
    @Test(expected = IllegalArgumentException::class) fun truncatedDownloadRejected() { DownloadIntegrity.verify(9, 10, "a".repeat(64), null) }
    @Test(expected = IllegalArgumentException::class) fun alteredDownloadRejected() { DownloadIntegrity.verify(10, 10, "a".repeat(64), "b".repeat(64)) }
    @Test fun matchingDownloadAccepted() { DownloadIntegrity.verify(10, 10, "a".repeat(64), "A".repeat(64)) }
    @Test(expected = IllegalArgumentException::class) fun emptyDownloadRejected() { DownloadIntegrity.verify(0, 0, "", null) }
    @Test fun spiralStaysInsideRadiusAcrossDateLine() {
        val points = DronePathGenerator.generateSpiralWaypoints(25.0, 179.999, 1.5, 300.0)
        assertTrue(points.size > 10)
        assertTrue(points.all { it.longitude in -180.0..180.0 && GeoUtils.calculateDistanceMeters(25.0, 179.999, it.latitude, it.longitude) <= 1500.1 })
    }
    @Test(expected = IllegalArgumentException::class) fun zeroStepRejected() { DronePathGenerator.generateSpiralWaypoints(25.0, 121.0, 1.5, 0.0) }
    @Test(expected = IllegalArgumentException::class) fun polarRouteRejected() { DronePathGenerator.generateSpiralWaypoints(90.0, 0.0, 1.5) }
    @Test fun multilineCoordinatesPreserveNote() {
        val point = GeoUtils.extractCoordinatesFromText("一般活動菇 免4\n52.408297,16.93426666")!!
        assertEquals(52.408297, point.latitude, 0.0000001); assertTrue(point.note.contains("免4"))
    }
    @Test fun jitterRemainsWithinConfiguredRadius() {
        repeat(100) {
            val (lat, lng) = GeoUtils.applyRealisticJitter(25.0, 121.0, 0.4)
            assertTrue(GeoUtils.calculateDistanceMeters(25.0, 121.0, lat, lng) <= 0.401)
        }
    }
}
