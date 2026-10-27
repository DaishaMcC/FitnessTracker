package com.example.fitnesstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fitnesstracker.ui.theme.FitnessTrackerTheme
//class 1
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.nio.file.WatchEvent
import kotlin.math.sqrt
//class 2
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.blue
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.components.XAxis
// class 3
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.compose.material3.ButtonDefaults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
//class 4
import android.widget.Toast
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.example.fitnesstracker.data.AppDatabase
import com.example.fitnesstracker.data.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity(), SensorEventListener{

    //compose state - read by FitnessTrackerStreen(), written from sensor callbacks
    private var motionText by mutableStateOf("Motion: Stationary")
    private var gyroText by mutableStateOf("Rotation (Pitch, Roll, Yaw): 0.00, 0.00, 0.00")
    private var directionText by mutableStateOf("Direction")

    //sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    //sensor fusion both needed for the compass
    private var accelValues = FloatArray(3)
    private var magnetValues = FloatArray(3)

    //baseline cancels out gravity
    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 9.8f

    // MPAndroidChart is a view not a composable
    private lateinit var barChart: BarChart

    //sample data for the chart - index 2 is 0f to create a visible gap

    private val hourlySteps = floatArrayOf(100f, 1200f, 0f, 800f)

    //hardware step counter
    private var stepCounterSensor: Sensor? = null //demo mode for emulator

    private var sessionStepCount = 0
    private var initialStepCount = -1 //anchor point

    //health metric display state
    private var stepCountText by mutableStateOf("Steps: 0")
    private var caloriesText by mutableStateOf("Calories: 0.0 kcal")
    private var distanceText by mutableStateOf("Distance: 0.0 km")

    //demo mode compose state so the button recomposes when it flips
    private var isDemoMode by mutableStateOf(false)
    private val demoHandler = Handler(Looper.getMainLooper())

    //room
    private lateinit var database: AppDatabase
    private var sessionHistory by mutableStateOf<List<SessionEntity>>(emptyList())

    //calibration
    private var isCalibrating = false
    private val calibrationSamples = mutableListOf<FloatArray>()

    //accuracy warning
    private var accuracyText by mutableStateOf("")

    companion object {
        private const val STATIONARY_THRESHOLD = 2.0F
        private const val WALKING_THRESHOLD = 12.0F

        private const val ACTIVITY_PERMISSION_CODE = 100

        private const val CALORIES_PER_STEP = 0.04F

        private const val STRIDE_LENGTH_METERS = 0.762F


    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSensors()
        requestActivityPermission()
        database = AppDatabase.getInstance(this)
        loadHistory()

        setContent {
            FitnessTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FitnessTrackerScreen(
                        motionText = motionText,
                        gyroText = gyroText,
                        directionText = directionText,
                        onRefreshChartClick = { setupChart() },
                        onChartCreated = { chart -> barChart = chart; setupChart() },
                        stepCountText = stepCountText,
                        caloriesText = caloriesText,
                        distanceText = distanceText,
                        isDemoMode = isDemoMode,
                        onDemoModeClick = { toggleDemoMode() },
                        accuracyText = accuracyText,
                        isCalibrating = isCalibrating,
                        onCalibrateClick = { startCalibration() },
                        sessionHistory = sessionHistory,
                        onSaveSessionClick = { saveSession() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it,SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it,SensorManager.SENSOR_DELAY_NORMAL) }
        magnetometer?.let { sensorManager.registerListener(this, it,SensorManager.SENSOR_DELAY_NORMAL) }
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        sensorManager.unregisterListener(this)  //safety new onPause already does this
    }

    private fun initSensors () {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private fun requestActivityPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_PERMISSION_CODE)
    }


    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()
                // ASSIGNMENT 1
                handleAccelerometer(event.values)
//   Add two more cases to this when block:
//   - TYPE_GYROSCOPE: pass event.values to handleGyroscope()
//   - TYPE_MAGNETIC_FIELD: save a CLONE of event.values into magnetValues
//     (same reason as the accelerometer case), then call updateCompass()
            }
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscope(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD ->{
                magnetValues = event.values.clone()
                updateCompass()
            }

            Sensor.TYPE_STEP_COUNTER -> {
                handleStepCounter(event.values[0].toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        accuracyText = if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
            "${sensor.name} accuracy LOW - please recalibrate"
        else ""
    }

    private fun handleAccelerometer(values: FloatArray) {
        val x = values[0] - baselineX
        val y = values[1] - baselineY
        val z = values[2] - baselineZ
        val magnitude = sqrt(x * x + y * y + z * z)
        motionText = "Motion: ${classifyMotion(magnitude)}"
        if (isCalibrating) {
            calibrationSamples.add(floatArrayOf(values[0],values[1], values[2]))
            if (calibrationSamples.size >= 20) finishCalibration()
        }
    }

    private fun handleGyroscope(values: FloatArray) {
        gyroText = "Rotation (Pitch, Roll, Yaw): ${"%.2f".format(values[0])}, ${"%.2f".format(values[1])}, ${"%.2f".format(values[2])}"
    }


    // Sensor fusion: accel provides gravity reference (tilt), magnet provides North reference
    // getRotationMatrix() returns false if either sensor hasn't fired yet -- if-guard prevents a crash
    private fun updateCompass() {
        val rm = FloatArray(9); val im = FloatArray(9)
        if (SensorManager.getRotationMatrix(rm, im, accelValues, magnetValues)) {
            val orientation = FloatArray(3); SensorManager.getOrientation(rm, orientation)
            // orientation[0] = azimuth in radians (-pi to +pi) -> degrees -> normalize 0-360
            val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360) % 360
            directionText = "Direction: ${getDirection(deg)}"
        }
    }

    private fun getDirection(d: Float): String = when {
        d < 45 || d >= 315 -> "N"
        d < 125            -> "E"
        d < 225            -> "S"
        else               -> "W"
    }

    //
    private fun setupChart() {
        val entries = hourlySteps.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.Blue.toArgb()
            valueTextSize = 10f
        }
        //configure chart appearance
        barChart.apply {
            data = BarData(dataSet)
            description.text = "Set Count Per Hour"
            xAxis.position = XAxis.XAxisPosition.BOTTOM  //labels below bars
            xAxis.granularity = 1f  //one label per bar
            axisRight.isEnabled = false   //hide dublicate right y axis
            setFitBars(true)  //bars fill full chart width
            animateY(800)  //bars grow upward over 800m on load
            invalidate()  //force redraw - required after every data change
        }
    }

    private fun handleStepCounter(totalSteps: Int) {
        if (initialStepCount == -1) {
            initialStepCount = totalSteps
        }
        sessionStepCount = totalSteps - initialStepCount
        updateHealthMetrics()
        updateChartWithSteps()

    }

    private fun updateHealthMetrics() {
       stepCountText = "Steps: $sessionStepCount"
       caloriesText = "Calories: ${"%.1f".format(calculateCalories(sessionStepCount))} kcal"
       distanceText = "Distance: ${"%.2f".format(calculateDistance(sessionStepCount))} km"
    }

    // ASSIGNMENT 3

