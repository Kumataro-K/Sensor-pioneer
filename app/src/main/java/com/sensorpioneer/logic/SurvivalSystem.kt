package com.sensorpioneer.logic

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow
import kotlin.random.Random

/**
 * 惑星の環境データ（センサーの真値）。
 */
data class EnvironmentData(
    val temperatureCelsius: Double,
    val pressureHpa: Double,
    val humidityPercent: Double
)

/**
 * 装備の許容範囲と耐久計算係数。
 */
data class Equipment(
    val name: String,
    val minTemperatureCelsius: Double,
    val maxTemperatureCelsius: Double,
    val minPressureHpa: Double,
    val maxPressureHpa: Double,
    val minHumidityPercent: Double,
    val maxHumidityPercent: Double,
    val maxDurability: Double = 100.0,
    val temperatureK: Double = 0.0008,
    val pressureK: Double = 0.0002,
    val humidityK: Double = 0.0004
)

enum class Metric {
    TEMPERATURE,
    PRESSURE,
    HUMIDITY
}

enum class SurvivalEvent {
    NONE,
    CRITICAL_FAILURE
}

data class ThresholdBreach(
    val metric: Metric,
    val measuredValue: Double,
    val toleranceMin: Double,
    val toleranceMax: Double,
    val toleranceBoundary: Double,
    val deltaFromBoundary: Double,
    val durabilityLoss: Double,
    val critical: Boolean
)

/**
 * センサーの計測結果（ノイズ適用後）
 */
data class SensorReading(
    val measured: EnvironmentData,
    val raw: EnvironmentData
)

data class SurvivalUiState(
    val sensorReading: SensorReading? = null,
    val durability: Double = 100.0,
    val warning: Boolean = false,
    val event: SurvivalEvent = SurvivalEvent.NONE,
    val breaches: List<ThresholdBreach> = emptyList(),
    val totalDurabilityLoss: Double = 0.0
)

/**
 * precision が 1.0 に近いほど高精度（ノイズ小）、0.0 に近いほど低精度（ノイズ大）。
 */
class VirtualSensor(
    private val precision: Double,
    private val random: Random = Random.Default
) {
    init {
        require(precision in 0.0..1.0) { "precision must be in 0.0..1.0" }
    }

    fun read(raw: EnvironmentData): SensorReading {
        val scale = 1.0 - precision

        val measured = EnvironmentData(
            temperatureCelsius = raw.temperatureCelsius + noise(4.0 * scale),
            pressureHpa = raw.pressureHpa + noise(30.0 * scale),
            humidityPercent = (raw.humidityPercent + noise(8.0 * scale)).coerceIn(0.0, 100.0)
        )

        return SensorReading(measured = measured, raw = raw)
    }

    private fun noise(maxAbs: Double): Double {
        if (maxAbs <= 0.0) return 0.0
        return random.nextDouble(-maxAbs, maxAbs)
    }
}

data class Evaluation(
    val nextDurability: Double,
    val totalLoss: Double,
    val warning: Boolean,
    val event: SurvivalEvent,
    val breaches: List<ThresholdBreach>
)

class SurvivalManager(private val equipment: Equipment) {

    fun evaluate(reading: SensorReading, currentDurability: Double): Evaluation {
        val breaches = buildList {
            evaluateMetric(
                metric = Metric.TEMPERATURE,
                value = reading.measured.temperatureCelsius,
                min = equipment.minTemperatureCelsius,
                max = equipment.maxTemperatureCelsius,
                k = equipment.temperatureK
            )?.let(::add)

            evaluateMetric(
                metric = Metric.PRESSURE,
                value = reading.measured.pressureHpa,
                min = equipment.minPressureHpa,
                max = equipment.maxPressureHpa,
                k = equipment.pressureK
            )?.let(::add)

            evaluateMetric(
                metric = Metric.HUMIDITY,
                value = reading.measured.humidityPercent,
                min = equipment.minHumidityPercent,
                max = equipment.maxHumidityPercent,
                k = equipment.humidityK
            )?.let(::add)
        }

        val loss = breaches.sumOf { it.durabilityLoss }
        val next = (currentDurability - loss).coerceAtLeast(0.0)
        val hasCriticalBreach = breaches.any { it.critical }
        val event = if (hasCriticalBreach || next <= 0.0) SurvivalEvent.CRITICAL_FAILURE else SurvivalEvent.NONE

        return Evaluation(
            nextDurability = next,
            totalLoss = loss,
            warning = breaches.isNotEmpty(),
            event = event,
            breaches = breaches
        )
    }

    private fun evaluateMetric(
        metric: Metric,
        value: Double,
        min: Double,
        max: Double,
        k: Double
    ): ThresholdBreach? {
        if (value in min..max) return null

        val boundary = if (value > max) max else min
        val delta = value - boundary

        // 物理モデル:
        // D_loss = k * (V_env - V_tol)^2
        // 逸脱幅が大きいほど損失が二次的に増加する。
        val loss = k * delta.pow(2)

        // 許容限界を50%以上超えたら致命的故障。
        val critical = if (value > max) {
            value >= max * 1.5
        } else {
            value <= min * 0.5
        }

        return ThresholdBreach(
            metric = metric,
            measuredValue = value,
            toleranceMin = min,
            toleranceMax = max,
            toleranceBoundary = boundary,
            deltaFromBoundary = delta,
            durabilityLoss = loss,
            critical = critical
        )
    }
}

/**
 * Android Studio の MVVM でそのまま利用できる ViewModel。
 * Fragment/Activity から pollAndUpdate() を呼ぶと StateFlow が更新される。
 */
class SurvivalViewModel(
    private val equipment: Equipment,
    private val sensor: VirtualSensor
) : ViewModel() {

    private val manager = SurvivalManager(equipment)

    private val _uiState = MutableStateFlow(
        SurvivalUiState(durability = equipment.maxDurability)
    )
    val uiState: StateFlow<SurvivalUiState> = _uiState.asStateFlow()

    private val _criticalFailure = MutableSharedFlow<ThresholdBreach>(extraBufferCapacity = 1)
    val criticalFailure: SharedFlow<ThresholdBreach> = _criticalFailure.asSharedFlow()

    /**
     * リアルタイムポーリングを模擬する更新メソッド。
     */
    fun pollAndUpdate(rawEnvironment: EnvironmentData) {
        val reading = sensor.read(rawEnvironment)
        val currentDurability = _uiState.value.durability

        val result = manager.evaluate(reading, currentDurability)

        _uiState.value = _uiState.value.copy(
            sensorReading = reading,
            durability = result.nextDurability,
            warning = result.warning,
            event = result.event,
            breaches = result.breaches,
            totalDurabilityLoss = result.totalLoss
        )

        if (result.event == SurvivalEvent.CRITICAL_FAILURE) {
            result.breaches.firstOrNull { it.critical }?.let { _criticalFailure.tryEmit(it) }
        }
    }

    fun clearEvent() {
        _uiState.value = _uiState.value.copy(event = SurvivalEvent.NONE)
    }

    fun resetDurability() {
        _uiState.value = _uiState.value.copy(
            durability = equipment.maxDurability,
            warning = false,
            event = SurvivalEvent.NONE,
            breaches = emptyList(),
            totalDurabilityLoss = 0.0
        )
    }
}
