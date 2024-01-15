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
    private val initializeMeasurementsCount: Int = 1000
    private var calculatedHz: Double = 0.0
    private var dataHz: Int = 0
    private lateinit var startPull: TimeMark

    // +-------------------------+
    // | Vars to recognise track |
    // +-------------------------+
    // TODO: Clear
    private var fingerprints = ArrayDeque<Array<Double>>()
    private var fingerprintHashes = ArrayDeque<Int>()
    private lateinit var referenceData: HashMap<String, List<Int>> // TODO: Rename
    private val blockInputSize: Int = 128              // N_FFT
    private val blockInputStep: Int = 8                // N_STEP
    private val fingerprintsSize: Int = 12             // N_DEL
    private val frequenciesCount: Int = 24             // N_FREQ
    private val bandsCount: Int = frequenciesCount + 1 // N_BANDS TODO: (why do we need it..)
    private val fingerprintMergingSize: Int = 2        // N_AVG
    private val fingerprintBottomDiscardSize: Int = 13 // N_MIN
    private val powerSpectrumFloor: Double = 1e-100    // POWER_SPECTRUM_FLOOR TODO: Could round to 0 in Float
    private val fingerprintMatchingSize: Int = 768     // N_block
    private val fingerprintMatchingStep: Int = fingerprintMatchingSize
    private var fingerprintMatchingStepCount: Int = 0
    private var blockInputStepCount: Int = 0

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
        sensorManager.unregisterListener(this);
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
    private fun guessTrack() {
        // TODO: Add comment

        Log.d("DEVEL", "Guessing track")

        // TODO: Move to background, we drop frames
        //       And add all tracks

        // Tested on track 'Wonderful world'
        // Noise gives around 8800 errors
        // While track is played we have 8500-8700
        // Underperformed, need further checks

        for (trackInfo in referenceData) {
            var string: String = ""
            string += trackInfo.key

            // TODO: Add time recognition
            // TODO: Use sizeOf
            var minError: Int = 32 * fingerprintMatchingSize
            for (segmentStart in 0..<trackInfo.value.size - fingerprintMatchingSize) {
                var error = 0
                for (id in 0..<fingerprintMatchingSize) {
                    error += (trackInfo.value[segmentStart + id] xor fingerprintHashes[id]).countOneBits()
                }
                minError = min(minError, error)
            }
            string += " $minError"

            trackView.text = string
            break
        }
    }

    private fun calculateFingerprint() {
        // TODO: Tidy up (and add comments)
        // calculate_fp(?, accelerometerData)

        // x0 = x_in * w
        // X0 = abs(np.fft.fft(x0, N_FFT)[: int(N_FFT / 2 + 1)]) ** 2
        // X0[X0 < POWER_SPECTRUM_FLOOR] = POWER_SPECTRUM_FLOOR  # Avoid zero

        // Hamming window
        val windowedData: FloatArray = FloatArray(blockInputSize)
        for (id in accelerometerData.indices) {
            // Hamming value w(id)
            val w: Double = 0.54 - 0.46 * cos((2 * PI * id) / (blockInputSize - 1))
            windowedData[id] = (accelerometerData[id] * w).toFloat()
            // TODO: we have conversion to float (probably ok)
        }

        // FFT
        val noise: Noise = Noise.real(blockInputSize)
        val dst = FloatArray(blockInputSize + 2)

        val fft: FloatArray = noise.fft(windowedData, dst)
        // TODO: fft or dst they are different, but I dunno why
        //       I mean they are kinda similar but why would we
        //       need two outputs..

        // TODO: Delete
        /*
        Log.d("DEVEL", "fft size: ${fft.size}")
        Log.d("DEVEL", "dst size: ${dst.size}")

        var string: String = "fft: "
        for (id in fft.indices) {
            string += fft[id].toString()
            string += " "
        }
        Log.d("DEVEL", string)
        string = "dst: "
        for (id in dst.indices) {
            string += dst[id].toString()
            string += " "
        }
        Log.d("DEVEL", string)
        */

        // Extracting Data from FFT
        val realFftSize = blockInputSize / 2 + 1
        val x0: Array<Double> = Array<Double>(realFftSize) { 0.0 } // TODO: Rename (I dunno how to call)
        for (id in x0.indices) {
            x0[id] = fft[id * 2].toDouble()
            x0[id] *= x0[id]
            if (x0[id] < powerSpectrumFloor) {
                x0[id] = powerSpectrumFloor
            }
        }

        // Band split and energy calculation
        val bandEdges: Array<Int> =
            Array<Int>(bandsCount + 1) { fingerprintBottomDiscardSize + it * fingerprintMergingSize }
        val bandScale: Array<Double> =
            Array<Double>(bandsCount) { 1.0 / (bandEdges[it + 1] - bandEdges[it]) }

        val fingerprint: Array<Double> = Array<Double>(bandsCount) { 0.0 }
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

        /*var string: String = "New fingerprint: "
        for (id in fingerprint.indices) {
            string += fingerprint[id].toString()
            string += " "
        }
        Log.d("DEVEL", string)*/

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
        //Log.d("DEVEL", "New hash: $fingerprintHash")

        // Guessing the song
        ++fingerprintMatchingStepCount
        if (fingerprintMatchingStepCount == fingerprintMatchingStep) {
            fingerprintMatchingStepCount = 0
            if (fingerprintHashes.size == fingerprintMatchingSize) {
                guessTrack()
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

        val dataHzList = listOf<Int>(400, 500)

        var closestHz: Int = dataHzList[0]
        for (hz in dataHzList) {
            if (abs(closestHz - calculatedHz) > abs(hz - calculatedHz)) {
                closestHz = hz
            }
        }

        dataHz = closestHz

        if (dataHz == 500) {
            // +---------+
            // | Format: |
            // | 1. name |
            // | 2. data |
            // | 3. name |
            // | 4. data |
            // | ...     |
            // +---------+
            val lines = resources.openRawResource(R.raw.reference_data_500hz).bufferedReader()
                .use { it.readLines() }
            // TODO: [W] A resource failed to call close.

            // TODO: remove
            Log.d("DEVEL", "Size: " + lines.size)

            // TODO: Drop frames, move to background (?)
            referenceData = hashMapOf()
            for (i in lines.indices step 2) {
                val trackName: String = lines[i]
                val data: List<Int> = lines[i + 1].split(' ').map { it.trim().toInt() }
                referenceData[trackName] = data
            }

            // TODO: remove
            for (track in referenceData) {
                Log.d("DEVEL", "Name: " + track.key)
                Log.d("DEVEL", "Data: " + track.value)
                break
            }
        }
    }
}