//   calculateCalories: return the total calories burned for `steps`,
//   using the per-step constant in the companion object.
//   calculateDistance: return the distance in KILOMETERS for `steps`,
//   using the stride-length constant. The constant is in metres, so a
//   conversion is needed. See Hour 1: Health Metric Formulas.

    private fun calculateCalories(steps: Int): Float = steps * CALORIES_PER_STEP

    private fun calculateDistance(steps: Int): Float = (steps * STRIDE_LENGTH_METERS) / 1000f

    private fun updateChartWithSteps() {
        val updatedSteps = floatArrayOf(
            sessionStepCount * 0.1f,
            sessionStepCount * 0.2f,
            sessionStepCount * 0.3f,
            sessionStepCount * 0.4f
        )
        val entries = updatedSteps.mapIndexed{ i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.Cyan.toArgb()
            valueTextSize = 10f
        }
        barChart.apply {
            data = BarData(dataSet)
            invalidate()
        }
    }

    private fun toggleDemoMode() {
        isDemoMode = !isDemoMode
        if (isDemoMode) {
            demoHandler.post(demoRunnable)
        } else {
            demoHandler.removeCallbacks(demoRunnable)
        }
    }

    private val demoRunnable = object : Runnable {
        private var compassAngle = 0f //tracks simulated heading independently
        override fun run() {
            if (!isDemoMode) return //safety check just in case

            //sine wave walking simulation feeds into handleAccelermeter() same
            //method real sensor events call the app cant tell the difference
            val t = System.currentTimeMillis()
            handleAccelerometer(floatArrayOf(
                (Math.sin(t / 200.0) * 3).toFloat(),
                (Math.cos(t / 300.0) * 2).toFloat(),
                9.8f + (Math.sin(t / 150.0) * 4).toFloat()
            ))

            //rotate compass 5 degrease per trick so direction cycles through
            compassAngle = (compassAngle + 5f) % 360f
            directionText = "Direction: ${getDirection(compassAngle)}"
            sessionStepCount += 2 //simulate 4 steps second at 5000 ms interval
            updateHealthMetrics()
            updateChartWithSteps()
            demoHandler.postDelayed(this, 500)
        }
    }

    private fun loadHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            val sessions = database.sessionDao().getRecentSessions()
            withContext(Dispatchers.Main) { sessionHistory = sessions }
        }
    }

    private fun startCalibration() {
        calibrationSamples.clear() //discard any previous samples
        isCalibrating = true  //handleAccelermeter() checks this flag also disables the button
    }

    private fun finishCalibration() {
        isCalibrating = false
        baselineX = calibrationSamples.map { it[0] }.average().toFloat()
        baselineY = calibrationSamples.map { it[1] }.average().toFloat()
        baselineZ = calibrationSamples.map { it[2] }.average().toFloat()
        calibrationSamples.clear()
    }

    private fun saveSession() {
        // bail out if there is nothing worth saving
        if (sessionStepCount == 0) {
            Toast.makeText(this, "No steps - please walk around", Toast.LENGTH_SHORT).show()
            return
        }
        val session = SessionEntity(
            timestamp = System.currentTimeMillis(), //right now mili secs
            steps = sessionStepCount, // current step count
            calories = calculateCalories(sessionStepCount),
            distanceKm = calculateDistance(sessionStepCount)

        )
        lifecycleScope.launch(Dispatchers.IO) {
            database.sessionDao().insertSession(session)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Session Saved!", Toast.LENGTH_SHORT).show()
                loadHistory()
            }
        }
        // ASSIGNMENT 4 -- Implement this! See Hour 3 for the full spec.
