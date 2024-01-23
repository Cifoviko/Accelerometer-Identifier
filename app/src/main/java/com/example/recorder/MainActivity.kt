package com.example.recorder

import android.annotation.SuppressLint
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
import kotlin.math.max
import kotlin.time.*
import kotlin.time.Duration.Companion.ZERO
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

    // TODO: Fix naming
    private lateinit var text0: TextView
    private lateinit var text1: TextView
    private lateinit var text2: TextView
    private lateinit var text3: TextView
    private lateinit var trackView: TextView

    // +--------------------+
    // | Accelerometer Data |
    // +--------------------+
    private var accelerometerData = DoubleArray(blockInputSize)
    private var accelerometerX: Double = 0.0
    private var accelerometerY: Double = 0.0
    private var accelerometerZ: Double = 0.0

    // +----------------------+
    // | Vars to calculate Hz |
    // +----------------------+
    private lateinit var startPull: TimeMark
    private var isHzInitialized: Boolean = false
    private var measurements: Int = 0
    private var calculatedHz: Double = 0.0
    private var dataHz: Int = 0

    // +-----------------------------------------+
    // | Pre calculations to create fingerprints |
    // +-----------------------------------------+
    private val hammingWindow: DoubleArray =
        DoubleArray(blockInputSize) { 0.54 - 0.46 * cos((2 * PI * it) / (blockInputSize - 1)) }
    private val bandEdges: IntArray =
        IntArray(bandsCount + 1) { fingerprintBottomDiscardSize + it * fingerprintMergingSize }
    private val bandScale: DoubleArray =
        DoubleArray(bandsCount) { 1.0 / (bandEdges[it + 1] - bandEdges[it]) }

    // +-------------------------+
    // | Vars to recognise track |
    // +-------------------------+
    private lateinit var referenceDataHashes: HashMap<String, IntArray>
    private var fingerprints = ArrayDeque<DoubleArray>()
    private var fingerprintHashes = IntArray(fingerprintMatchingSize)
    // private var fingerprintMatchingStepCount: Int = 0
    private var blockInputStepCount: Int = 0
    private var isLoadedData: Boolean = false
    private val mutex = Mutex() // TODO: bad mutex

    // +----------------------+
    // | Vars for development |
    // +----------------------+
    private var fingerprintCalculationCount: Int = 0
    private var fingerprintCalculationTime: Duration = ZERO

    companion object {
        // +---------------------------+
        // | Constants to calculate Hz |
        // +---------------------------+
        private const val initializeMeasurementsCount: Int = 1000

        // +------------------------------+
        // | Constants to recognise track |
        // +------------------------------+
        private const val blockInputSize: Int = 128
        private const val blockInputStep: Int = 8
        private const val fingerprintsSize: Int = 12
        private const val frequenciesCount: Int = 24
        private const val bandsCount: Int = frequenciesCount + 1
        private const val fingerprintMergingSize: Int = 2
        private const val fingerprintBottomDiscardSize: Int = 13
        private const val powerSpectrumFloor: Double = 1e-100
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
        // TODO: Catch top k matches
        // TODO: We lose power, after a little calculations became slower and slower

        mutex.lock()

        // For DEBUG
        val startTime: TimeMark = timeSource.markNow()

        var track = "NONE"
        // TODO: Use sizeOf instead of 32
        var minError: Int = 32 * fingerprintMatchingSize
        for (trackInfo in referenceDataHashes) {
            // TODO: Add time recognition
            for (segmentStart in 0..trackInfo.value.size - fingerprintMatchingSize) {
                var error = 0
                for (id in 0..<fingerprintMatchingSize) {
                    error += (trackInfo.value[segmentStart + id] xor fingerprintHashesScreenshot[id]).countOneBits()
                }
                if (minError > error) {
                    track = trackInfo.key
                    minError = error
                }
            }
        }

        // TODO: print results on screen
        Log.d("DEVEL", "Guessed track: $track [$minError]\nTime spent: ${startTime.elapsedNow()}")
        mutex.unlock()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun calculateFingerprint() {
        // +----------------------------------+
        // | Calculates fingerprint and it's  |
        // | hash from accelerometer data and |
        // | if needed tries to guess track   |
        // +----------------------------------+

        // TODO: Move to coroutine (?)

        // for DEBUG
        val startTime: TimeMark = timeSource.markNow()

        // Hamming window
        val windowedData =
            FloatArray(blockInputSize) { (accelerometerData[it] * hammingWindow[it]).toFloat() }

        // FFT
        val noise: Noise = Noise.real(blockInputSize)
        val dst = FloatArray(blockInputSize + 2)

        val fft: FloatArray = noise.fft(windowedData, dst)
        // TODO: fft or dst they are different, but I dunno why
        //       I mean they are really similar, but why would we
        //       need two outputs..

        // Extracting Data from FFT
        val realFftSize = blockInputSize / 2 + 1
        val magnitude = DoubleArray(realFftSize) {
            max(
                powerSpectrumFloor,
                (fft[it * 2].toDouble()) * (fft[it * 2].toDouble())
            )
        } // TODO: Rename (I dunno how to call)

        // Band split and energy calculation
        val fingerprint = DoubleArray(bandsCount) { 0.0 }
        for (fingerprintId in 0..<bandsCount) {
            for (id in bandEdges[fingerprintId]..<bandEdges[fingerprintId + 1]) {
                fingerprint[fingerprintId] += magnitude[id]
            }
            fingerprint[fingerprintId] *= bandScale[fingerprintId]
        }

        // Saving fingerprint
        if (fingerprints.size == fingerprintsSize) {
            fingerprints.removeFirst()
        }
        fingerprints.addLast(fingerprint)

        // Calculating fingerprint hash
        var fingerprintHash = 0
        for (id in 0..<frequenciesCount) {
            // TODO: Rename or better, we can simplify code
            val difference =
                (fingerprint[id] - fingerprints.first()[id]) - (fingerprint[id + 1] - fingerprints.first()[id + 1])
            if (difference > 0) {
                fingerprintHash += (1 shl id)
            }
        }

        // Saving fingerprint hash
        for (id in 0..<(fingerprintHashes.size - 1)) {
            fingerprintHashes[id] = fingerprintHashes[id + 1]
        }
        fingerprintHashes[fingerprintHashes.lastIndex] = fingerprintHash

        // Saving time to calculate average fingerprint calculation time
        fingerprintCalculationTime += startTime.elapsedNow()
        ++fingerprintCalculationCount

        // Guessing the song
        // TODO: We can lose mutex
        if (isLoadedData && !mutex.isLocked) {
            if (fingerprintHashes.size == fingerprintMatchingSize) {
                val fingerprintHashesScreenshot: IntArray = fingerprintHashes

                // Calculating average fingerprint calculation time
                Log.d(
                    "DEVEL",
                    "Average fingerprint calculation time: ${fingerprintCalculationTime / fingerprintCalculationCount}"
                )
                fingerprintCalculationTime = ZERO
                fingerprintCalculationCount = 0

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

        // Saving measurement
        for (id in 0..<(accelerometerData.size - 1)) {
            accelerometerData[id] = accelerometerData[id + 1]
        }
        accelerometerData[accelerometerData.lastIndex] = value

        // Checking to calculate fingerprint
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

    @SuppressLint("SetTextI18n")
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

        val dataHzList = listOf(400, 415, 435, 470, 500)

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

        val startTime: TimeMark = timeSource.markNow()

        when (dataHz) {
            500 -> {
                resource = resources.openRawResource(R.raw.reference_data_500hz)
            }

            470 -> {
                resource = resources.openRawResource(R.raw.reference_data_470hz)
            }

            435 -> {
                resource = resources.openRawResource(R.raw.reference_data_435hz)
            }

            415 -> {
                resource = resources.openRawResource(R.raw.reference_data_415hz)
            }

            400 -> {
                resource = resources.openRawResource(R.raw.reference_data_400hz)
            }
        }

        val lines = resource.bufferedReader()
            .use { it.readLines() }
        // TODO: [W] A resource failed to call close.

        Log.d("DEVEL", "Read file, tracks: " + lines.size)

        // TODO: Drop frames, move to background (?)
        referenceDataHashes = hashMapOf()
        for (i in lines.indices step 2) {
            val trackName: String = lines[i]
            val data: List<Int> = lines[i + 1].split(' ').map { it.trim().toInt() }
            referenceDataHashes[trackName] = data.toIntArray()
        }

        isLoadedData = true
        Log.d("DEVEL", "Extracted Data\nTime spent: ${startTime.elapsedNow()}")
    }
}
