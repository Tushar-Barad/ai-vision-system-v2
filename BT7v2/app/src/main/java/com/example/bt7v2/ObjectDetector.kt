package com.example.bt7v2

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TFLiteObjectDetector

class ObjectDetector(context: Context) {

    private var detector: TFLiteObjectDetector? = null

    init {
        try {
            val options = TFLiteObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(5)
                .setScoreThreshold(0.4f)
                .build()

            detector = TFLiteObjectDetector.createFromFileAndOptions(
                context,
                "detect.tflite",
                options
            )
        } catch (e: Exception) {
            e.printStackTrace()
            detector = null
        }
    }

    fun detectObjects(bitmap: Bitmap): List<DetectedObject> {
        return try {
            if (detector == null) {
                return emptyList()
            }

            val tensorImage = TensorImage.fromBitmap(bitmap)
            val results = detector?.detect(tensorImage) ?: emptyList()

            results.mapNotNull { detection ->
                val categories = detection.categories
                if (categories.isEmpty()) return@mapNotNull null

                val label = categories[0].label
                val confidence = categories[0].score

                if (confidence > 0.4f) {
                    DetectedObject(
                        label = label,
                        confidence = confidence,
                        distance = estimateDistance(detection.boundingBox.width() * detection.boundingBox.height())
                    )
                } else null
            }.distinctBy { it.label }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun estimateDistance(area: Float): String {
        return when {
            area > 100000 -> "Very close (< 1m)"
            area > 50000 -> "Close (1-2m)"
            area > 20000 -> "Medium (2-3m)"
            else -> "Far (> 3m)"
        }
    }

    fun close() {
        detector?.close()
    }

    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val distance: String
    )
}
