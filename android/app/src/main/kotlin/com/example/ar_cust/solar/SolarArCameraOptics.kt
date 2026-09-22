package com.example.ar_cust.solar

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Matches SceneView's Filament lens to the rear CameraX preview after FILL_CENTER crop.
 *
 * Filament uses a 24 mm vertical sensor. SceneView's default 28 mm lens is ~46.4° VFOV,
 * which is much narrower than a typical phone rear camera (~70°+). Using that mismatch
 * makes a real-meter GLB look like a tiny sticker.
 */
@OptIn(ExperimentalCamera2Interop::class)
object SolarArCameraOptics {
    private const val TAG = "SolarArCameraOptics"

    /** Filament Camera.cpp SENSOR_SIZE (meters) → 24 mm vertical film. */
    const val FILAMENT_SENSOR_HEIGHT_MM = 24.0

    /** SceneView CameraNode default focalLength (millimeters). */
    const val SCENEVIEW_DEFAULT_FOCAL_MM = 28.0

    fun sceneViewDefaultVerticalFovDegrees(): Float {
        val half = FILAMENT_SENSOR_HEIGHT_MM / (2.0 * SCENEVIEW_DEFAULT_FOCAL_MM)
        return Math.toDegrees(2.0 * atan(half)).toFloat()
    }

    fun focalLengthMmForVerticalFov(verticalFovDegrees: Float): Double {
        val vfov = verticalFovDegrees.toDouble().coerceIn(25.0, 95.0)
        return FILAMENT_SENSOR_HEIGHT_MM / (2.0 * tan(Math.toRadians(vfov / 2.0)))
    }

    /**
     * Vertical FOV of the *visible* PreviewView after FILL_CENTER crop.
     * Sensor size and focal length are millimeters (Camera2).
     */
    fun verticalFovFromRearCamera(
        cameraInfo: CameraInfo?,
        viewWidth: Int,
        viewHeight: Int,
    ): Float? {
        if (cameraInfo == null || viewWidth <= 0 || viewHeight <= 0) return null
        return runCatching {
            val c2 = Camera2CameraInfo.from(cameraInfo)
            val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return@runCatching null
            val focals = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            ) ?: return@runCatching null
            val focalMm = focals.firstOrNull()?.toDouble() ?: return@runCatching null
            if (focalMm <= 0.0) return@runCatching null

            val sensorW = sensor.width.toDouble()
            val sensorH = sensor.height.toDouble()
            if (sensorW <= 0.0 || sensorH <= 0.0) return@runCatching null

            val viewAspect = viewWidth.toDouble() / viewHeight.toDouble()
            val portrait = viewHeight >= viewWidth
            val sensorLong = max(sensorW, sensorH)
            val sensorShort = min(sensorW, sensorH)
            val fullV = if (portrait) sensorLong else sensorShort
            val fullH = if (portrait) sensorShort else sensorLong
            val sensorAspect = fullH / fullV

            val visibleV = if (viewAspect > sensorAspect) {
                fullH / viewAspect
            } else {
                fullV
            }

            val vfov = Math.toDegrees(2.0 * atan((visibleV / 2.0) / focalMm)).toFloat()
            Log.i(
                TAG,
                "CameraX FOV vfov=$vfov focalMm=$focalMm sensor=${sensorW}x$sensorH " +
                    "visibleV=$visibleV view=${viewWidth}x$viewHeight",
            )
            vfov.takeIf { it in 25f..95f }
        }.getOrNull()
    }
}
