package com.sensorpioneer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sensorpioneer.logic.EnvironmentData
import com.sensorpioneer.logic.Equipment
import com.sensorpioneer.logic.SurvivalEvent
import com.sensorpioneer.logic.SurvivalViewModel
import com.sensorpioneer.logic.VirtualSensor
import kotlin.random.Random

/**
 * Sensor Pioneer の最小実行可能サンプル Activity。
 * XML レイアウト不要で、Android Studio に配置後すぐ動作確認できる。
 */
class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button

    private val viewModel: SurvivalViewModel by lazy {
        ViewModelProvider(this, SurvivalViewModelFactory()).get(SurvivalViewModel::class.java)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            val sampledEnvironment = randomEnvironment()
            viewModel.pollAndUpdate(sampledEnvironment)
            renderState()

            // 1秒間隔でセンサー更新をシミュレート
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            textSize = 16f
            text = "Sensor Pioneer\nTap START to begin simulation"
            setPadding(24, 24, 24, 24)
        }

        toggleButton = Button(this).apply {
            text = "START"
            setOnClickListener {
                running = !running
                text = if (running) "STOP" else "START"

                if (running) {
                    mainHandler.post(pollRunnable)
                } else {
                    mainHandler.removeCallbacks(pollRunnable)
                }
            }
        }

        val resetButton = Button(this).apply {
            text = "RESET DURABILITY"
            setOnClickListener {
                viewModel.resetDurability()
                renderState()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(statusView)
            addView(toggleButton)
            addView(resetButton)
        }

        setContentView(root)
        renderState()
    }

    override fun onStop() {
        super.onStop()
        running = false
        toggleButton.text = "START"
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun renderState() {
        val state = viewModel.uiState.value
        val reading = state.sensorReading?.measured

        val envText = if (reading == null) {
            "No sensor data yet"
        } else {
            "Temp: %.1f °C, Pressure: %.1f hPa, Humidity: %.1f %%".format(
                reading.temperatureCelsius,
                reading.pressureHpa,
                reading.humidityPercent
            )
        }

        val warningText = if (state.warning) "WARNING: OUT OF RANGE" else "Status: STABLE"
        val eventText = if (state.event == SurvivalEvent.CRITICAL_FAILURE) "EVENT: CRITICAL_FAILURE" else "EVENT: NONE"

        statusView.text = buildString {
            appendLine("Sensor Pioneer")
            appendLine(envText)
            appendLine("Durability: %.2f".format(state.durability))
            appendLine("Durability Loss(tick): %.4f".format(state.totalDurabilityLoss))
            appendLine(warningText)
            appendLine(eventText)
            if (state.breaches.isNotEmpty()) {
                appendLine("Breaches:")
                state.breaches.forEach { breach ->
                    appendLine(
                        "- ${breach.metric}: value=%.2f, boundary=%.2f, loss=%.4f, critical=%s".format(
                            breach.measuredValue,
                            breach.toleranceBoundary,
                            breach.durabilityLoss,
                            breach.critical
                        )
                    )
                }
            }
        }
    }

    private fun randomEnvironment(): EnvironmentData {
        return EnvironmentData(
            temperatureCelsius = Random.nextDouble(-80.0, 120.0),
            pressureHpa = Random.nextDouble(200.0, 2200.0),
            humidityPercent = Random.nextDouble(0.0, 100.0)
        )
    }
}

private class SurvivalViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
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
        return SurvivalViewModel(equipment, sensor) as T
    }
}
