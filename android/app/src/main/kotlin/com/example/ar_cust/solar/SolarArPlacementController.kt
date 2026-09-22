package com.example.ar_cust.solar

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Manual placement math for camera-relative positioning.
 * No ARCore / plane detection / SLAM / GPS / world tracking.
 *
 * Move clamps keep the full model (not just its center) inside the frustum.
 */
class SolarArPlacementController(
    private val initialDistance: Float = DEFAULT_INITIAL_DISTANCE,
    private val minScale: Float = DEFAULT_MIN_SCALE,
    private val maxScale: Float = DEFAULT_MAX_SCALE,
    private val maxHorizontalSpan: Float = DEFAULT_MAX_HORIZONTAL_SPAN,
    private val maxVerticalSpan: Float = DEFAULT_MAX_VERTICAL_SPAN,
) {

    data class Pose(
        val position: Position,
        val rotation: Rotation,
        val scale: Scale,
        val distance: Float,
    )

    private var initialPose: Pose = Pose(
        position = Position(x = 0f, y = 0f, z = -initialDistance),
        rotation = Rotation(x = 0f, y = 0f, z = 0f),
        scale = Scale(1f),
        distance = initialDistance,
    )

    var currentPose: Pose = initialPose
        private set

    var hasBeenPlacedByUser: Boolean = false
        private set

    /** Model half-extents at visualScale = 1 (world units). */
    private var modelHalfX: Float = 0.6f
    private var modelHalfY: Float = 0.3f
    private var modelHalfZ: Float = 0.6f

    fun setModelExtents(halfX: Float, halfY: Float, halfZ: Float = halfX) {
        modelHalfX = halfX.coerceAtLeast(0.05f)
        modelHalfY = halfY.coerceAtLeast(0.05f)
        modelHalfZ = halfZ.coerceAtLeast(0.05f)
    }

    fun captureInitial(
        distance: Float = initialDistance,
        rotationY: Float = 0f,
        visualScale: Float = 1f,
        offsetY: Float = 0f,
    ) {
        val pose = Pose(
            position = Position(x = 0f, y = offsetY, z = -distance),
            rotation = Rotation(x = 0f, y = rotationY, z = 0f),
            scale = Scale(visualScale.coerceIn(minScale, maxScale)),
            distance = distance,
        )
        initialPose = pose
        currentPose = pose
        hasBeenPlacedByUser = false
    }

    fun reset(): Pose {
        currentPose = initialPose.copy(
            position = Position(
                x = initialPose.position.x,
                y = initialPose.position.y,
                z = initialPose.position.z,
            ),
            rotation = Rotation(
                x = initialPose.rotation.x,
                y = initialPose.rotation.y,
                z = initialPose.rotation.z,
            ),
            scale = Scale(initialPose.scale.x),
            distance = initialPose.distance,
        )
        return currentPose
    }

    fun placeFromScreenTap(
        screenX: Float,
        screenY: Float,
        viewWidth: Int,
        viewHeight: Int,
        verticalFovDegrees: Float = DEFAULT_VERTICAL_FOV_DEGREES,
    ): Pose {
        val distance = currentPose.distance
        val position = screenToCameraRelative(
            screenX = screenX,
            screenY = screenY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            distance = distance,
            verticalFovDegrees = verticalFovDegrees,
        )
        val (halfW, halfH) = frustumHalfExtents(
            viewWidth,
            viewHeight,
            distance,
            verticalFovDegrees,
        )
        currentPose = currentPose.copy(
            position = clampPosition(
                position,
                distance,
                halfW,
                halfH,
                currentPose.scale.x,
                currentPose.rotation.y,
            ),
        )
        hasBeenPlacedByUser = true
        return currentPose
    }

    fun moveByScreenDelta(
        deltaXPx: Float,
        deltaYPx: Float,
        viewWidth: Int,
        viewHeight: Int,
        verticalFovDegrees: Float = DEFAULT_VERTICAL_FOV_DEGREES,
    ): Pose {
        val distance = currentPose.distance
        val (halfW, halfH) = frustumHalfExtents(
            viewWidth,
            viewHeight,
            distance,
            verticalFovDegrees,
        )
        val (worldPerPxX, worldPerPxY) = if (viewWidth <= 0 || viewHeight <= 0) {
            0f to 0f
        } else {
            (2f * halfW / viewWidth) to (2f * halfH / viewHeight)
        }
        val next = Position(
            x = currentPose.position.x + deltaXPx * worldPerPxX,
            y = currentPose.position.y - deltaYPx * worldPerPxY,
            z = -distance,
        )
        currentPose = currentPose.copy(
            position = clampPosition(
                next,
                distance,
                halfW,
                halfH,
                currentPose.scale.x,
                currentPose.rotation.y,
            ),
        )
        return currentPose
    }

    fun rotateByScreenDelta(
        deltaXPx: Float,
        viewWidth: Int,
        viewHeight: Int,
        verticalFovDegrees: Float = DEFAULT_VERTICAL_FOV_DEGREES,
    ): Pose {
        val degrees = deltaXPx * ROTATION_DEGREES_PER_PIXEL
        val nextY = currentPose.rotation.y + degrees
        val distance = currentPose.distance
        val (halfW, halfH) = frustumHalfExtents(
            viewWidth,
            viewHeight,
            distance,
            verticalFovDegrees,
        )
        currentPose = currentPose.copy(
            rotation = Rotation(
                x = currentPose.rotation.x,
                y = nextY,
                z = currentPose.rotation.z,
            ),
            position = clampPosition(
                currentPose.position,
                distance,
                halfW,
                halfH,
                currentPose.scale.x,
                nextY,
            ),
        )
        return currentPose
    }

    fun suggestDistanceForBounds(
        modelExtent: Float,
        verticalFovDegrees: Float = DEFAULT_VERTICAL_FOV_DEGREES,
        targetFillFraction: Float = 0.55f,
        minDistance: Float = MIN_COMFORTABLE_DISTANCE,
        maxDistance: Float = MAX_COMFORTABLE_DISTANCE,
    ): Float {
        if (modelExtent <= 0f) return initialDistance
        val halfFovRad = Math.toRadians((verticalFovDegrees / 2.0))
        val visibleHeightAtUnit = 2f * tan(halfFovRad).toFloat()
        val desiredVisible = modelExtent / targetFillFraction.coerceIn(0.2f, 0.9f)
        val distance = desiredVisible / visibleHeightAtUnit
        return distance.coerceIn(minDistance, maxDistance)
    }

    private fun projectedHalfExtents(scale: Float, yawDegrees: Float): Pair<Float, Float> {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = kotlin.math.abs(cos(yaw)).toFloat()
        val s = kotlin.math.abs(sin(yaw)).toFloat()
        val halfX = (modelHalfX * c + modelHalfZ * s) * scale
        val halfY = modelHalfY * scale
        return halfX to halfY
    }

    private fun screenToCameraRelative(
        screenX: Float,
        screenY: Float,
        viewWidth: Int,
        viewHeight: Int,
        distance: Float,
        verticalFovDegrees: Float,
    ): Position {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return Position(x = 0f, y = 0f, z = -distance)
        }
        val ndcX = (screenX / viewWidth) * 2f - 1f
        val ndcY = -((screenY / viewHeight) * 2f - 1f)
        val (halfW, halfH) = frustumHalfExtents(viewWidth, viewHeight, distance, verticalFovDegrees)
        return Position(
            x = ndcX * halfW,
            y = ndcY * halfH,
            z = -distance,
        )
    }

    private fun frustumHalfExtents(
        viewWidth: Int,
        viewHeight: Int,
        distance: Float,
        verticalFovDegrees: Float,
    ): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f
        val aspect = viewWidth.toFloat() / viewHeight.toFloat()
        val halfH = distance * tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val halfW = halfH * aspect
        return halfW to halfH
    }

    /**
     * Keep the entire panel inside the frustum by clamping the center with
     * model half-extents (yaw-aware) subtracted from the visible half-size.
     */
    private fun clampPosition(
        position: Position,
        distance: Float,
        halfW: Float,
        halfH: Float,
        scale: Float,
        yawDegrees: Float,
    ): Position {
        val (modelHX, modelHY) = projectedHalfExtents(scale, yawDegrees)
        val margin = CONTAIN_MARGIN
        val maxX = if (halfW > 0f) {
            max(0f, halfW * CONTAIN_FILL - modelHX - margin)
        } else {
            maxHorizontalSpan
        }
        val maxY = if (halfH > 0f) {
            max(0f, halfH * CONTAIN_FILL - modelHY - margin)
        } else {
            maxVerticalSpan
        }
        return Position(
            x = position.x.coerceIn(-maxX, maxX),
            y = position.y.coerceIn(-maxY, maxY),
            z = -distance,
        )
    }

    companion object {
        /** Fallback before GLB extents are known; placeFitted overwrites from mesh AABB. */
        const val DEFAULT_INITIAL_DISTANCE = 8f
        /** Last-resort uniform shrink only — never squash the GLB down to a sticker. */
        const val DEFAULT_MIN_SCALE = 0.55f
        const val DEFAULT_MAX_SCALE = 4.0f
        const val DEFAULT_VERTICAL_FOV_DEGREES = 46.4f
        const val MIN_COMFORTABLE_DISTANCE = 2.0f
        /** Far enough to fit the ~7.6 m 6-panel GLB at scale 1 in portrait. */
        const val MAX_COMFORTABLE_DISTANCE = 45f
        private const val DEFAULT_MAX_HORIZONTAL_SPAN = 8.0f
        private const val DEFAULT_MAX_VERTICAL_SPAN = 4.0f
        private const val ROTATION_DEGREES_PER_PIXEL = 0.35f
        private const val CONTAIN_FILL = 0.98f
        private const val CONTAIN_MARGIN = 0.01f
        }
}
