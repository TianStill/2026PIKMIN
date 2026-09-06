package com.pikmin.fakegps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.pikmin.fakegps.cv.MushroomDetector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DetectorInstrumentedTest {
    @Test fun grassIsNotAMushroom() {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            assertTrue(MushroomDetector.detectMushrooms(bitmap).isEmpty())
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun colorCandidateDoesNotClaimKnownSize() {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            Canvas(bitmap).drawRect(70f, 200f, 115f, 225f, Paint().apply { color = Color.RED })
            val found = MushroomDetector.detectMushrooms(bitmap)
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
