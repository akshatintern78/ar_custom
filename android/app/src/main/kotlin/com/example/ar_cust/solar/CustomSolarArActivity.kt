package com.example.ar_cust.solar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.ar_cust.R
import com.example.ar_cust.databinding.ActivityCustomSolarArBinding
import com.google.android.material.button.MaterialButton
import io.github.sceneview.SceneView
import kotlin.math.abs

/**
 * Live rear camera (CameraX) + transparent SceneView/Filament GLB overlay.
 */
class CustomSolarArActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomSolarArBinding

    private var sceneView: SceneView? = null
    private var modelController: SolarArModelController? = null
    private lateinit var placementController: SolarArPlacementController
    private lateinit var worldTracker: SolarWorldTracker

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCameraInfo: CameraInfo? = null
    private var resourcesReleased: Boolean = false
    private var cameraStarted: Boolean = false
    private var frameLoopRunning: Boolean = false

    private var modelLocation: String = SolarArModelController.DEFAULT_ASSET_PATH
    private var solarConfig: SolarArConfig? = null

    private var interactionMode: InteractionMode = InteractionMode.PLACE
    private var modelReady: Boolean = false
    private var modelPlaced: Boolean = false
    private var useWorldLock: Boolean = false
    private var worldOrigin: SolarWorldTracker.Vec3? = null
    private var worldYawDeg: Float = 0f
    private var lastFov: Float = SolarArCameraOptics.sceneViewDefaultVerticalFovDegrees()
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var totalDragDistance: Float = 0f
    private var isDragging: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.permissionPanel.isVisible = false
            binding.bottomBar.isVisible = true
            startCameraThenModel()
        } else {
            showPermissionDenied()
        }
    }

    private enum class InteractionMode {
        PLACE, MOVE, ROTATE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomSolarArBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.setBackgroundColor(Color.BLACK)
        binding.previewContainer.setBackgroundColor(Color.TRANSPARENT)

        readIntentExtras()
        worldTracker = SolarWorldTracker(this)
        setupUi()
        setupGestures()
        setupBackHandler()
        setupSceneOverlay()
        ensureCameraPermissionThenStart()
    }

    override fun onResume() {
        super.onResume()
        worldTracker.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED && !cameraStarted
        ) {
            startCameraThenModel()
        }
        if (modelReady) {
            startFrameLoop()
        }
    }

    override fun onPause() {
        stopFrameLoop()
        if (::worldTracker.isInitialized) {
            worldTracker.stop()
        }
        super.onPause()
    }

    private fun readIntentExtras() {
        solarConfig = SolarArConfig.readFromIntent(intent)

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_MODEL_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_MODEL_URI)
        }
        val path = intent.getStringExtra(EXTRA_GLB_PATH)
        modelLocation = SolarArModelController.resolveModelLocation(uri, path)

        val distance = intent.getFloatExtra(
            EXTRA_INITIAL_DISTANCE,
            SolarArPlacementController.DEFAULT_INITIAL_DISTANCE
        )
        placementController = SolarArPlacementController(initialDistance = distance)
    }

    private fun setupUi() {
        binding.btnBack.setOnClickListener { finishSafely() }
        binding.btnDone.setOnClickListener { finishSafely() }
        binding.btnClosePermission.setOnClickListener { finishSafely() }
        binding.btnCloseError.setOnClickListener { finishSafely() }
        binding.btnRetry.setOnClickListener { loadModel() }

        binding.btnMove.setOnClickListener { selectMode(InteractionMode.MOVE) }
        binding.btnRotate.setOnClickListener { selectMode(InteractionMode.ROTATE) }
        binding.btnReset.setOnClickListener {
            if (!modelReady) return@setOnClickListener
            modelPlaced = false
            useWorldLock = false
            worldOrigin = null
            worldYawDeg = 0f
            modelController?.setModelVisible(false)
            binding.reticle.isVisible = true
            binding.instructionText.setText(R.string.tap_to_place)
            binding.instructionText.isVisible = true
            setControlsEnabled(false)
            selectMode(InteractionMode.PLACE)
        }

        // Wait for GLB fit — do not show design-only dimensions (e.g. 16×192 ft)
        // as if they were the mesh size.
        binding.configSummary.isVisible = false

        setControlsEnabled(false)
        selectMode(InteractionMode.PLACE)
    }

    private fun setupSceneOverlay() {
        val sv = SolarArModelController.createTransparentSceneView(this, lifecycle).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        binding.sceneContainer.setBackgroundColor(Color.TRANSPARENT)
        binding.sceneContainer.addView(sv)
        sceneView = sv
        modelController = SolarArModelController(this, sv, lifecycleScope)
    }

    private fun setupGestures() {
        binding.gestureOverlay.setOnTouchListener { _, event ->
            if (!modelReady) return@setOnTouchListener false
            handleGesture(event)
        }
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        val controller = placementController
        val model = modelController ?: return false
        val w = binding.gestureOverlay.width
        val h = binding.gestureOverlay.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                totalDragDistance = 0f
                isDragging = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                totalDragDistance += abs(dx) + abs(dy)
                val fov = model.verticalFovDegrees(w, h)

                when (interactionMode) {
                    InteractionMode.MOVE -> {
                        if (modelPlaced) {
                            if (useWorldLock) {
                                moveWorldByScreenDelta(dx, dy, w, h)
                            } else {
                                model.applyPose(controller.moveByScreenDelta(dx, dy, w, h, fov))
                            }
                        }
                    }
                    InteractionMode.ROTATE -> {
                        if (modelPlaced) {
                            if (useWorldLock) {
                                worldYawDeg += dx * 0.35f
                                worldOrigin?.let { origin ->
                                    model.applyWorldLocked(worldTracker, origin, worldYawDeg)
                                }
                            } else {
                                model.applyPose(controller.rotateByScreenDelta(dx, w, h, fov))
                            }
                        }
                    }
                    InteractionMode.PLACE -> Unit
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return true
                isDragging = false
                val wasTap = totalDragDistance < TAP_SLOP_PX
                if (wasTap &&
                    (interactionMode == InteractionMode.PLACE || interactionMode == InteractionMode.MOVE)
                ) {
                    placeOnDetectedSurface(event.x, event.y, w, h)
                }
                return true
            }
        }
        return false
    }

    private fun revealModelOverlay() {
        modelPlaced = true
        modelController?.setModelVisible(true)
        binding.reticle.isVisible = false
        binding.instructionText.setText(R.string.tap_to_reposition)
        binding.instructionText.isVisible = true
        setControlsEnabled(true)
    }

    private fun currentAspect(width: Int, height: Int): Float {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return w / h
    }

    private fun syncFov(width: Int, height: Int): Float {
        val previewW = binding.cameraPreview.width.takeIf { it > 0 } ?: width
        val previewH = binding.cameraPreview.height.takeIf { it > 0 } ?: height
        val cameraFov = SolarArCameraOptics.verticalFovFromRearCamera(
            boundCameraInfo,
            previewW,
            previewH,
        ) ?: SolarArCameraOptics.sceneViewDefaultVerticalFovDegrees()
        modelController?.syncSceneCamera(cameraFov)
        lastFov = cameraFov
        return cameraFov
    }

    private fun placeOnDetectedSurface(screenX: Float, screenY: Float, viewW: Int, viewH: Int) {
        val model = modelController ?: return
        val fov = syncFov(viewW, viewH)
        val aspect = currentAspect(viewW, viewH)
        val ndcX = (screenX / viewW.coerceAtLeast(1)) * 2f - 1f
        val ndcY = -((screenY / viewH.coerceAtLeast(1)) * 2f - 1f)

        val wanted = SolarSurfaceFitter.modulesWanted(
            solarConfig?.panelCount ?: SolarRooftopDims.BakedPanelCount,
        )

        if (worldTracker.isTracking) {
            val hit = worldTracker.hitTest(ndcX, ndcY, fov, aspect) ?: return
            val (frustumW, frustumH) = worldTracker.frustumSizeAtDistance(hit.distance, fov, aspect)
            val plan = SolarSurfaceFitter.fit(
                surfaceWidthM = frustumW * 0.90f,
                surfaceDepthM = frustumH * 0.58f,
                moduleWidthM = model.meshWidthMeters,
                moduleDepthM = model.meshDepthMeters,
                modulesWanted = wanted,
            )
            worldOrigin = hit.point
            if (!modelPlaced) {
                worldYawDeg = 0f
            }
            useWorldLock = true
            model.applySurfacePlan(plan)
            model.applyWorldLocked(worldTracker, hit.point, worldYawDeg)
            revealModelOverlay()
            updateConfigChipFromPlan(plan, fov, viewW, viewH)
            Log.i(
                TAG,
                "Placed on surface dist=${hit.distance} modules=${plan.count} " +
                    "grid=${plan.cols}x${plan.rows} frustum=${frustumW}x${frustumH}",
            )
            return
        }

        useWorldLock = false
        val pose = placementController.placeFromScreenTap(screenX, screenY, viewW, viewH, fov)
        val frustumW = model.frustumWidthMeters(pose.distance, viewW, viewH, fov)
        val frustumH = frustumW / aspect
        val plan = SolarSurfaceFitter.fit(
            surfaceWidthM = frustumW * 0.90f,
            surfaceDepthM = frustumH * 0.58f,
            moduleWidthM = model.meshWidthMeters,
            moduleDepthM = model.meshDepthMeters,
            modulesWanted = wanted,
        )
        model.applySurfacePlan(plan)
        model.applyPose(pose)
        revealModelOverlay()
        updateConfigChipFromPlan(plan, fov, viewW, viewH)
    }

    private fun moveWorldByScreenDelta(dx: Float, dy: Float, viewW: Int, viewH: Int) {
        val origin = worldOrigin ?: return
        val model = modelController ?: return
        val fov = lastFov
        val aspect = currentAspect(viewW, viewH)
        val distance = origin.length().coerceAtLeast(0.5f)
        val (frustumW, frustumH) = worldTracker.frustumSizeAtDistance(distance, fov, aspect)
        val metersPerPxX = frustumW / viewW.coerceAtLeast(1)
        val metersPerPxY = frustumH / viewH.coerceAtLeast(1)
        val basis = worldTracker.cameraBasis()
        val dummyHit = SolarWorldTracker.Hit(origin, distance, worldTracker.worldUp())
        val (right, along) = worldTracker.planeAxes(dummyHit, basis.forward)
        worldOrigin = origin
            .plus(right.times(dx * metersPerPxX))
            .minus(along.times(dy * metersPerPxY))
        worldOrigin?.let { model.applyWorldLocked(worldTracker, it, worldYawDeg) }
    }

    private fun selectMode(mode: InteractionMode) {
        interactionMode = mode
        styleModeButton(binding.btnMove, mode == InteractionMode.MOVE)
        styleModeButton(binding.btnRotate, mode == InteractionMode.ROTATE)
    }

    private fun styleModeButton(button: MaterialButton, selected: Boolean) {
        button.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.solar_teal else R.color.solar_control_bg
            )
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnMove.isEnabled = enabled
        binding.btnRotate.isEnabled = enabled
        binding.btnReset.isEnabled = enabled
    }

    private fun ensureCameraPermissionThenStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCameraThenModel()
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        binding.permissionPanel.isVisible = true
        binding.statusPanel.isVisible = false
        binding.instructionText.isVisible = false
        binding.bottomBar.isVisible = false
    }

    private fun startCameraThenModel() {
        if (cameraStarted) {
            if (modelController?.isLoaded != true) {
                loadModel()
            }
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                if (cameraStarted) return@addListener
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                bindCameraUseCases(provider)
                cameraStarted = true
                Log.i(TAG, "Rear camera preview started")
                binding.statusPanel.isVisible = false
                binding.instructionText.isVisible = true
                loadModel()
            } catch (t: Throwable) {
                cameraStarted = false
                Log.e(TAG, "Camera initialization failed", t)
                showError("Unable to start camera. Check permission and try again.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        // PERFORMANCE SurfaceView pairs with SceneView media-overlay SurfaceView.
        binding.cameraPreview.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE
        binding.cameraPreview.scaleType =
            androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

        val preview = Preview.Builder().build().also { useCase ->
            useCase.surfaceProvider = binding.cameraPreview.surfaceProvider
        }
        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
        )
        boundCameraInfo = camera.cameraInfo
        binding.cameraPreview.elevation = 0f
        binding.sceneContainer.elevation = 2f
        binding.gestureOverlay.elevation = 4f
        binding.previewContainer.bringChildToFront(binding.sceneContainer)
        binding.previewContainer.bringChildToFront(binding.gestureOverlay)
    }

    private fun loadModel() {
        val controller = modelController ?: return
        modelReady = false
        modelPlaced = false
        setControlsEnabled(false)
        showLoading()

        controller.load(modelLocation) { result ->
            runOnUiThread {
                result.onSuccess { onModelLoaded() }
                    .onFailure { error ->
                        Log.e(TAG, "Model load failed", error)
                        binding.statusPanel.isVisible = false
                        showError(getString(R.string.unable_to_load))
                    }
            }
        }
    }

    private fun onModelLoaded() {
        val controller = modelController ?: return
        controller.applyDesignConfig(solarConfig)
        controller.setModelVisible(false)

        val overlay = binding.gestureOverlay
        fun afterLayout() {
            val w = overlay.width.takeIf { it > 0 } ?: binding.previewContainer.width
            val h = overlay.height.takeIf { it > 0 } ?: binding.previewContainer.height
            syncFov(w, h)
            modelReady = true
            modelPlaced = false
            binding.statusPanel.isVisible = false
            binding.reticle.isVisible = true
            binding.instructionText.setText(R.string.tap_to_place)
            binding.instructionText.isVisible = true
            setControlsEnabled(false)
            selectMode(InteractionMode.PLACE)
            binding.topBar.bringToFront()
            binding.bottomBar.bringToFront()
            binding.configSummary.bringToFront()
            binding.instructionText.bringToFront()
            binding.reticle.bringToFront()
            binding.gestureOverlay.bringToFront()
            startFrameLoop()
            Log.i(TAG, "3D GLB ready — waiting for surface tap")
        }

        if (overlay.width > 0 && overlay.height > 0) {
            afterLayout()
        } else {
            overlay.post { afterLayout() }
        }
    }

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

    private fun formatMeters(meters: Float): String = String.format("%.1f", meters)

    private fun updateConfigChipFromPlan(
        plan: SolarSurfacePlan,
        fov: Float,
        viewW: Int,
        viewH: Int,
    ) {
        val controller = modelController ?: return
        val layout = controller.activeLayout
        val glbW = formatMeters(controller.meshWidthMeters)
        val glbD = formatMeters(controller.meshDepthMeters)
        val detail = buildString {
            if (layout != null) {
                val spec = layout.spec
                append("${spec.kw} kW · ${spec.panelCount} wanted")
                append(" · placed ${plan.panelsPlaced} panels")
                append(" · ${plan.cols}×${plan.rows} arrays")
                append(" · tilt ${String.format("%.0f", layout.tiltDeg)}°")
            } else {
                append("${glbW}×${glbD} m GLB · ${plan.panelsPlaced} panels")
            }
            solarConfig?.panelPower?.let { watts ->
                append(" · ${watts}W")
            }
        }
        binding.configSummary.text = detail
        binding.configSummary.isVisible = true
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning || resourcesReleased) return
            onArFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun onArFrame() {
        if (!modelReady || !::worldTracker.isInitialized || !worldTracker.isTracking) return
        val model = modelController ?: return
        val w = binding.gestureOverlay.width
        val h = binding.gestureOverlay.height
        if (w <= 0 || h <= 0) return
        if (modelPlaced) {
            val origin = worldOrigin ?: return
            if (useWorldLock) {
                model.applyWorldLocked(worldTracker, origin, worldYawDeg)
            }
        } else {
            val fov = lastFov
            val hit = worldTracker.hitTest(0f, 0f, fov, currentAspect(w, h))
            if (hit != null) {
                model.showGhostOnHit(worldTracker, hit, worldYawDeg)
                binding.instructionText.setText(R.string.scanning_surface)
            }
        }
    }

    private fun showLoading() {
        binding.statusPanel.isVisible = true
        binding.loadingIndicator.isVisible = true
        binding.statusMessage.setText(R.string.loading_solar_design)
        binding.errorActions.isVisible = false
    }

    private fun showError(message: String) {
        binding.statusPanel.isVisible = true
        binding.loadingIndicator.isVisible = false
        binding.statusMessage.text = message
        binding.errorActions.isVisible = true
        binding.instructionText.isVisible = false
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishSafely()
            }
        })
    }

    private fun finishSafely() {
        cleanupResources()
        finish()
    }

    private fun cleanupResources() {
        if (resourcesReleased) return
        resourcesReleased = true
        cameraStarted = false
        stopFrameLoop()
        if (::worldTracker.isInitialized) {
            worldTracker.stop()
        }

        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "Camera unbind failed", t)
        }
        cameraProvider = null
        boundCameraInfo = null

        modelController?.destroy()
        modelController = null
        runCatching { sceneView?.destroy() }
        sceneView = null
        if (::binding.isInitialized) {
            binding.sceneContainer.removeAllViews()
        }
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CustomSolarAr"
        private const val TAP_SLOP_PX = 24f

        const val EXTRA_GLB_PATH = "EXTRA_GLB_PATH"
        const val EXTRA_MODEL_URI = "EXTRA_MODEL_URI"
        const val EXTRA_INITIAL_DISTANCE = "EXTRA_INITIAL_DISTANCE"

        fun createIntent(
            context: Context,
            glbPath: String? = null,
            modelUri: Uri? = null,
            config: SolarArConfig? = null,
            initialDistance: Float = SolarArPlacementController.DEFAULT_INITIAL_DISTANCE,
        ): Intent {
            return Intent(context, CustomSolarArActivity::class.java).apply {
                glbPath?.let { putExtra(EXTRA_GLB_PATH, it) }
                modelUri?.let { putExtra(EXTRA_MODEL_URI, it) }
                SolarArConfig.writeToIntent(this, config)
                putExtra(EXTRA_INITIAL_DISTANCE, initialDistance)
            }
        }
    }
}
