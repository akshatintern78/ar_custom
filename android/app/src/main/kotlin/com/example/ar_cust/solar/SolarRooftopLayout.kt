package com.example.ar_cust.solar

import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Custom rooftop-array layout math (same approach as Solar 360's designer,
 * but fully independent — no Google Solar API / Scene Viewer / Maps).
 *
 * Synthetic pad + tilted array plane from north/south post heights.
 * Panel count rule: [kw] × 2 (capped), cols = panelCount / rows.
 */
object SolarRooftopDims {
    /** Authored single-panel size used when design metadata has no width/length. */
    const val PanelW = 1.2192f
    const val PanelL = 2.286f
    const val Gap = 0.025f
    const val MaxPanels = 10
    const val MaxKw = 5
    const val MinKw = 2
    const val SetupBufferFt = 1f
    const val FtToM = 0.3048f
    const val MToFt = 3.28084f
    val SetupBufferM = SetupBufferFt * FtToM

    /** Baked asset is a single 6-panel row (solar_3kw_6x1_rooftop.glb). */
    const val BakedAsset = "models/solar_3kw_6x1_rooftop.glb"
    const val BakedPanelCount = 6
    const val BakedCols = 6
    const val BakedRows = 1
}

object SolarHeightLimits {
    const val MinFt = 1f
    const val MaxFt = 13f
    const val DefaultSouthFt = 2f
    const val DefaultNorthFt = 5f
    const val MaxSlopeSin = 0.98f

    val MinM = MinFt * SolarRooftopDims.FtToM
    val MaxM = MaxFt * SolarRooftopDims.FtToM
    val DefaultSouthM = DefaultSouthFt * SolarRooftopDims.FtToM
    val DefaultNorthM = DefaultNorthFt * SolarRooftopDims.FtToM

    fun clampM(meters: Float): Float = meters.coerceIn(MinM, MaxM)

    fun maxDeltaM(arrayL: Float): Float = arrayL * MaxSlopeSin

    fun clampPair(southM: Float, northM: Float, arrayL: Float): Pair<Float, Float> {
        val south = clampM(southM)
        val maxD = maxDeltaM(arrayL)
        val north = clampM(northM).coerceIn(south - maxD, south + maxD)
        return south to north
    }
}

data class SolarRooftopSpec(
    val kw: Int = 3,
    val rows: Int = 1,
    /** Front / lower edge post height (meters). */
    val southHeightM: Float = SolarHeightLimits.DefaultSouthM,
    /** Back / higher edge post height (meters). */
    val northHeightM: Float = SolarHeightLimits.DefaultNorthM,
    val panelW: Float = SolarRooftopDims.PanelW,
    val panelL: Float = SolarRooftopDims.PanelL,
    val panelWatts: Int = 500,
) {
    val panelCount: Int
        get() = (kw.coerceIn(SolarRooftopDims.MinKw, SolarRooftopDims.MaxKw) * 2)
            .coerceAtMost(SolarRooftopDims.MaxPanels)

    val cols: Int
        get() = (panelCount / rows.coerceIn(1, 2)).coerceAtLeast(1)

    val totalKw: Float
        get() = panelCount * panelWatts / 1000f

    val arrayL: Float
        get() {
            val r = rows.coerceIn(1, 2)
            return r * panelL + (r - 1).coerceAtLeast(0) * SolarRooftopDims.Gap
        }
}

data class SolarRooftopLayout(
    val spec: SolarRooftopSpec,
    val arrayW: Float,
    val arrayL: Float,
    val yLift: Float,
    val cosT: Float,
    val sinT: Float,
    val tiltDeg: Float,
    val panelCenters: List<Pair<Float, Float>>,
    val footprintW: Float,
    val footprintL: Float,
) {
    /** Slope-local (x, yLocal, s) → world meters. s runs south→north along panel length. */
    fun slopeToWorld(x: Float, yLocal: Float, s: Float): Triple<Float, Float, Float> {
        return Triple(
            x,
            yLocal * cosT + s * -sinT + yLift,
            yLocal * sinT + s * cosT,
        )
    }
}

object SolarRooftopLayoutEngine {

