package com.example.ar_cust.solar

/**
 * Optional solar design metadata passed into [CustomSolarArActivity].
 * Passed via Intent extras (no Parcelize — avoids classpath conflicts).
 */
data class SolarArConfig(
    val systemSize: Double? = null,
    val panelCount: Int? = null,
    val panelPower: Int? = null,
    val alignment: String? = null,
    val width: Double? = null,
    val length: Double? = null,
    val southHeight: Double? = null,
    val northHeight: Double? = null,
    val slope: Double? = null,
    val direction: String? = null,
    val units: String? = null,
    val label: String? = null,
) {

    fun summaryLine(): String? {
        val parts = buildList {
            systemSize?.let { add("${trimNum(it)} kW") }
            panelCount?.let { add("$it panels") }
            slope?.let { s ->
                val dir = direction?.takeIf { it.isNotBlank() }.orEmpty()
                add("${trimNum(s)}°${if (dir.isNotEmpty()) " $dir" else ""}")
            }
            alignment?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun trimNum(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    companion object {
        private const val P_SYSTEM_SIZE = "cfg_systemSize"
        private const val P_PANEL_COUNT = "cfg_panelCount"
        private const val P_PANEL_POWER = "cfg_panelPower"
        private const val P_ALIGNMENT = "cfg_alignment"
        private const val P_WIDTH = "cfg_width"
        private const val P_LENGTH = "cfg_length"
        private const val P_SOUTH = "cfg_southHeight"
        private const val P_NORTH = "cfg_northHeight"
        private const val P_SLOPE = "cfg_slope"
        private const val P_DIRECTION = "cfg_direction"
        private const val P_UNITS = "cfg_units"
        private const val P_LABEL = "cfg_label"
        private const val P_HAS_CONFIG = "cfg_hasConfig"

        fun writeToIntent(intent: android.content.Intent, config: SolarArConfig?) {
            if (config == null) {
                intent.putExtra(P_HAS_CONFIG, false)
                return
            }
            intent.putExtra(P_HAS_CONFIG, true)
            config.systemSize?.let { intent.putExtra(P_SYSTEM_SIZE, it) }
            config.panelCount?.let { intent.putExtra(P_PANEL_COUNT, it) }
            config.panelPower?.let { intent.putExtra(P_PANEL_POWER, it) }
            config.alignment?.let { intent.putExtra(P_ALIGNMENT, it) }
            config.width?.let { intent.putExtra(P_WIDTH, it) }
            config.length?.let { intent.putExtra(P_LENGTH, it) }
            config.southHeight?.let { intent.putExtra(P_SOUTH, it) }
            config.northHeight?.let { intent.putExtra(P_NORTH, it) }
            config.slope?.let { intent.putExtra(P_SLOPE, it) }
            config.direction?.let { intent.putExtra(P_DIRECTION, it) }
            config.units?.let { intent.putExtra(P_UNITS, it) }
            config.label?.let { intent.putExtra(P_LABEL, it) }
        }

        fun readFromIntent(intent: android.content.Intent): SolarArConfig? {
            if (!intent.getBooleanExtra(P_HAS_CONFIG, false)) return null
            fun has(key: String) = intent.hasExtra(key)
            return SolarArConfig(
                systemSize = if (has(P_SYSTEM_SIZE)) intent.getDoubleExtra(P_SYSTEM_SIZE, 0.0) else null,
                panelCount = if (has(P_PANEL_COUNT)) intent.getIntExtra(P_PANEL_COUNT, 0) else null,
                panelPower = if (has(P_PANEL_POWER)) intent.getIntExtra(P_PANEL_POWER, 0) else null,
                alignment = intent.getStringExtra(P_ALIGNMENT),
                width = if (has(P_WIDTH)) intent.getDoubleExtra(P_WIDTH, 0.0) else null,
                length = if (has(P_LENGTH)) intent.getDoubleExtra(P_LENGTH, 0.0) else null,
                southHeight = if (has(P_SOUTH)) intent.getDoubleExtra(P_SOUTH, 0.0) else null,
                northHeight = if (has(P_NORTH)) intent.getDoubleExtra(P_NORTH, 0.0) else null,
                slope = if (has(P_SLOPE)) intent.getDoubleExtra(P_SLOPE, 0.0) else null,
                direction = intent.getStringExtra(P_DIRECTION),
                units = intent.getStringExtra(P_UNITS),
                label = intent.getStringExtra(P_LABEL),
            )
        }
    }
}
