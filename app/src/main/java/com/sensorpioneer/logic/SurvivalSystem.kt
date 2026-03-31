package com.sensorpioneer.logic

import kotlin.math.max

/**
 * 惑星の環境データ。
 */
data class EnvironmentData(
    val temperatureCelsius: Double,
    val pressureHpa: Double,
    val humidityPercent: Double
)

/**
 * 装備の各環境要素ごとの許容範囲。
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
    val durabilityDrainMultiplier: Double = 1.0
)

/**
 * 単一ポーリング時の計算結果。
 */
data class SurvivalStatus(
    val environment: EnvironmentData,
    val durability: Double,
    val totalDegradation: Double,
    val issues: List<ThresholdBreach>,
    val criticalFailure: Boolean
)

/**
 * 許容範囲超過の詳細。
 */
data class ThresholdBreach(
    val metric: Metric,
    val value: Double,
    val allowedMin: Double,
    val allowedMax: Double,
    val exceedRatio: Double,
    val degradationRate: Double
)

enum class Metric {
    TEMPERATURE,
    PRESSURE,
    HUMIDITY
}

/**
 * 致命的故障イベント。
 */
data class CriticalFailureEvent(
    val metric: Metric,
    val value: Double,
    val exceedRatio: Double,
    val message: String
)

/**
 * センサーポーリングとUI更新を受け取るインターフェース。
 */
interface SensorPollingInterface {
    /**
     * センサー値をポーリングして、内部状態更新とUIへの通知を行う。
     */
    fun pollAndUpdate(environmentData: EnvironmentData)

    /**
     * 最新の計算結果を返す（未実行時は null）。
     */
    fun getLatestStatus(): SurvivalStatus?
}

/**
 * 生存判定と装備劣化を管理するクラス。
 */
class SurvivalManager(
    private val equipment: Equipment,
    private val onUiUpdate: (SurvivalStatus) -> Unit = {},
    private val onCriticalFailure: (CriticalFailureEvent) -> Unit = {}
) : SensorPollingInterface {

    private var durability: Double = equipment.maxDurability
    private var latestStatus: SurvivalStatus? = null

    override fun pollAndUpdate(environmentData: EnvironmentData) {
        val issues = buildList {
            evaluateMetric(
                metric = Metric.TEMPERATURE,
                value = environmentData.temperatureCelsius,
                allowedMin = equipment.minTemperatureCelsius,
                allowedMax = equipment.maxTemperatureCelsius
            )?.let(::add)

            evaluateMetric(
                metric = Metric.PRESSURE,
                value = environmentData.pressureHpa,
                allowedMin = equipment.minPressureHpa,
                allowedMax = equipment.maxPressureHpa
            )?.let(::add)

            evaluateMetric(
                metric = Metric.HUMIDITY,
                value = environmentData.humidityPercent,
                allowedMin = equipment.minHumidityPercent,
                allowedMax = equipment.maxHumidityPercent
            )?.let(::add)
        }

        val totalDegradation = issues.sumOf { it.degradationRate } * equipment.durabilityDrainMultiplier
        durability = max(0.0, durability - totalDegradation)

        val criticalIssue = issues.firstOrNull { it.exceedRatio >= CRITICAL_FAILURE_THRESHOLD }
        val criticalFailure = criticalIssue != null

        if (criticalIssue != null) {
            onCriticalFailure(
                CriticalFailureEvent(
                    metric = criticalIssue.metric,
                    value = criticalIssue.value,
                    exceedRatio = criticalIssue.exceedRatio,
                    message = "Critical Failure: ${criticalIssue.metric} exceeded limit by " +
                        "${"%.1f".format(criticalIssue.exceedRatio * 100)}%"
                )
            )
        }

        val status = SurvivalStatus(
            environment = environmentData,
            durability = durability,
            totalDegradation = totalDegradation,
            issues = issues,
            criticalFailure = criticalFailure
        )

        latestStatus = status
        onUiUpdate(status)
    }

    override fun getLatestStatus(): SurvivalStatus? = latestStatus

    fun resetDurability() {
        durability = equipment.maxDurability
        latestStatus = null
    }

    private fun evaluateMetric(
        metric: Metric,
        value: Double,
        allowedMin: Double,
        allowedMax: Double
    ): ThresholdBreach? {
        if (value in allowedMin..allowedMax) {
            return null
        }

        val span = (allowedMax - allowedMin).takeIf { it > 0 } ?: 1.0
        val exceedRatio = when {
            value > allowedMax -> (value - allowedMax) / span
            else -> (allowedMin - value) / span
        }

        val degradationRate = BASE_DEGRADATION_RATE * (1.0 + exceedRatio)

        return ThresholdBreach(
            metric = metric,
            value = value,
            allowedMin = allowedMin,
            allowedMax = allowedMax,
            exceedRatio = exceedRatio,
            degradationRate = degradationRate
        )
    }

    companion object {
        private const val BASE_DEGRADATION_RATE = 2.5
        private const val CRITICAL_FAILURE_THRESHOLD = 0.5
    }
}
