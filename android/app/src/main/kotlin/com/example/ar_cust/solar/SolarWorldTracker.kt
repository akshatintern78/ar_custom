package com.example.ar_cust.solar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Custom world tracking from the device rotation vector (no ARCore / Google AR).
 *
 * World Y is gravity-up. The virtual camera stays at the origin and only rotates,
 * matching the physical rear camera. Horizontal rooftop/ground planes are hit-tested
 * with a camera ray so the GLB can sit on the surface the user is pointing at.
 */
class SolarWorldTracker(
    context: Context,
) : SensorEventListener {

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        fun times(s: Float) = Vec3(x * s, y * s, z * s)
        fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun length() = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3 {
            val len = length().coerceAtLeast(1e-6f)
            return Vec3(x / len, y / len, z / len)
        }
        fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x,
        )
    }

    data class Hit(
        val point: Vec3,
        val distance: Float,
        val normal: Vec3,
    )

    data class CameraBasis(
        val forward: Vec3,
        val right: Vec3,
        val up: Vec3,
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationVector = FloatArray(5)
    private val deviceToWorld = FloatArray(16)
    private val cameraToWorld = FloatArray(16)
    private val worldToCamera = FloatArray(16)
    private val tmp = FloatArray(16)

    @Volatile
    var isTracking: Boolean = false
        private set

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }
        val values = event.values
        val n = minOf(values.size, rotationVector.size)
        System.arraycopy(values, 0, rotationVector, 0, n)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, rotationVector)
        // Device: X right, Y up screen, Z toward user. Rear camera looks toward -Z.
        SensorManager.remapCoordinateSystem(
            deviceToWorld,
            SensorManager.AXIS_X,
            SensorManager.AXIS_MINUS_Z,
            tmp,
        )
        System.arraycopy(tmp, 0, cameraToWorld, 0, 16)
        Matrix.invertM(worldToCamera, 0, cameraToWorld, 0)
        isTracking = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun cameraBasis(): CameraBasis {
        val forward = directionFromCamera(0f, 0f, -1f)
        val right = directionFromCamera(1f, 0f, 0f)
        val up = directionFromCamera(0f, 1f, 0f)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(cameraToWorld, orientation)
        return CameraBasis(
            forward = forward,
            right = right,
            up = up,
            yawDeg = Math.toDegrees(orientation[0].toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat(),
            rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat(),
        )
    }

    fun worldUp(): Vec3 = Vec3(0f, 1f, 0f)

    /**
     * Ray from the camera through a screen NDC point onto a gravity-horizontal plane.
     * Tries a ground plane (below the phone) and a rooftop plane (above), then a
     * forward fallback so looking level at a wall/roof still places the array.
     */
    fun hitTest(
        ndcX: Float,
        ndcY: Float,
        verticalFovDegrees: Float,
        aspect: Float,
    ): Hit? {
        val dir = screenRayWorld(ndcX, ndcY, verticalFovDegrees, aspect)
        val origin = Vec3(0f, 0f, 0f)
        val up = worldUp()
        val ground = intersectPlane(origin, dir, up, -DEFAULT_CAMERA_HEIGHT_M)
        val roof = intersectPlane(origin, dir, up, ROOF_PLANE_ABOVE_CAMERA_M)
        val candidates = listOfNotNull(ground, roof)
            .filter { it.distance in MIN_HIT_M..MAX_HIT_M }
            .sortedBy { abs(it.distance - PREFERRED_HIT_M) }
        if (candidates.isNotEmpty()) return candidates.first()

        val t = PREFERRED_HIT_M
        val point = dir.times(t)
        val planeY = point.y
        return intersectPlane(origin, dir, up, planeY)?.takeIf {
            it.distance in MIN_HIT_M..MAX_HIT_M
        } ?: Hit(point = point, distance = t, normal = up)
    }

    /**
     * Camera-space pose for an array sitting on a gravity-up plane with a yaw
     * around world Y. SceneView's camera stays identity; this parent transform
     * world-locks the real GLB.
     */
    fun arrayPoseInCamera(origin: Vec3, yawDeg: Float): Pair<Position, Rotation> {
        val pos = worldToCameraPoint(origin)
        val yaw = Math.toRadians(yawDeg.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        val xAxis = worldDirectionToCamera(Vec3(c, 0f, -s))
        val yAxis = worldDirectionToCamera(Vec3(0f, 1f, 0f))
        val zAxis = worldDirectionToCamera(Vec3(s, 0f, c))
        return Position(pos.x, pos.y, pos.z) to basisToEuler(xAxis, yAxis, zAxis)
    }

    fun worldToCameraPoint(world: Vec3): Vec3 {
        val inV = floatArrayOf(world.x, world.y, world.z, 1f)
        val outV = FloatArray(4)
        Matrix.multiplyMV(outV, 0, worldToCamera, 0, inV, 0)
        return Vec3(outV[0], outV[1], outV[2])
    }

    fun worldDirectionToCamera(world: Vec3): Vec3 {
        val inV = floatArrayOf(world.x, world.y, world.z, 0f)
        val outV = FloatArray(4)
        Matrix.multiplyMV(outV, 0, worldToCamera, 0, inV, 0)
        return Vec3(outV[0], outV[1], outV[2]).normalized()
    }

    fun cameraToWorldPoint(camera: Vec3): Vec3 {
        val inV = floatArrayOf(camera.x, camera.y, camera.z, 1f)
        val outV = FloatArray(4)
        Matrix.multiplyMV(outV, 0, cameraToWorld, 0, inV, 0)
        return Vec3(outV[0], outV[1], outV[2])
    }

    fun frustumSizeAtDistance(
        distance: Float,
        verticalFovDegrees: Float,
        aspect: Float,
    ): Pair<Float, Float> {
        val d = distance.coerceAtLeast(0.2f)
        val halfH = d * tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val halfW = halfH * aspect.coerceAtLeast(0.2f)
        return (2f * halfW) to (2f * halfH)
    }

    fun planeAxes(hit: Hit, cameraForward: Vec3): Pair<Vec3, Vec3> {
        val up = hit.normal.normalized()
        var right = cameraForward.cross(up)
        if (right.length() < 1e-3f) {
            right = Vec3(1f, 0f, 0f).cross(up)
        }
        right = right.normalized()
        val along = up.cross(right).normalized()
        return right to along
    }

    private fun screenRayWorld(
        ndcX: Float,
        ndcY: Float,
        verticalFovDegrees: Float,
        aspect: Float,
    ): Vec3 {
        val tanHalfV = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val tanHalfH = tanHalfV * aspect.coerceAtLeast(0.2f)
        return directionFromCamera(ndcX * tanHalfH, ndcY * tanHalfV, -1f)
    }

    private fun directionFromCamera(x: Float, y: Float, z: Float): Vec3 {
        val inV = floatArrayOf(x, y, z, 0f)
        val outV = FloatArray(4)
        Matrix.multiplyMV(outV, 0, cameraToWorld, 0, inV, 0)
        return Vec3(outV[0], outV[1], outV[2]).normalized()
    }

    private fun intersectPlane(
        origin: Vec3,
        dir: Vec3,
        normal: Vec3,
        planeConstant: Float,
    ): Hit? {
        val denom = normal.dot(dir)
        if (abs(denom) < 1e-4f) return null
        val t = (planeConstant - normal.dot(origin)) / denom
        if (t < 0.05f) return null
        return Hit(point = origin.plus(dir.times(t)), distance = t, normal = normal)
    }

    private fun basisToEuler(x: Vec3, y: Vec3, z: Vec3): Rotation {
        val r00 = x.x
        val r10 = x.y
        val r20 = x.z
        val r11 = y.y
        val r21 = y.z
        val r22 = z.z
        val pitch = atan2(-r20.toDouble(), sqrt((r00 * r00 + r10 * r10).toDouble()))
        val yaw = atan2(r10.toDouble(), r00.toDouble())
        val roll = atan2(r21.toDouble(), r22.toDouble())
        return Rotation(
            x = Math.toDegrees(pitch).toFloat(),
            y = Math.toDegrees(yaw).toFloat(),
            z = Math.toDegrees(roll).toFloat(),
        )
    }

    companion object {
        const val DEFAULT_CAMERA_HEIGHT_M = 1.5f
        const val ROOF_PLANE_ABOVE_CAMERA_M = 2.8f
        const val MIN_HIT_M = 0.8f
        const val MAX_HIT_M = 28f
        const val PREFERRED_HIT_M = 6f
    }
}
