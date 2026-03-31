package com.sensorpioneer.logic

import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 惑星環境の真値モデル。
 * UIやセンサーのノイズが乗る前の「実際の環境」を表す。
 */
data class EnvironmentModel(
    val temperatureCelsius: Double,
    val pressureHpa: Double,
    val humidityPercent: Double
)

/**
 * 装備の許容範囲と劣化係数。
 * k は D_loss = k * (V_env - V_tol)^2 に使用する物理係数。
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

/**
 * センサー観測値。
 * trueEnvironment はノイズ付与前の真値を保持し、デバッグや検証時に利用できる。
 */
data class SensorReading(
    val measured: EnvironmentModel,
    val trueEnvironment: EnvironmentModel
)

/**
 * 許容範囲逸脱の詳細。
 */
data class ThresholdBreach(
    val metric: Metric,
    val measuredValue: Double,
    val toleranceBoundary: Double,
    val deltaFromBoundary: Double,
    val degradationLoss: Double,
    val warning: Boolean,
    val critical: Boolean
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

/**
 * UIへ公開する状態。
 */
data class SurvivalUiState(
    val sensorReading: SensorReading? = null,
    val durability: Double = 100.0,
    val totalDurabilityLoss: Double = 0.0,
    val warning: Boolean = false,
    val event: SurvivalEvent = SurvivalEvent.NONE,
    val breaches: List<ThresholdBreach> = emptyList()
)

/**
 * 仮想センサー。
 * precision が 1.0 に近いほどノイズが小さく、0.0 に近いほどノイズが大きい。
 */
class VirtualSensor(
    private val precision: Double,
    private val random: Random = Random.Default
) {
    init {
        require(precision in 0.0..1.0) { "precision must be in 0.0..1.0" }
    }

    fun sample(environment: EnvironmentModel): SensorReading {
        val noiseScale = 1.0 - precision
        val measured = EnvironmentModel(
            temperatureCelsius = environment.temperatureCelsius + boundedNoise(temperatureBand() * noiseScale),
            pressureHpa = environment.pressureHpa + boundedNoise(pressureBand() * noiseScale),
            humidityPercent = (environment.humidityPercent + boundedNoise(humidityBand() * noiseScale))
                .coerceIn(0.0, 100.0)
        )

        return SensorReading(
            measured = measured,
            trueEnvironment = environment
        )
    }

    private fun boundedNoise(maxAbs: Double): Double {
        if (maxAbs <= 0.0) return 0.0
        return random.nextDouble(from = -maxAbs, until = maxAbs)
    }

    // 惑星探査向けに、温度・気圧・湿度それぞれ異なる観測揺らぎ帯を仮定する。
    private fun temperatureBand(): Double = 4.0
    private fun pressureBand(): Double = 30.0
    private fun humidityBand(): Double = 8.0
}

/**
 * 装備耐久を計算する生存ロジック。
 */
class SurvivalLogic(private val equipment: Equipment) {

    fun evaluate(
        measuredEnvironment: EnvironmentModel,
        currentDurability: Double
    ): EvaluationResult {
        val breaches = buildList {
            evaluateMetric(
                metric = Metric.TEMPERATURE,
                value = measuredEnvironment.temperatureCelsius,
                minTolerance = equipment.minTemperatureCelsius,
                maxTolerance = equipment.maxTemperatureCelsius,
                k = equipment.temperatureK
            )?.let(::add)

            evaluateMetric(
                metric = Metric.PRESSURE,
                value = measuredEnvironment.pressureHpa,
                minTolerance = equipment.minPressureHpa,
                maxTolerance = equipment.maxPressureHpa,
                k = equipment.pressureK
            )?.let(::add)

            evaluateMetric(
                metric = Metric.HUMIDITY,
                value = measuredEnvironment.humidityPercent,
                minTolerance = equipment.minHumidityPercent,
                maxTolerance = equipment.maxHumidityPercent,
                k = equipment.humidityK
            )?.let(::add)
        }

        val loss = breaches.sumOf { it.degradationLoss }
        val nextDurability = (currentDurability - loss).coerceAtLeast(0.0)
        val warning = breaches.any { it.warning }
        val criticalByTolerance = breaches.any { it.critical }
        val criticalByDurability = nextDurability <= 0.0

        return EvaluationResult(
            nextDurability = nextDurability,
            durabilityLoss = loss,
            breaches = breaches,
            warning = warning,
            event = if (criticalByTolerance || criticalByDurability) {
                SurvivalEvent.CRITICAL_FAILURE
            } else {
                SurvivalEvent.NONE
            }
        )
    }

    private fun evaluateMetric(
        metric: Metric,
        value: Double,
        minTolerance: Double,
        maxTolerance: Double,
        k: Double
    ): ThresholdBreach? {
        val boundary = when {
            value < minTolerance -> minTolerance
            value > maxTolerance -> maxTolerance
            else -> return null
        }

        val delta = value - boundary

        // 要件式:
        // D_loss = k * (V_env - V_tol)^2
        // 差分を2乗することで、逸脱が小さい間は緩やか、
        // 大きく逸脱したときは急激にダメージが増える。
        val durabilityLoss = k * delta.pow(2)

        // 「許容限界を50%以上超える」判定:
        // 上限超えなら value >= max * 1.5
        // 下限割れなら value <= min * 0.5（正の値を想定）
        val critical = when {
            value > maxTolerance -> value >= maxTolerance * 1.5
            value < minTolerance -> value <= minTolerance * 0.5
            else -> false
        }

        return ThresholdBreach(
            metric = metric,
            measuredValue = value,
            toleranceBoundary = boundary,
            deltaFromBoundary = delta,
            degradationLoss = durabilityLoss,
            warning = true,
            critical = critical
        )
    }
}

data class EvaluationResult(
    val nextDurability: Double,
    val durabilityLoss: Double,
    val breaches: List<ThresholdBreach>,
    val warning: Boolean,
    val event: SurvivalEvent
)

/**
 * MVVM の ViewModel 相当クラス。
 * AndroidX ViewModel を直接継承しない純粋 Kotlin 実装にしており、
 * Android Studio プロジェクトへそのまま組み込んで利用しやすい形にしている。
 */
class SurvivalViewModel(
    equipment: Equipment,
    private val sensor: VirtualSensor
) {
    private val logic = SurvivalLogic(equipment)
    private val maxDurability = equipment.maxDurability

    private val _uiState = MutableStateFlow(
        SurvivalUiState(durability = equipment.maxDurability)
    )
    val uiState: StateFlow<SurvivalUiState> = _uiState.asStateFlow()

    private val _criticalEvent = MutableSharedFlow<ThresholdBreach>(extraBufferCapacity = 1)
    val criticalEvent: SharedFlow<ThresholdBreach> = _criticalEvent.asSharedFlow()

    /**
     * リアルタイム更新用メソッド。
     * センサー観測→生存計算→StateFlow反映を1ステップで実行する。
     */
    fun pollAndUpdate(environment: EnvironmentModel) {
        val reading = sensor.sample(environment)
        val current = _uiState.value

        val result = logic.evaluate(
            measuredEnvironment = reading.measured,
            currentDurability = current.durability
        )

        val nextState = current.copy(
            sensorReading = reading,
            durability = result.nextDurability,
            totalDurabilityLoss = result.durabilityLoss,
            warning = result.warning,
            event = result.event,
            breaches = result.breaches
        )

        _uiState.value = nextState

        if (result.event == SurvivalEvent.CRITICAL_FAILURE) {
            result.breaches.firstOrNull { it.critical }?.let {
                _criticalEvent.tryEmit(it)
            }
        }
    }

    fun clearEvent() {
        _uiState.value = _uiState.value.copy(event = SurvivalEvent.NONE)
    }

    fun resetDurability() {
        _uiState.value = _uiState.value.copy(
            durability = maxDurability,
            totalDurabilityLoss = 0.0,
            warning = false,
            event = SurvivalEvent.NONE,
            breaches = emptyList()
        )
    }
}
