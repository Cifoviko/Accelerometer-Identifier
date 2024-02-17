package com.example.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.paramsen.noise.Noise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
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

    // +---------+
    // | UI vars |
    // +---------+
    private lateinit var hzView: TextView
    private lateinit var xAccelerometerView: TextView
    private lateinit var yAccelerometerView: TextView
    private lateinit var zAccelerometerView: TextView
    private lateinit var trackNameView: TextView
    private lateinit var trackInfoView: TextView
    private val trackViewLock: ReentrantLock = ReentrantLock()
    private var trackName: String = "Track"
    private var trackError: Int = trackMatchMaxError
    private var trackMatchTimeStart: Int = 0
    private var trackMatchTimeEnd: Int = 0
    private lateinit var trackMatchTimestamp: TimeMark

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
    private var measurements: Int = 0
    private var calculatedHz: Double = 0.0
    private var dataHz: Int = 0
    private var isCalculatedHz: Boolean = false

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
    private var blockInputStepCount: Int = 0
    private var isLoadedData: AtomicBoolean = AtomicBoolean(false)
    private val guessTrackLock: ReentrantLock = ReentrantLock()

    // +----------------------+
    // | Vars for development |
    // +----------------------+
    private var fingerprintCalculationCount: Int = 0
    private var fingerprintCalculationTime: Duration = ZERO
    private val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "acc_results.txt")

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
        private const val trackMatchMaxError: Int = 32 * fingerprintMatchingSize
        private const val trackMatchThreshold: Int = 8300
        private const val topMatchesCount: Int = 5
    }

    // +------------------------------------------------------------------------------------------+
    // | ================================= Main Activity ======================================== |
    // +------------------------------------------------------------------------------------------+
    override fun onCreate(savedInstanceState: Bundle?) {

        // [Strict Mode] Section Start
        /*
        StrictMode.setThreadPolicy(
            ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .penaltyDeath()
                .build()
        )
        */
        // [Strict Mode] Section End

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on for long tests
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        trackMatchTimestamp = timeSource.markNow()

        hzView = findViewById(R.id.hzView)
        xAccelerometerView = findViewById(R.id.xAccelerometerView)
        yAccelerometerView = findViewById(R.id.yAccelerometerView)
        zAccelerometerView = findViewById(R.id.zAccelerometerView)
        trackNameView = findViewById(R.id.trackNameView)
        trackInfoView = findViewById(R.id.trackInfoView)

        // Create empty log file
        file.writeText("Results:\n")

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
            if (!isCalculatedHz) {
                if (measurements == initializeMeasurementsCount) {
                    calculatedHz = initializeMeasurementsCount.seconds / startPull.elapsedNow()
                    isCalculatedHz = true

                    // Should be Dispatchers.IO for server communication
                    CoroutineScope(Dispatchers.Default).launch(Dispatchers.Default) {
                        getReferenceData(calculatedHz)
                    }

                    Log.d("DEVEL", "onSensorChanged Debug call")
                } else {
                    ++measurements
                }
            }

            accelerometerX = event.values[0].toDouble()
            accelerometerY = event.values[1].toDouble()
            accelerometerZ = event.values[2].toDouble()

            addMeasurement(accelerometerZ) // TODO: not just Z (?)
            updateUI()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        return
    }

    // +------------------------------------------------------------------------------------------+
    // | ================================ Private methods ======================================= |
    // +------------------------------------------------------------------------------------------+
    private fun dataPointToSeconds(point: Int): Int {
        return (point * blockInputStep / dataHz)
    }
    private fun guessTrack(fingerprintHashesScreenshot: IntArray) {
        // +-------------------------------+
        // | Finds closest match in tracks |
        // +-------------------------------+

        // TODO: Test accuracy
        // TODO: We lose power, after a little calculations became slower and slower

        guessTrackLock.lock()

        Log.d("DEVEL", "guessTrack on thread: ${Thread.currentThread().name}")

        // For statistic
        val startTime: TimeMark = timeSource.markNow()
        val matchingTime = System.currentTimeMillis() / 1000

        val tracks: Array<String> = Array(topMatchesCount) { "NONE" }
        val errors = IntArray(topMatchesCount) { trackMatchMaxError }
        val positions = IntArray(topMatchesCount) { 0 }
        for (trackInfo in referenceDataHashes) {
            var minTrack: String = trackInfo.key
            var minError: Int = trackMatchMaxError
            var minPosition = 0
            for (segmentStart in 0..trackInfo.value.size - fingerprintMatchingSize) {
                var error = 0
                for (id in 0..<fingerprintMatchingSize) {
                    error += (trackInfo.value[segmentStart + id] xor fingerprintHashesScreenshot[id]).countOneBits()
                }
                if (error < minError) {
                    minError = error
                    minPosition = segmentStart
                }
            }
            for (id in tracks.indices) {
                if (errors[id] > minError) {
                    tracks[id] = minTrack.also { minTrack = tracks[id] }
                    errors[id] = minError.also { minError = errors[id] }
                    positions[id] = minPosition.also { minPosition = positions[id] }
                }
            }
        }

        // Set UI values
        trackViewLock.lock()
        trackName = tracks[0]
        trackError = errors[0]
        trackMatchTimeStart = dataPointToSeconds(positions[0])
        trackMatchTimeEnd = dataPointToSeconds(positions[0] + fingerprintMatchingSize)
        trackMatchTimestamp = startTime
        trackViewLock.unlock()

        var logString = "Guessed tracks:"
        for (id in tracks.indices) {
            logString += "\n${id + 1}) [${errors[id]}] ${tracks[id]} " +
                    "(at ${dataPointToSeconds(positions[id])}s - " +
                    "${dataPointToSeconds(positions[id] + fingerprintMatchingSize)}s)"
        }
        logString += "\nTime spent: ${startTime.elapsedNow()}"
        Log.d("DEVEL", logString)

        // Save to log file
        file.appendText("$matchingTime, $trackName, $trackError, $trackMatchTimeStart, $trackMatchTimeEnd \n")

        guessTrackLock.unlock()
    }

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
                fft[it * 2].toDouble() * fft[it * 2].toDouble() + fft[it * 2 + 1].toDouble() * fft[it * 2 + 1].toDouble()
            )
        }

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
        if (isLoadedData.get() && !guessTrackLock.isLocked) {
            val fingerprintHashesScreenshot: IntArray = fingerprintHashes

            // Calculating average fingerprint calculation time
            Log.d(
                "DEVEL",
                "Average fingerprint calculation time: ${fingerprintCalculationTime / fingerprintCalculationCount}"
            )
            fingerprintCalculationTime = ZERO
            fingerprintCalculationCount = 0

            Log.d("DEVEL", "Guessing track")

            CoroutineScope(Dispatchers.Default).launch(Dispatchers.Default) {
                guessTrack(fingerprintHashesScreenshot)
            }
        }
    }

    private fun addMeasurement(value: Double) {
        // +---------------------------+
        // | Adds new measurement from |
        // | Accelerometer to the list |
        // +---------------------------+

        //Log.d("DEVEL", "addMeasurement Debug call")

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
    private fun updateUI() {
        // +------------------------------------+
        // | Updating sensor info on the screen |
        // +------------------------------------+

        trackViewLock.lock()
        if (trackError < trackMatchThreshold) {
            trackNameView.setTextColor(Color.GREEN)
            trackInfoView.setTextColor(Color.GREEN)
        } else {
            trackNameView.setTextColor(Color.RED)
            trackInfoView.setTextColor(Color.RED)
        }

        trackNameView.text = trackName
        trackInfoView.text = "error: %d / at %ds - %ds / %ds ago".format(
            trackError,
            trackMatchTimeStart,
            trackMatchTimeEnd,
            trackMatchTimestamp.elapsedNow().inWholeSeconds
        )
        trackViewLock.unlock()

        if (isLoadedData.get()) {
            hzView.text = "Calculated Hz: %.1fHz / Data Hz: %dHz".format(calculatedHz, dataHz)
        } else {
            hzView.text = "Calculating Hz.."
        }

        xAccelerometerView.text = "x = %.3f".format(accelerometerX / SensorManager.GRAVITY_EARTH)
        yAccelerometerView.text = "y = %.3f".format(accelerometerY / SensorManager.GRAVITY_EARTH)
        zAccelerometerView.text = "z = %.3f".format(accelerometerZ / SensorManager.GRAVITY_EARTH)
    }

    // +------------------------------------------------------------------------------------------+
    // | =============================== Temporary methods ====================================== |
    // +------------------------------------------------------------------------------------------+
    // This methods are for show purpose, in reality we want to get this from server, not local data
    private fun getReferenceData(calculatedHz: Double) {
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

        Log.d("DEVEL", "getReferenceData on thread: ${Thread.currentThread().name}")

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

        // Reading file line by line in cycle because
        // File is to big to extract all lines at once
        // Without freezing app
        val bufferedReader = resource.bufferedReader()
        val lines = mutableListOf<String>()
        var line: String?
        while (bufferedReader.readLine().also { line = it } != null) {
            lines.add(line.toString())
        }
        bufferedReader.close()
        resource.close()

        // Fresh install don't drop frames, but repeated launch on some devices do

        Log.d("DEVEL", "Read file, tracks: " + lines.size)

        referenceDataHashes = hashMapOf()
        for (i in lines.indices step 2) {
            val trackName: String = lines[i]
            val data: List<Int> = lines[i + 1].split(' ').map { it.trim().toInt() }
            referenceDataHashes[trackName] = data.toIntArray()
        }

        isLoadedData.set(true)
        Log.d("DEVEL", "Extracted Data\nTime spent: ${startTime.elapsedNow()}")
    }
}