    fun fromConfig(config: SolarArConfig?): SolarRooftopSpec {
        val kw = (config?.systemSize?.toInt() ?: 3)
            .coerceIn(SolarRooftopDims.MinKw, SolarRooftopDims.MaxKw)
        val rows = parseRows(config?.alignment)
        val panelW = resolvePanelWidthM(config)
        val panelL = resolvePanelLengthM(config, rows)
        val watts = config?.panelPower?.coerceAtLeast(50) ?: 500
        val southM = (config?.southHeight?.toFloat()?.let { it * SolarRooftopDims.FtToM }
            ?: SolarHeightLimits.DefaultSouthM)
        val northM = (config?.northHeight?.toFloat()?.let { it * SolarRooftopDims.FtToM }
            ?: SolarHeightLimits.DefaultNorthM)
        val arrayL = rows * panelL + (rows - 1).coerceAtLeast(0) * SolarRooftopDims.Gap
        val (south, north) = SolarHeightLimits.clampPair(southM, northM, arrayL)
        return SolarRooftopSpec(
            kw = kw,
            rows = rows,
            southHeightM = south,
            northHeightM = north,
            panelW = panelW,
            panelL = panelL,
            panelWatts = watts,
        )
    }

    fun compute(spec: SolarRooftopSpec): SolarRooftopLayout {
        val cols = spec.cols
        val rows = spec.rows.coerceIn(1, 2)
        val panelW = spec.panelW.coerceAtLeast(0.2f)
        val panelL = spec.panelL.coerceAtLeast(0.2f)
        val arrayW = cols * panelW + (cols - 1) * SolarRooftopDims.Gap
        val arrayL = spec.arrayL
        val (south, north) = SolarHeightLimits.clampPair(
            spec.southHeightM,
            spec.northHeightM,
            arrayL,
        )
        val sinT = ((north - south) / arrayL).coerceIn(
            -SolarHeightLimits.MaxSlopeSin,
            SolarHeightLimits.MaxSlopeSin,
        )
        val cosT = sqrt((1f - sinT * sinT).coerceAtLeast(0f))
        val yLift = (north + south) / 2f
        val tiltDeg = Math.toDegrees(asin(sinT.toDouble())).toFloat()

        val pitchX = panelW + SolarRooftopDims.Gap
        val pitchS = panelL + SolarRooftopDims.Gap
        val originX = (cols - 1) * pitchX / 2f
        val originS = (rows - 1) * pitchS / 2f
        val centers = buildList {
            for (row in 0 until rows) {
                val cs = originS - row * pitchS
                for (col in 0 until cols) {
                    add((col * pitchX - originX) to cs)
                }
            }
        }

        return SolarRooftopLayout(
            spec = spec.copy(
                southHeightM = south,
                northHeightM = north,
                rows = rows,
            ),
            arrayW = arrayW,
            arrayL = arrayL,
            yLift = yLift,
            cosT = cosT,
            sinT = sinT,
            tiltDeg = tiltDeg,
            panelCenters = centers,
            footprintW = arrayW + SolarRooftopDims.SetupBufferM,
            footprintL = arrayL * cosT + SolarRooftopDims.SetupBufferM,
        )
    }

    fun computeFromConfig(config: SolarArConfig?): SolarRooftopLayout =
        compute(fromConfig(config))

    /**
     * Presentation pitch for camera AR: design tilt (Solar 360 style) with a
     * minimum so the array still reads as a roof when heights are nearly flat.
     */
    fun viewPitchDegrees(layout: SolarRooftopLayout): Float {
        return max(18f, min(55f, kotlin.math.abs(layout.tiltDeg) + 12f))
    }

    private fun parseRows(alignment: String?): Int {
        if (alignment.isNullOrBlank()) return 1
        val lower = alignment.lowercase()
        // "3x2", "6x1", "2 rows", etc.
        val xParts = lower.split('x', '×')
        if (xParts.size >= 2) {
            val rows = xParts[1].trim().takeWhile { it.isDigit() }.toIntOrNull()
            if (rows != null) return rows.coerceIn(1, 2)
        }
        if (lower.contains("2") && lower.contains("row")) return 2
        return 1
    }

    private fun resolvePanelWidthM(config: SolarArConfig?): Float {
        val wFt = config?.width
        val count = config?.panelCount?.coerceAtLeast(1)
            ?: ((config?.systemSize?.toInt() ?: 3) * 2)
        val rows = parseRows(config?.alignment)
        val cols = (count / rows).coerceAtLeast(1)
        return if (wFt != null && wFt > 0.0) {
            // Config width is full array width in feet → per-panel meters.
            ((wFt * SolarRooftopDims.FtToM).toFloat() / cols)
                .coerceIn(0.6f, 2.5f)
        } else {
            SolarRooftopDims.PanelW
        }
    }

    private fun resolvePanelLengthM(config: SolarArConfig?, rows: Int): Float {
        val lFt = config?.length
        return if (lFt != null && lFt > 0.0) {
            // Config length is full array length in feet → per-panel meters.
            ((lFt * SolarRooftopDims.FtToM).toFloat() / rows.coerceAtLeast(1))
                .coerceIn(0.8f, 3.5f)
        } else {
            SolarRooftopDims.PanelL
        }
    }
}
