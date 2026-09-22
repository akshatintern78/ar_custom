package com.example.ar_cust.solar

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Packs copies of the baked rooftop GLB onto the visible surface patch.
 *
 * The provided asset is a 6×1 array (~3 kW). We instance that whole 3D mesh
 * (never a flat image) according to design panel count and how much roof
 * is visible at the hit.
 */
data class SolarSurfacePlan(
    val cols: Int,
    val rows: Int,
    val count: Int,
    val centers: List<Pair<Float, Float>>,
    val pitchX: Float,
    val pitchZ: Float,
    val footprintW: Float,
    val footprintL: Float,
) {
    val modulesPlaced: Int get() = count
    val panelsPlaced: Int get() = count * SolarRooftopDims.BakedPanelCount
}

object SolarSurfaceFitter {

    fun modulesWanted(panelCount: Int): Int {
        val panels = panelCount.coerceIn(1, SolarRooftopDims.MaxPanels)
        return ceil(panels.toFloat() / SolarRooftopDims.BakedPanelCount).toInt()
            .coerceIn(1, MAX_MODULES)
    }

    fun fit(
        surfaceWidthM: Float,
        surfaceDepthM: Float,
        moduleWidthM: Float,
        moduleDepthM: Float,
        modulesWanted: Int,
    ): SolarSurfacePlan {
        val wanted = modulesWanted.coerceIn(1, MAX_MODULES)
        val gap = SolarRooftopDims.Gap
        val pitchX = (moduleWidthM + gap).coerceAtLeast(0.2f)
        val pitchZ = (moduleDepthM + gap).coerceAtLeast(0.2f)

        val maxCols = floor((surfaceWidthM + gap) / pitchX).toInt().coerceAtLeast(1)
        val maxRows = floor((surfaceDepthM + gap) / pitchZ).toInt().coerceAtLeast(1)
        val capacity = (maxCols * maxRows).coerceAtLeast(1)

        val count = min(wanted, min(capacity, MAX_MODULES)).coerceAtLeast(1)
        val cols: Int
        val rows: Int
        if (maxCols >= count) {
            cols = count
            rows = 1
        } else {
            cols = maxCols.coerceAtLeast(1)
            rows = max(1, ceil(count.toFloat() / cols).toInt())
        }
        val placed = min(count, cols * rows)

        val originX = (cols - 1) * pitchX / 2f
        val originZ = (rows - 1) * pitchZ / 2f
        val centers = buildList {
            var n = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    if (n >= placed) break
                    add((col * pitchX - originX) to (row * pitchZ - originZ))
                    n++
                }
            }
        }
        return SolarSurfacePlan(
            cols = cols,
            rows = rows,
            count = placed,
            centers = centers,
            pitchX = pitchX,
            pitchZ = pitchZ,
            footprintW = cols * moduleWidthM + (cols - 1) * gap,
            footprintL = rows * moduleDepthM + (rows - 1) * gap,
        )
    }

    const val MAX_MODULES = 6
}