//   Launch a coroutine on Dispatchers.IO and insert `session` into the
//   database via database.sessionDao(). Database calls are not allowed
//   on the main thread. Once the insert finishes, switch back to
//   Dispatchers.Main (withContext) to show a confirmation Toast and
//   call loadHistory() again so the new session appears in the list.
    }


    private fun classifyMotion(magnitude: Float): String {
        return when {
            magnitude < STATIONARY_THRESHOLD -> "Stationary"
            magnitude < WALKING_THRESHOLD -> "Walking"
            else ->                         "Jogging"
        }
    }


}

@Composable
fun FitnessTrackerScreen(motionText: String, gyroText: String, directionText: String,
                         onRefreshChartClick: () -> Unit, onChartCreated: (BarChart) -> Unit,
                         stepCountText: String, caloriesText: String, distanceText: String,
                         isDemoMode: Boolean, onDemoModeClick: () -> Unit, accuracyText: String,
                         isCalibrating: Boolean, onCalibrateClick: () -> Unit, sessionHistory: List<SessionEntity>,
                         onSaveSessionClick: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)

    ) {
        Text(text = motionText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = gyroText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = directionText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))


        //accuracy warning hidden until onAccuracyChanged files
        if (accuracyText.isNotEmpty()) {
            Text(text = accuracyText, fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }

        //calibration button
        Button(onClick = onCalibrateClick, enabled = !isCalibrating, modifier = Modifier.padding(bottom = 20.dp)) {
            Text(if (isCalibrating) "Calibrating..." else "Calibrate")
        }

        AndroidView(    //blank until setUpChart() is implemented. AndroidView bridges a legacy View into Compose
            factory = { ctx -> BarChart(ctx).also(onChartCreated) },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(bottom = 16.dp)

        )

        Button(onClick = onRefreshChartClick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Refresh Chart")
        }

        //health metrics
        Text(text = stepCountText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = caloriesText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = directionText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))

        //demo button
        Button(
            onClick = onDemoModeClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDemoMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (isDemoMode) "Stop Demo" else "Demo Mode")
        }

        //session History save session list from room
        Button(onClick = onSaveSessionClick, modifier = Modifier.padding(bottom = 20.dp).align(Alignment.CenterHorizontally)){
            Text("Save Session")
        }
        Text(text = "Session History", fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        if (sessionHistory.isEmpty()) {
            Text("No session saved yet. Run Demo Mode then tap Save Session,", fontSize = 14.sp)
        } else {
            sessionHistory.forEach { s ->
                Text(
                   text = "${fmt.format(Date(s.timestamp))} | ${s.steps} steps | ${"%.1f".format(s.calories)} kcal | ${"%.2f".format(s.distanceKm)} km",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }


    }
}

