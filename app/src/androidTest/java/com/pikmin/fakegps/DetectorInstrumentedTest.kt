package com.pikmin.fakegps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.pikmin.fakegps.cv.MushroomDetector
import com.pikmin.fakegps.cv.MushroomType
import com.pikmin.fakegps.drone.DroneScannerManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DetectorInstrumentedTest {
    @Test fun normalElementReferencesAreRejected() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val samples = listOf(
            "normal-electric.jpg" to MushroomType.LARGE_ELECTRIC,
            "normal-poison.jpg" to MushroomType.LARGE_POISON,
            "normal-fire.jpg" to MushroomType.LARGE_FIRE,
            "normal-water.png" to MushroomType.LARGE_WATER,
            "normal-crystal.jpg" to MushroomType.LARGE_CRYSTAL
        )
        for ((filename, expected) in samples) {
            val bitmap = assets.open("element-samples/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(expected))
                assertTrue("$filename incorrectly detected as large $expected: $predictions", predictions.isEmpty())
            } finally { bitmap.recycle() }
        }
    }

    @Test fun reproducedTerrainWaterFalsePositiveIsRejected() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("false-positive-samples/terrain-water-20260908.jpg")
            .use { BitmapFactory.decodeStream(it) } ?: error("Cannot decode reproduced terrain sample")
        try {
            val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_WATER))
            assertTrue("Terrain incorrectly detected as large water mushroom: $predictions", predictions.isEmpty())
        } finally { bitmap.recycle() }
    }

    @Test fun emptyLakeScreenIsNotPoisonMushroom() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("false-positive-samples/empty-lake-poison-20260908.jpg")
            .use { BitmapFactory.decodeStream(it) } ?: error("Cannot decode empty lake sample")
        try {
            val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_POISON))
            assertTrue("Empty lake screen incorrectly detected as large poison mushroom: $predictions", predictions.isEmpty())
        } finally { bitmap.recycle() }
    }

    @Test fun largeElectricCruiseScreensAreDetected() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (filename in listOf("large-electric-cruise-1.jpg", "large-electric-cruise-2.jpg")) {
            val bitmap = assets.open("element-samples/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_ELECTRIC))
                assertTrue("$filename did not detect large electric mushroom: $predictions",
                    predictions.any { it.type == MushroomType.LARGE_ELECTRIC && it.isGiant == true })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun largePoisonCruiseScreensAreDetected() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (filename in listOf("large-poison-cruise-1.jpg", "large-poison-cruise-2.jpg")) {
            val bitmap = assets.open("element-samples/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_POISON))
                assertTrue("$filename did not detect large poison mushroom: $predictions",
                    predictions.any { it.type == MushroomType.LARGE_POISON && it.isGiant == true })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun largeWaterCruiseScreensAreDetected() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (filename in listOf("large-water-cruise-1.jpg", "large-water-cruise-2.jpg")) {
            val bitmap = assets.open("element-samples/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val predictions = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_WATER))
                assertTrue("$filename did not detect large water mushroom: $predictions",
                    predictions.any { it.type == MushroomType.LARGE_WATER && it.isGiant == true })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun allLargeElementReferencesDetectAtSourceAndCruiseResolution() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val samples = listOf(
            "large-electric.jpg" to MushroomType.LARGE_ELECTRIC,
            "large-poison.jpg" to MushroomType.LARGE_POISON,
            "large-fire.jpg" to MushroomType.LARGE_FIRE,
            "large-water.png" to MushroomType.LARGE_WATER,
            "large-crystal.jpg" to MushroomType.LARGE_CRYSTAL
        )
        for ((filename, expected) in samples) {
            val bitmap = assets.open("element-samples/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val sourcePredictions = MushroomDetector.detectMushrooms(bitmap, setOf(expected))
                assertTrue("$filename source did not detect $expected: $sourcePredictions",
                    sourcePredictions.any { it.type == expected && it.isGiant == true })
                val cruise = Bitmap.createScaledBitmap(
                    bitmap,
                    720,
                    (bitmap.height * 720f / bitmap.width).toInt(),
                    true
                )
                try {
                val cruisePredictions = MushroomDetector.detectMushrooms(cruise, setOf(expected))
                assertTrue("$filename cruise-size did not detect $expected: $cruisePredictions",
                        cruisePredictions.any { it.type == expected && it.isGiant == true })
                } finally { if (cruise !== bitmap) cruise.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun captureHealthRejectsBlackFrames() {
        val black = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val map = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        try {
            black.eraseColor(Color.BLACK)
            map.eraseColor(Color.rgb(65, 150, 70))
            assertTrue(DroneScannerManager.isBlankCapture(black))
            assertFalse(DroneScannerManager.isBlankCapture(map))
        } finally { black.recycle(); map.recycle() }
    }
    @Test fun diagnoseCapturedGameScreen() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("No diagnostic capture requested", args.getString("diagnoseCapture") == "true")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cat /sdcard/Download/pikmin-diagnostic.png")
        ).use { BitmapFactory.decodeStream(it) } ?: error("Cannot read diagnostic capture")
        try {
            val predictions = MushroomDetector.detectMushrooms(bitmap)
            android.util.Log.i("CvEvaluation", "diagnostic ${bitmap.width}x${bitmap.height}: $predictions")
            val capture = Bitmap.createScaledBitmap(bitmap, 720, (bitmap.height * 720f / bitmap.width).toInt(), true)
            try {
                val water = MushroomDetector.detectMushrooms(capture, setOf(com.pikmin.fakegps.cv.MushroomType.LARGE_WATER))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Original: $predictions\nCapture-size water-only: $water\n")
                })
            } finally { if (capture !== bitmap) capture.recycle() }
        } finally { bitmap.recycle() }
    }
    @Test fun grassIsNotAMushroom() {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            assertTrue(MushroomDetector.detectMushrooms(bitmap).isEmpty())
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun largeColorCandidateDoesNotClaimKnownSize() {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            Canvas(bitmap).drawRect(70f, 200f, 115f, 225f, Paint().apply { color = Color.rgb(147, 51, 234) })
            val found = MushroomDetector.detectMushrooms(bitmap, setOf(MushroomType.LARGE_PURPLE))
            assertTrue(found.isNotEmpty())
            assertTrue(found.all { it.isGiant == null })
        } finally { bitmap.recycle() }
    }

    @Test fun largeConnectedBackgroundIsFullyExcluded() {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            assertTrue(MushroomDetector.detectMushrooms(bitmap).isEmpty())
        } finally { bitmap.recycle() }
    }

    /** Real screenshots are supplied separately. Missing corpus is reported as skipped, never as accuracy success. */
    @Test fun evaluateLabelledCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val available = assets.list("cv-corpus").orEmpty().contains("manifest.json")
        assumeTrue("No labelled screenshot corpus supplied", available)
        val cases = JSONArray(assets.open("cv-corpus/manifest.json").bufferedReader().use { it.readText() })
        require(cases.length() > 0) { "Corpus is empty" }
        var tp = 0; var fp = 0; var fn = 0
        val details = JSONArray()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val filename = case.getString("image")
            require(!filename.contains("..") && !filename.startsWith('/'))
            val bitmap = assets.open("cv-corpus/$filename").use { BitmapFactory.decodeStream(it) }
                ?: error("Cannot decode $filename")
            try {
                val labels = case.getJSONArray("targets")
                val unmatched = (0 until labels.length()).toMutableSet()
                val predictions = MushroomDetector.detectMushrooms(bitmap)
                var matches = 0
                for (prediction in predictions) {
                    val match = unmatched.firstOrNull { i ->
                        val label = labels.getJSONObject(i)
                        val box = label.getJSONArray("box")
                        prediction.type.name == label.getString("type") &&
                            prediction.x.toDouble() in box.getDouble(0)..box.getDouble(2) &&
                            prediction.y.toDouble() in box.getDouble(1)..box.getDouble(3)
                    }
                    if (match != null) { unmatched.remove(match); matches++ }
                }
                tp += matches; fp += predictions.size - matches; fn += unmatched.size
                details.put(JSONObject().put("image", filename).put("tp", matches)
                    .put("fp", predictions.size - matches).put("fn", unmatched.size))
            } finally { bitmap.recycle() }
        }
        val result = JSONObject().put("images", cases.length()).put("tp", tp).put("fp", fp).put("fn", fn)
            .put("precision", if (tp + fp > 0) tp.toDouble() / (tp + fp) else JSONObject.NULL)
            .put("recall", if (tp + fn > 0) tp.toDouble() / (tp + fn) else JSONObject.NULL).put("cases", details)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "cv-evaluation.json")
        output.writeText(result.toString(2))
        android.util.Log.i("CvEvaluation", result.toString())
    }
}
