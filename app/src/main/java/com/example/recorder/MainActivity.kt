package com.example.recorder

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.paramsen.noise.Noise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity(), SensorEventListener {
    // +------------------------------------------------------------------------------------------+
    // | =============================== Private variables ====================================== |
    // +------------------------------------------------------------------------------------------+

    // +------------------+
    // | System utilities |
    // +------------------+
    private lateinit var sensorManager: SensorManager
    private val timeSource = TimeSource.Monotonic

    // TODO("Fix naming")
    private lateinit var text0: TextView
    private lateinit var text1: TextView
    private lateinit var text2: TextView
    private lateinit var text3: TextView
    private lateinit var trackView: TextView

    // +--------------------+
    // | Accelerometer Data |
    // +--------------------+
    private var accelerometerData = ArrayDeque<Double>()
    private var accelerometerX: Double = 0.0
    private var accelerometerY: Double = 0.0
    private var accelerometerZ: Double = 0.0

    // +----------------------+
    // | Vars to calculate Hz |
    // +----------------------+
    private var isHzInitialized: Boolean = false
    private var measurements: Int = 0
    private var calculatedHz: Double = 0.0
    private var dataHz: Int = 0
    private lateinit var startPull: TimeMark

    // +-------------------------+
    // | Vars to recognise track |
    // +-------------------------+
    // TODO: bad mutex
    private val mutex = Mutex()
    private val hammingWindow: DoubleArray =
        DoubleArray(blockInputSize) { 0.54 - 0.46 * cos((2 * PI * it) / (blockInputSize - 1)) }
    private lateinit var referenceData: HashMap<String, IntArray> // TODO: Rename
    private var fingerprints = ArrayDeque<DoubleArray>()
    private var fingerprintHashes = ArrayDeque<Int>()
    private var fingerprintMatchingStepCount: Int = 0
    private var blockInputStepCount: Int = 0

    companion object {
        // +---------------------------+
        // | Constants to calculate Hz |
        // +---------------------------+
        private const val initializeMeasurementsCount: Int = 1000

        // +------------------------------+
        // | Constants to recognise track |
        // +------------------------------+
        // TODO: Clear
        private const val blockInputSize: Int = 128
        private const val blockInputStep: Int = 8
        private const val fingerprintsSize: Int = 12
        private const val frequenciesCount: Int = 24
        private const val bandsCount: Int =
            frequenciesCount + 1 // TODO: (why do we need it..)
        private const val fingerprintMergingSize: Int = 2
        private const val fingerprintBottomDiscardSize: Int = 13
        private const val powerSpectrumFloor: Double =
            1e-100    // TODO: Could round to 0 in Float (?)
        private const val fingerprintMatchingSize: Int = 768
    }

    // +------------------------------------------------------------------------------------------+
    // | ================================= Main Activity ======================================== |
    // +------------------------------------------------------------------------------------------+
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        text0 = findViewById(R.id.text0)
        text1 = findViewById(R.id.text1)
        text2 = findViewById(R.id.text2)
        text3 = findViewById(R.id.text3)
        trackView = findViewById(R.id.track)

        setupSensors()

        // TODO: Calculate reference Hz (?)
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    // +------------------------------------------------------------------------------------------+
    // | ============================= Sensor Event Listener ==================================== |
    // +------------------------------------------------------------------------------------------+
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            if (!isHzInitialized) {
                if (measurements == initializeMeasurementsCount) {
                    calculatedHz = initializeMeasurementsCount.seconds / startPull.elapsedNow()
                    getReferenceData()
                    isHzInitialized = true
                } else {
                    ++measurements
                }
            }

            accelerometerX = event.values[0].toDouble()
            accelerometerY = event.values[1].toDouble()
            accelerometerZ = event.values[2].toDouble()
        }

        addMeasurement(accelerometerZ) // TODO: not just Z (?)
        updateText()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        return
    }

    // +------------------------------------------------------------------------------------------+
    // | ================================ Private methods ======================================= |
    // +------------------------------------------------------------------------------------------+
    private suspend fun guessTrack(fingerprintHashesScreenshot: IntArray) {
        // +-------------------------------+
        // | Finds closest match in tracks |
        // +-------------------------------+
        // TODO: Test accuracy

        mutex.lock()
        val startTime: TimeMark = timeSource.markNow()

        var track: String = "NONE"
        // TODO: Use sizeOf
        var minError: Int = 32 * fingerprintMatchingSize
        for (trackInfo in referenceData) {
            // TODO: Add time recognition
            for (segmentStart in 0..trackInfo.value.size - fingerprintMatchingSize) {
                var error: Int = 0
                for (id in 0..<fingerprintMatchingSize) {
                    error += (trackInfo.value[segmentStart + id] xor fingerprintHashesScreenshot[id]).countOneBits()
                }
                if (minError > error) {
                    track = trackInfo.key
                    minError = error
                }
            }
        }

        // TODO: split view
        // TODO: We have time leak! Move textView out of here. Return results
        // trackView.text = "$track [$minError]"
        Log.d("DEVEL", "Guessed track: $track [$minError]\nTime spent: ${startTime.elapsedNow()}")
        mutex.unlock()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun calculateFingerprint() {
        // +---------------------------------+
        // | Calculates fingerprint and it's |
        // | hash from accelerometer data    |
        // +---------------------------------+

        // TODO: Move to coroutine

        // Hamming window
        val windowedData: FloatArray = FloatArray(blockInputSize)
        for (id in accelerometerData.indices) {
            windowedData[id] = (accelerometerData[id] * hammingWindow[id]).toFloat()
        }

        // FFT
        val noise: Noise = Noise.real(blockInputSize)
        val dst = FloatArray(blockInputSize + 2)

        val fft: FloatArray = noise.fft(windowedData, dst)
        // TODO: fft or dst they are different, but I dunno why
        //       I mean they are kinda similar but why would we
        //       need two outputs..

        // Extracting Data from FFT
        val realFftSize = blockInputSize / 2 + 1
        val x0: DoubleArray =
            DoubleArray(realFftSize) { 0.0 } // TODO: Rename (I dunno how to call)
        for (id in x0.indices) {
            x0[id] = fft[id * 2].toDouble()
            x0[id] *= x0[id]
            if (x0[id] < powerSpectrumFloor) {
                x0[id] = powerSpectrumFloor
            }
        }

        // Band split and energy calculation
        val bandEdges: IntArray =
            IntArray(bandsCount + 1) { fingerprintBottomDiscardSize + it * fingerprintMergingSize }
        val bandScale: DoubleArray =
            DoubleArray(bandsCount) { 1.0 / (bandEdges[it + 1] - bandEdges[it]) }

        val fingerprint: DoubleArray = DoubleArray(bandsCount) { 0.0 }
        for (fingerprintId in 0..<bandsCount) {
            for (id in bandEdges[fingerprintId]..<bandEdges[fingerprintId + 1]) {
                fingerprint[fingerprintId] += x0[id]
            }
            fingerprint[fingerprintId] *= bandScale[fingerprintId]
        }

        // Saving fingerprint
        if (fingerprints.size == fingerprintsSize) {
            fingerprints.removeFirst()
        }
        fingerprints.addLast(fingerprint)

        // Calculating fingerprint hash
        var fingerprintHash: Int = 0
        for (id in 0..<frequenciesCount) {
            // TODO: Rename or better, we can simplify code
            val difference =
                (fingerprint[id] - fingerprints.first()[id]) - (fingerprint[id + 1] - fingerprints.first()[id + 1])
            if (difference > 0) {
                fingerprintHash += (1 shl id)
            }
        }

        // Saving fingerprint hash
        if (fingerprintHashes.size == fingerprintMatchingSize) {
            fingerprintHashes.removeFirst()
        }
        fingerprintHashes.addLast(fingerprintHash)

        // Guessing the song
        // TODO: We can lose mutex
        if (!mutex.isLocked) {
            if (fingerprintHashes.size == fingerprintMatchingSize) {
                val fingerprintHashesScreenshot: IntArray = fingerprintHashes.toIntArray()

                // TODO: Formalize
                Log.d("DEVEL", "Guessing track")

                // TODO: GlobalScope is discouraged
                GlobalScope.launch {
                    guessTrack(fingerprintHashesScreenshot)
                }
            }
        }
    }

    private fun addMeasurement(value: Double) {
        // +---------------------------+
        // | Adds new measurement from |
        // | Accelerometer to the list |
        // +---------------------------+

        if (blockInputSize == accelerometerData.size) {
            accelerometerData.removeFirst()
        }
        accelerometerData.addLast(value)

        if (blockInputSize == accelerometerData.size) {
            ++blockInputStepCount

            if (blockInputStepCount == blockInputStep) {
                blockInputStepCount = 0
                calculateFingerprint()
            }
        }
    }

    private fun setupSensors() {
        // +------------------------------+
        // | Setting up sensorManager and |
        // | Register all needed sensors  |
        // +------------------------------+

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_FASTEST, SensorManager.SENSOR_DELAY_FASTEST
            )
        }

        // Start timestamp to calculate Hz
        startPull = timeSource.markNow()
    }

    private fun updateText() { // TODO: Rename
        // +------------------------------------+
        // | Updating sensor info on the screen |
        // +------------------------------------+

        if (isHzInitialized) {
            text0.text = "Calculated Hz: %.1fHz / Data Hz: %dHz".format(calculatedHz, dataHz)
        } else {
            text0.text = "Calculating Hz.."
        }
        text1.text = "x = %.3f".format(accelerometerX / SensorManager.GRAVITY_EARTH)
        text2.text = "y = %.3f".format(accelerometerY / SensorManager.GRAVITY_EARTH)
        text3.text = "z = %.3f".format(accelerometerZ / SensorManager.GRAVITY_EARTH)
    }

    // +------------------------------------------------------------------------------------------+
    // | =============================== Temporary methods ====================================== |
    // +------------------------------------------------------------------------------------------+
    // This methods are for show purpose, in reality we want to get this from server, not local data
    private fun getReferenceData() {
        // +--------------------------------+
        // | Rounds 'CalculatedHz' to       |
        // | Closest value for whom we      |
        // | Have calculated reference data |
        // |                                |
        // | Operation:                     |
        // | -> calculatedHz                |
        // | <- dataHz                      |
        // | <- referenceData               |
        // +--------------------------------+

        val dataHzList = listOf<Int>(400, 415, 500)

        var closestHz: Int = dataHzList[0]
        for (hz in dataHzList) {
            if (abs(closestHz - calculatedHz) > abs(hz - calculatedHz)) {
                closestHz = hz
            }
        }

        dataHz = closestHz
        lateinit var resource: InputStream
        // +---------+
        // | Format: |
        // | 1. name |
        // | 2. data |
        // | 3. name |
        // | 4. data |
        // | ...     |
        // +---------+

        if (dataHz == 500) {
            resource = resources.openRawResource(R.raw.reference_data_500hz)
        } else if (dataHz == 415) {
            resource = resources.openRawResource(R.raw.reference_data_415hz)
        } else if (dataHz == 400) {
            resource = resources.openRawResource(R.raw.reference_data_400hz)
        }

        val lines = resource.bufferedReader()
            .use { it.readLines() }
        // TODO: [W] A resource failed to call close.

        Log.d("DEVEL", "Read file, tracks: " + lines.size)

        // TODO: Drop frames, move to background (?)
        referenceData = hashMapOf()
        for (i in lines.indices step 2) {
            val trackName: String = lines[i]
            val data: List<Int> = lines[i + 1].split(' ').map { it.trim().toInt() }
            referenceData[trackName] = data.toIntArray()
        }

        Log.d("DEVEL", "Extracted Data")
    }
}
