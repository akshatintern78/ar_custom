package com.example.ar_cust

import com.example.ar_cust.solar.CustomSolarArActivity
import com.example.ar_cust.solar.SolarArConfig
import com.example.ar_cust.solar.SolarArModelController
import com.example.ar_cust.solar.SolarArPlacementController
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter ↔ native bridge for custom Solar AR (no Google ARCore).
 *
 * Channel: `com.example.ar_cust/solar_ar`
 * Method:  `openSolarAr` with optional design map.
 */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "openSolarAr" -> {
                    @Suppress("UNCHECKED_CAST")
                    val args = call.arguments as? Map<String, Any?>
                    openSolarAr(args)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun openSolarAr(args: Map<String, Any?>?) {
        val glbPath = (args?.get("glbPath") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: SolarArModelController.DEFAULT_ASSET_PATH

        val initialDistance = (args?.get("initialDistance") as? Number)?.toFloat()
            ?: SolarArPlacementController.DEFAULT_INITIAL_DISTANCE

        val config = args?.let { map ->
            SolarArConfig(
                systemSize = (map["systemSize"] as? Number)?.toDouble(),
                panelCount = (map["panelCount"] as? Number)?.toInt(),
                panelPower = (map["panelPower"] as? Number)?.toInt(),
                alignment = map["alignment"] as? String,
                width = (map["width"] as? Number)?.toDouble(),
                length = (map["length"] as? Number)?.toDouble(),
                southHeight = (map["southHeight"] as? Number)?.toDouble(),
                northHeight = (map["northHeight"] as? Number)?.toDouble(),
                slope = (map["slope"] as? Number)?.toDouble(),
                direction = map["direction"] as? String,
                units = map["units"] as? String,
                label = map["label"] as? String,
            )
        }

        startActivity(
            CustomSolarArActivity.createIntent(
                context = this,
                glbPath = glbPath,
                config = config,
                initialDistance = initialDistance,
            )
        )
    }

    companion object {
        private const val CHANNEL = "com.example.ar_cust/solar_ar"
    }
}
