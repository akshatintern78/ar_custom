package com.example.ar_cust.solar

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.Lifecycle
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Loads the solar rooftop GLB into a transparent [SceneView] layered over CameraX.
 *
 * Mesh proportions always match the GLB. Design metadata is display-only and never
 * shears the model. User move / rotate live on a parent pose node.
 */
class SolarArModelController(
    private val context: Context,
    private val sceneView: SceneView,
    private val scope: CoroutineScope,
) {

    data class ViewportFit(
        val distance: Float,
        val visualScale: Float,
        val halfExtentX: Float,
        val halfExtentY: Float,
        val halfExtentZ: Float,
        /** Camera-space Y so the array sits on the lower view, like a roof in front of you. */
        val offsetY: Float = 0f,
    )

    private var poseNode: Node? = null
    private var modelNode: ModelNode? = null
    private val moduleNodes = mutableListOf<ModelNode>()
    private var shadowNode: CubeNode? = null
    private var ghostNode: CubeNode? = null
    private var loadJob: Job? = null
    private var surfacePlan: SolarSurfacePlan? = null
    private var lastWorldScale: Float = 1f
    private var assetPath: String = SolarRooftopDims.BakedAsset

    /** Authored mesh scale (always 1 — GLB stays in real meters). */
    private var unitScale = 1f

    /** Local half-extents from GLB AABB (world meters, before user scale). */
    private var localHalfX = 3.795f
    private var localHalfY = 0.523f
    private var localHalfZ = 1.179f
    private var centerOffsetX = 0f
    private var centerOffsetY = 0f
    private var centerOffsetZ = 0f

    private var posX = 0f
    private var posY = 0f
    private var posZ = -DEFAULT_DISTANCE
    private var rotYDeg = 0f
    private var userScale = 1f

    /** Rooftop presentation pitch (from design tilt, Solar 360–style). */
    private var viewPitchDeg: Float = DEFAULT_VIEW_PITCH_DEG

    /** Last computed custom layout (pad + tilt); never from Google APIs. */
    private var rooftopLayout: SolarRooftopLayout? = null

    /** Last FOV applied to SceneView so framing and the Filament lens stay in sync. */
    private var lastSyncedFovDeg: Float = SolarArCameraOptics.sceneViewDefaultVerticalFovDegrees()

    val isLoaded: Boolean get() = modelNode != null && poseNode != null

    val meshWidthMeters: Float get() = localHalfX * 2f
    val meshHeightMeters: Float get() = localHalfY * 2f
    val meshDepthMeters: Float get() = localHalfZ * 2f

    val activeLayout: SolarRooftopLayout? get() = rooftopLayout
    val activeViewPitchDeg: Float get() = viewPitchDeg

    fun load(modelLocation: String, onDone: (Result<Unit>) -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Main) {
            val result = runCatching {
                clearModel()
                val path = resolveAssetPath(modelLocation)
                assetPath = path
                val rawInstances = runCatching {
                    sceneView.modelLoader.createInstancedModel(
                        path,
                        SolarSurfaceFitter.MAX_MODULES,
                    )
                }.getOrNull()
                val instances = if (rawInstances == null || rawInstances.isEmpty()) {
                    Log.w(TAG, "Instanced load unavailable, creating a single GLB instance")
                    listOf(sceneView.modelLoader.createModelInstance(path))
                } else {
                    rawInstances.toList()
                }
                require(instances.isNotEmpty()) { "GLB produced no instances: $path" }

                val pose = Node(engine = sceneView.engine).apply {
                    isEditable = false
                    isVisible = false
                }
                instances.forEachIndexed { index, instance ->
                    val node = ModelNode(
                        modelInstance = instance,
                        autoAnimate = false,
                        scaleToUnits = null,
                        centerOrigin = null,
                    ).apply {
                        isEditable = false
                        isShadowCaster = true
                        isShadowReceiver = true
                        setScreenSpaceContactShadows(true)
                        isVisible = index == 0
                    }
                    pose.addChildNode(node)
                    moduleNodes.add(node)
                }
                val node = moduleNodes.first()
                unitScale = 1f
                captureLocalExtents(node)
                attachContactShadow(pose)
                sceneView.addChildNode(pose)
                poseNode = pose
                modelNode = node
                applyModelFit()
                applyPoseTransform()
                Log.i(
                    TAG,
                    "GLB loaded OK path=$path modules=${moduleNodes.size} " +
                        "half=($localHalfX,$localHalfY,$localHalfZ)",
                )
                Unit
            }
            if (result.isFailure) {
                Log.e(TAG, "Failed to load model: $modelLocation", result.exceptionOrNull())
            }
            onDone(result)
        }
    }

    private fun resolveAssetPath(modelLocation: String): String {
        return when {
            modelLocation.startsWith("file://") -> modelLocation.removePrefix("file://")
            modelLocation.startsWith("content:") ||
                modelLocation.startsWith("android.resource:") ||
                modelLocation.startsWith("/") -> modelLocation
            else -> modelLocation
        }
    }

    /**
     * Applies custom rooftop layout (tilt + pad) from design metadata.
     * Mesh proportions always stay as authored in the GLB — never sheared to
     * match design feet. Layout math mirrors Solar 360's engine, without Google.
     */
    fun applyDesignConfig(config: SolarArConfig?) {
        val layout = SolarRooftopLayoutEngine.computeFromConfig(config)
        rooftopLayout = layout
        viewPitchDeg = SolarRooftopLayoutEngine.viewPitchDegrees(layout)
        Log.i(
            TAG,
            "Design layout kw=${layout.spec.kw} panels=${layout.spec.panelCount} " +
                "cols=${layout.spec.cols} rows=${layout.spec.rows} " +
                "tilt=${layout.tiltDeg} viewPitch=$viewPitchDeg " +
                "footprint=${layout.footprintW}x${layout.footprintL} " +
                "array=${layout.arrayW}x${layout.arrayL}",
        )
        applyModelFit()
        val pose = poseNode
        if (pose != null) {
            attachContactShadow(pose)
        }
    }

    fun scaledMaxExtent(): Float = max(localHalfX, max(localHalfY, localHalfZ)) * 2f

    /**
     * Drive SceneView's Filament lens so overlay FOV matches the rear camera.
     * Uses Filament's 24 mm vertical sensor + a matching focal length.
     */
    fun syncSceneCamera(verticalFovDegrees: Float) {
        val vfov = verticalFovDegrees.coerceIn(25f, 95f)
        val focalMm = SolarArCameraOptics.focalLengthMmForVerticalFov(vfov)
        sceneView.cameraNode.apply {
            far = FAR_PLANE_METERS
            near = NEAR_PLANE_METERS
            focalLength = focalMm
        }
        lastSyncedFovDeg = vfov
        Log.i(TAG, "Scene camera synced vfov=$vfov focalMm=$focalMm far=$FAR_PLANE_METERS")
    }

    /**
     * Frame the GLB at authored meter size. Distance is driven by WIDTH so the
     * 6-panel row fills most of the camera. Height is not allowed to push the
     * array farther away (that made a thin floating sticker). Uniform shrink is
     * last-resort only if the far plane would clip.
     */
    fun computeViewportFit(
        viewWidth: Int,
        viewHeight: Int,
        uiTopPx: Int = 0,
        uiBottomPx: Int = 0,
        verticalFovDegrees: Float,
        fillFraction: Float = DEFAULT_FILL_FRACTION,
    ): ViewportFit {
        val fov = verticalFovDegrees.coerceIn(25f, 95f)
        val fullH = viewHeight.coerceAtLeast(1).toFloat()
        val fullW = viewWidth.coerceAtLeast(1).toFloat()
        val fill = fillFraction.coerceIn(0.70f, 0.98f)
        val aspect = (fullW / fullH).coerceAtLeast(0.2f)
        val tanHalfV = tan(Math.toRadians(fov / 2.0)).toFloat().coerceAtLeast(1e-4f)

        val pitchRad = Math.toRadians(viewPitchDeg.toDouble())
        val cP = abs(cos(pitchRad)).toFloat()
        val sP = abs(sin(pitchRad)).toFloat()
        val pitchedHalfY = localHalfY * cP + localHalfZ * sP
        val pitchedHalfZ = localHalfY * sP + localHalfZ * cP
        val orbitHalfX = max(localHalfX, pitchedHalfZ).coerceAtLeast(1e-4f)
        val orbitHalfY = pitchedHalfY.coerceAtLeast(1e-4f)

        var distance = (orbitHalfX / (tanHalfV * aspect * fill)).coerceAtLeast(
            SolarArPlacementController.MIN_COMFORTABLE_DISTANCE,
        )
        var visualScale = 1f

        val maxDistance = SolarArPlacementController.MAX_COMFORTABLE_DISTANCE
        if (distance > maxDistance) {
            distance = maxDistance
            val frustumHalfW = distance * tanHalfV * aspect
            visualScale = ((frustumHalfW * fill) / orbitHalfX).coerceIn(
                SolarArPlacementController.DEFAULT_MIN_SCALE,
                1f,
            )
        }

        val frustumHalfH = distance * tanHalfV
        val topFrac = uiTopPx.toFloat().coerceAtLeast(0f) / fullH
        val bottomFrac = uiBottomPx.toFloat().coerceAtLeast(0f) / fullH
        val usableBottom = -frustumHalfH + 2f * frustumHalfH * bottomFrac
        val usableTop = frustumHalfH - 2f * frustumHalfH * topFrac
        val halfY = orbitHalfY * visualScale
        val pad = frustumHalfH * SURFACE_PAD_FRAC
        val offsetY = if (usableTop - usableBottom > 2f * halfY + 2f * pad) {
            usableBottom + halfY + pad
        } else {
            (usableBottom + usableTop) * 0.5f
        }

        Log.i(
            TAG,
            "ViewportFit d=$distance scale=$visualScale offsetY=$offsetY fov=$fov " +
                "glb=(${meshWidthMeters}x${meshHeightMeters}x${meshDepthMeters} m) " +
                "pitchedHalf=($orbitHalfX,$orbitHalfY,$pitchedHalfZ)",
        )

        return ViewportFit(
            distance = distance,
            visualScale = visualScale,
            halfExtentX = localHalfX,
            halfExtentY = pitchedHalfY,
            halfExtentZ = pitchedHalfZ,
            offsetY = offsetY,
        )
    }

    fun frustumWidthMeters(distance: Float, viewWidth: Int, viewHeight: Int, verticalFovDegrees: Float): Float {
        if (viewWidth <= 0 || viewHeight <= 0) return 0f
        val aspect = viewWidth.toFloat() / viewHeight.toFloat()
        val halfH = distance * tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        return 2f * halfH * aspect
    }

    fun bakedPanelsThatFit(frustumWidthMeters: Float): Int {
        val panelW = meshWidthMeters / BAKED_PANEL_COUNT
        if (panelW <= 1e-4f || frustumWidthMeters <= 0f) return BAKED_PANEL_COUNT
        return max(1, kotlin.math.floor(frustumWidthMeters / panelW).toInt())
    }

    fun verticalFovDegrees(viewWidth: Int, viewHeight: Int): Float {
        // Prefer the FOV we just wrote onto CameraNode. Projection can lag a frame
        // and would then frame the GLB as if the lens were still 46°, looking tiny.
        if (lastSyncedFovDeg in 25f..95f) return lastSyncedFovDeg
        val fromProjection = runCatching {
            val cotHalf = sceneView.cameraNode.projectionTransform.y.y
            if (cotHalf > 0.05f) {
                Math.toDegrees(2.0 * atan(1.0 / cotHalf)).toFloat()
            } else {
                0f
            }
        }.getOrDefault(0f)
        if (fromProjection in 25f..95f) return fromProjection
        return SolarArCameraOptics.sceneViewDefaultVerticalFovDegrees()
    }

    fun applyPose(pose: SolarArPlacementController.Pose) {
        posX = pose.position.x
        posY = pose.position.y
        posZ = pose.position.z
        rotYDeg = pose.rotation.y
        userScale = pose.scale.x
        applyPoseTransform()
    }

    fun setModelVisible(show: Boolean) {
        poseNode?.isVisible = show
        val plan = surfacePlan
        moduleNodes.forEachIndexed { index, node ->
            node.isVisible = show && (plan == null && index == 0 || plan != null && index < plan.count)
        }
        shadowNode?.isVisible = show
        if (show) {
            ghostNode?.isVisible = false
        }
    }

    fun applySurfacePlan(plan: SolarSurfacePlan) {
        surfacePlan = plan
        applyModelFit()
        val pose = poseNode
        if (pose != null) {
            attachContactShadow(pose)
        }
        Log.i(
            TAG,
            "Surface plan ${plan.cols}x${plan.rows} modules=${plan.count} " +
                "panels=${plan.panelsPlaced} pad=${plan.footprintW}x${plan.footprintL}",
        )
    }

    /**
     * World-lock the array: SceneView camera stays identity while this parent
     * is converted into camera space from IMU tracking.
     */
    fun applyWorldLocked(
        tracker: SolarWorldTracker,
        origin: SolarWorldTracker.Vec3,
        yawDeg: Float,
        scale: Float = 1f,
    ) {
        val pose = poseNode ?: return
        val (position, rotation) = tracker.arrayPoseInCamera(origin, yawDeg)
        lastWorldScale = scale.coerceIn(
            SolarArPlacementController.DEFAULT_MIN_SCALE,
            SolarArPlacementController.DEFAULT_MAX_SCALE,
        )
        pose.transform = Transform(
            position = position,
            rotation = rotation,
            scale = Scale(lastWorldScale),
        )
        posX = position.x
        posY = position.y
        posZ = position.z
        rotYDeg = yawDeg
        userScale = lastWorldScale
    }

    fun showGhostOnHit(
        tracker: SolarWorldTracker,
        hit: SolarWorldTracker.Hit,
        yawDeg: Float,
    ) {
        val pose = poseNode ?: return
        applyWorldLocked(tracker, hit.point, yawDeg, 1f)
        pose.isVisible = true
        moduleNodes.forEach { it.isVisible = false }
        shadowNode?.isVisible = false
        ensureGhost(pose)
        ghostNode?.isVisible = true
    }

    /** Screen-space projected half-extents at the current yaw + pitch + user scale. */
    fun projectedHalfExtents(): Pair<Float, Float> {
        val pitchRad = Math.toRadians(viewPitchDeg.toDouble())
        val cP = abs(cos(pitchRad)).toFloat()
        val sP = abs(sin(pitchRad)).toFloat()
        val pitchedHalfY = localHalfY * cP + localHalfZ * sP
        val pitchedHalfZ = localHalfY * sP + localHalfZ * cP
        val yaw = Math.toRadians(rotYDeg.toDouble())
        val c = abs(cos(yaw)).toFloat()
        val s = abs(sin(yaw)).toFloat()
        val halfX = (localHalfX * c + pitchedHalfZ * s) * userScale
        val halfY = pitchedHalfY * userScale
        return halfX to halfY
    }

    private fun captureLocalExtents(node: ModelNode) {
        val center = runCatching { node.center }.getOrNull()
        centerOffsetX = center?.x ?: 0f
        centerOffsetY = center?.y ?: 0f
        centerOffsetZ = center?.z ?: 0f

        val half = runCatching { node.halfExtent }.getOrNull()
        val hx: Float
        val hy: Float
        val hz: Float
        if (half != null) {
            hx = max(half.x, 0.05f)
            hy = max(half.y, 0.05f)
            hz = max(half.z, 0.05f)
        } else {
            val size = runCatching { node.size }.getOrNull()
            if (size != null) {
                hx = max(size.x * 0.5f, 0.05f)
                hy = max(size.y * 0.5f, 0.05f)
                hz = max(size.z * 0.5f, 0.05f)
            } else {
                // Fallback matches solar_3kw_6x1_rooftop.glb accessor bounds (~7.59×1.05×2.36 m).
                hx = 3.795f
                hy = 0.523f
                hz = 1.179f
            }
        }
        // Keep authored GLB meters — camera distance is derived from these extents.
        unitScale = 1f
        localHalfX = hx
        localHalfY = hy
        localHalfZ = hz
        Log.i(
            TAG,
            "GLB dimension points half=($localHalfX,$localHalfY,$localHalfZ) " +
                "center=($centerOffsetX,$centerOffsetY,$centerOffsetZ) " +
                "full=(${hx * 2f},${hy * 2f},${hz * 2f})",
        )
    }

    /** Uniform mesh transform centered on the GLB origin — proportions unchanged. */
    private fun applyModelFit() {
        val plan = surfacePlan
        moduleNodes.forEachIndexed { index, node ->
            val slot = plan?.centers?.getOrNull(index)
            val show = plan == null && index == 0 || slot != null
            node.isVisible = show && (poseNode?.isVisible == true)
            val ox = (slot?.first ?: 0f) - centerOffsetX * unitScale
            val oy = -centerOffsetY * unitScale
            val oz = (slot?.second ?: 0f) - centerOffsetZ * unitScale
            node.transform = Transform(
                position = Position(x = ox, y = oy, z = oz),
                rotation = Rotation(x = viewPitchDeg, y = 0f, z = 0f),
                scale = Scale(unitScale),
            )
        }
    }

    /**
     * Concrete-style pad under the array (Solar 360 footprint idea) so the GLB
     * reads as sitting on a rooftop surface. No ARCore / Google plane required.
     */
    private fun attachContactShadow(parent: Node) {
        detachContactShadow()
        runCatching {
            val pitchRad = Math.toRadians(viewPitchDeg.toDouble())
            val pitchedHalfY = localHalfY * abs(cos(pitchRad)).toFloat() +
                localHalfZ * abs(sin(pitchRad)).toFloat()
            val layout = rooftopLayout
            val padW = surfacePlan?.footprintW
                ?: layout?.footprintW?.takeIf { it > 0.5f }
                ?: (localHalfX * 2.16f)
            val padD = surfacePlan?.footprintL
                ?: layout?.footprintL?.takeIf { it > 0.5f }
                ?: (localHalfZ * 1.90f)
            val material = sceneView.materialLoader.createColorInstance(
                color = colorOf(0.55f, 0.54f, 0.52f, 0.92f),
                metallic = 0.08f,
                roughness = 0.78f,
            )
            val node = CubeNode(
                engine = sceneView.engine,
                size = Size(
                    x = padW,
                    y = 0.036f,
                    z = padD,
                ),
                center = Position(
                    x = 0f,
                    y = -pitchedHalfY - 0.01f,
                    z = localHalfZ * 0.06f,
                ),
                materialInstance = material,
            ).apply {
                isShadowCaster = false
                isShadowReceiver = true
            }
            parent.addChildNode(node)
            shadowNode = node
        }.onFailure { error ->
            Log.w(TAG, "Rooftop pad skipped", error)
        }
    }

    private fun detachContactShadow() {
        val shadow = shadowNode ?: return
        shadowNode = null
        runCatching { shadow.parent?.removeChildNode(shadow) }
        runCatching { shadow.destroy() }
    }

    private fun ensureGhost(parent: Node) {
        if (ghostNode != null) return
        runCatching {
            val material = sceneView.materialLoader.createColorInstance(
                color = colorOf(0.12f, 0.72f, 0.68f, 0.28f),
                metallic = 0.05f,
                roughness = 0.86f,
            )
            val node = CubeNode(
                engine = sceneView.engine,
                size = Size(x = 2.2f, y = 0.02f, z = 1.4f),
                center = Position(x = 0f, y = -0.04f, z = 0f),
                materialInstance = material,
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
            }
            parent.addChildNode(node)
            ghostNode = node
        }
    }

    private fun detachGhost() {
        val ghost = ghostNode ?: return
        ghostNode = null
        runCatching { ghost.parent?.removeChildNode(ghost) }
        runCatching { ghost.destroy() }
    }

    private fun applyPoseTransform() {
        val node = poseNode ?: return
        val uniform = userScale.coerceIn(
            SolarArPlacementController.DEFAULT_MIN_SCALE,
            SolarArPlacementController.DEFAULT_MAX_SCALE,
        )
        node.transform = Transform(
            position = Position(x = posX, y = posY, z = posZ),
            rotation = Rotation(x = 0f, y = rotYDeg, z = 0f),
            scale = Scale(uniform),
        )
    }

    private fun clearModel() {
        val pose = poseNode
        val modules = moduleNodes.toList()
        modelNode = null
        poseNode = null
        moduleNodes.clear()
        unitScale = 1f
        rooftopLayout = null
        surfacePlan = null
        viewPitchDeg = DEFAULT_VIEW_PITCH_DEG
        detachGhost()
        detachContactShadow()
        modules.forEach { node ->
            runCatching { pose?.removeChildNode(node) }
            runCatching { node.destroy() }
        }
        if (pose != null) {
            runCatching { sceneView.removeChildNode(pose) }
            runCatching { pose.destroy() }
        }
    }

    fun destroy() {
        loadJob?.cancel()
        loadJob = null
        clearModel()
    }

    companion object {
        private const val TAG = "SolarArModelController"
        const val DEFAULT_ASSET_PATH = SolarRooftopDims.BakedAsset
        /** Baked mesh is solar_3kw_6x1_rooftop — a single 6-panel row. */
        const val BAKED_PANEL_COUNT = SolarRooftopDims.BakedPanelCount
        /** Fallback only — real framing uses GLB half-extents via [computeViewportFit]. */
        const val DEFAULT_DISTANCE = 8f
        /** Default tilt before design heights are applied. */
        private const val DEFAULT_VIEW_PITCH_DEG = 40f
        /** Nearly edge-to-edge in portrait; still leaves a clip-safe margin. */
        private const val DEFAULT_FILL_FRACTION = 0.96f
        /** Keep the array just above the bottom controls. */
        private const val SURFACE_PAD_FRAC = 0.035f
        private const val FAR_PLANE_METERS = 80f
        private const val NEAR_PLANE_METERS = 0.05f

        fun resolveModelLocation(
            modelUri: Uri?,
            glbPath: String?,
        ): String {
            modelUri?.let { return it.toString() }
            glbPath?.takeIf { it.isNotBlank() }?.let { return it }
            return DEFAULT_ASSET_PATH
        }

        fun createTransparentSceneView(context: Context, lifecycle: Lifecycle): SceneView {
            return SceneView(
                context = context,
                sharedLifecycle = lifecycle,
                isOpaque = false,
                cameraManipulator = null,
            ).apply {
                setZOrderMediaOverlay(true)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                cameraNode.far = FAR_PLANE_METERS
                cameraNode.near = NEAR_PLANE_METERS
                // Overhead-front sun so PBR glass/cells shade as a real 3D rooftop.
                mainLightNode = SceneView.DefaultLightNode(engine).apply {
                    intensity = SceneView.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY * 1.85f
                    lightDirection = Position(x = 0.42f, y = -1f, z = -0.62f)
                    isShadowCaster = true
                }
            }
        }
    }
}
