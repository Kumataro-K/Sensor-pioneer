package com.sensorpioneer

import com.sensorpioneer.logic.EnvironmentData
import com.sensorpioneer.logic.Equipment
import com.sensorpioneer.logic.SurvivalController
import com.sensorpioneer.logic.SurvivalEvent
import com.sensorpioneer.logic.SurvivalUiState
import com.sensorpioneer.logic.VirtualSensor
import kotlin.random.Random

/**
 * これは Android Activity ではなく、Kotlin/JVM で動作するコンソール版シミュレータ。
 * Android SDK が未設定の環境でも実行できる。
 */
class MainActivity {
    private val controller = createController()

    fun runSimulation(ticks: Int = 10) {
        println("Sensor Pioneer console simulation start")

        repeat(ticks) {
            controller.pollAndUpdate(randomEnvironment())
        }

        println("Simulation finished")
    }

    private fun renderState(state: SurvivalUiState) {
        val reading = state.sensorReading?.measured
        val envText = if (reading == null) {
            "No sensor data yet"
        } else {
            "Temp=%.1f°C, Pressure=%.1fhPa, Humidity=%.1f%%".format(
                reading.temperatureCelsius,
                reading.pressureHpa,
                reading.humidityPercent
            )
        }

        val warningText = if (state.warning) "WARNING" else "STABLE"
        val eventText = if (state.event == SurvivalEvent.CRITICAL_FAILURE) "CRITICAL_FAILURE" else "NONE"

        println("$envText | Durability=%.2f | Loss=%.4f | %s | Event=%s".format(
            state.durability,
            state.totalDurabilityLoss,
            warningText,
            eventText
        ))
    }

    private fun createController(): SurvivalController {
        val equipment = Equipment(
            name = "Explorer Suit Mk-I",
            minTemperatureCelsius = -20.0,
            maxTemperatureCelsius = 50.0,
            minPressureHpa = 800.0,
            maxPressureHpa = 1200.0,
            minHumidityPercent = 10.0,
            maxHumidityPercent = 70.0,
            maxDurability = 100.0,
            temperatureK = 0.002,
            pressureK = 0.00005,
            humidityK = 0.001
        )

        val sensor = VirtualSensor(precision = 0.82)

        return SurvivalController(
            equipment = equipment,
            sensor = sensor,
            onStateChanged = { renderState(it) },
            onCriticalFailure = {
                println("Critical failure triggered: $it")
            }
        )
    }

    private fun randomEnvironment(): EnvironmentData {
        return EnvironmentData(
            temperatureCelsius = Random.nextDouble(-80.0, 120.0),
            pressureHpa = Random.nextDouble(200.0, 2200.0),
            humidityPercent = Random.nextDouble(0.0, 100.0)
        )
    }
}

fun main() {
    MainActivity().runSimulation(ticks = 20)
}
