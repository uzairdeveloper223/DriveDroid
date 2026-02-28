package dev.uzair.drivedroid

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class WebSocketManager {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    var connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
    var errorMessage = mutableStateOf<String?>(null)

    fun connect(ip: String, port: String) {
        if (connectionState.value != ConnectionState.DISCONNECTED) return
        connectionState.value = ConnectionState.CONNECTING
        errorMessage.value = null

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "ws://$ip:$port"
        val request = Request.Builder().url(url).build()

        try {
            webSocket = client?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connectionState.value = ConnectionState.CONNECTED
                    Log.d("WS", "Connected to $url")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {}
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connectionState.value = ConnectionState.DISCONNECTED
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connectionState.value = ConnectionState.DISCONNECTED
                    errorMessage.value = t.message
                }
            })
        } catch (e: Exception) {
            connectionState.value = ConnectionState.DISCONNECTED
            errorMessage.value = "Invalid URL or connection issue"
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connectionState.value = ConnectionState.DISCONNECTED
    }

    /** For button commands (pedals, handbrake) */
    fun sendCommand(action: String, state: String) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        val json = JSONObject().apply {
            put("action", action)
            put("state", state)
        }
        webSocket?.send(json.toString())
    }

    /** Gamepad analog axis: -1.0 (full left) … 1.0 (full right) */
    fun sendSteer(value: Float) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        val json = JSONObject().apply {
            put("action", "STEER")
            put("value", value.toDouble())
        }
        webSocket?.send(json.toString())
    }

    /**
     * PWM keyboard steering: sends the raw float tilt to the server.
     * The server's PWM loop converts it into proportional key pulses.
     * value: -1.0 (hard left) … 1.0 (hard right)
     */
    fun sendSteerPWM(value: Float) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        val json = JSONObject().apply {
            put("action", "STEER_PWM")
            put("value", value.toDouble())
        }
        webSocket?.send(json.toString())
    }

    /** Tell the server which steering mode the user selected in Settings */
    fun sendSteerMode(mode: String) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        val json = JSONObject().apply {
            put("action", "STEER_MODE")
            put("mode", mode)
        }
        webSocket?.send(json.toString())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sensor handler
// ─────────────────────────────────────────────────────────────────────────────

class SensorHandler(private val context: Context, private val wsManager: WebSocketManager) :
    SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor        = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer        = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** "GAMEPAD" | "KEYBOARD_AD" | "KEYBOARD_ARROWS" */
    var steeringMode = mutableStateOf("KEYBOARD_AD")

    /** Max tilt angle (degrees) that maps to full steering lock */
    var maxTiltAngle = mutableStateOf(30f)

    /** Tilt angles below this (degrees) are treated as center */
    var deadzone = mutableStateOf(3f)

    // ── UI display state ──────────────────────────────────────────────────
    var currentSteerState = mutableStateOf("CENTER")
    var currentTilt       = mutableFloatStateOf(0f)   // -1.0 … 1.0
    var currentAngle      = mutableFloatStateOf(0f)   // raw degrees

    // ── Low-pass filter ───────────────────────────────────────────────────
    /**
     * Alpha for exponential low-pass filter.
     * 0.0 = no update (frozen), 1.0 = no smoothing (raw).
     * 0.15 is a good starting point: kills jitter while staying responsive.
     * With a gyro-fused ROTATION_VECTOR sensor this would be ~0.6; the plain
     * accelerometer is noisy so we smooth more aggressively.
     */
    private var filterAlpha = 0.15f
    private var filteredAngle = 0f
    private var filterInitialized = false

    // ── Internal tracking ─────────────────────────────────────────────────
    private var lastSentGamepadValue = 0f
    // Minimum change in steer value before sending another PWM update (reduces WS spam)
    private val PWM_SEND_THRESHOLD = 0.015f
    private var lastSentPWMValue = 0f

    private val rotationMatrix    = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        val sensor = rotationVectorSensor ?: gravitySensor ?: accelerometer
        val name = when (sensor) {
            rotationVectorSensor -> "Rotation Vector (best accuracy)"
            gravitySensor        -> "Gravity (good accuracy)"
            accelerometer        -> "Accelerometer + low-pass filter (basic)"
            else                 -> "None"
        }
        // Adjust filter alpha based on sensor quality
        filterAlpha = when (sensor) {
            rotationVectorSensor -> 0.6f   // already fused, less smoothing needed
            gravitySensor        -> 0.35f  // decent, medium smoothing
            else                 -> 0.15f  // raw accel is noisy, smooth hard
        }
        filterInitialized = false
        Log.i("SensorHandler", "Using sensor: $name | filterAlpha=$filterAlpha")

        sensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        // Tell server to center
        when (steeringMode.value) {
            "GAMEPAD"                          -> wsManager.sendSteer(0f)
            "KEYBOARD_AD", "KEYBOARD_ARROWS"   -> wsManager.sendSteerPWM(0f)
        }
        currentTilt.floatValue       = 0f
        currentAngle.floatValue      = 0f
        currentSteerState.value      = "CENTER"
        lastSentGamepadValue         = 0f
        lastSentPWMValue             = 0f
        filterInitialized            = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // ── 1. Raw roll angle from sensor ─────────────────────────────────
        var rawRoll: Float = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            }
            Sensor.TYPE_GRAVITY -> {
                val gx = event.values[0]; val gz = event.values[2]
                Math.toDegrees(Math.atan2(gx.toDouble(), gz.toDouble())).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // yValue: +9.81 = portrait upright, -9.81 = upside-down
                // Map to ±90°
                (event.values[1] / 9.81f * 90f)
            }
            else -> return
        }

        // ── 2. Handle landscape rotation ──────────────────────────────────
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.rotation == android.view.Surface.ROTATION_270) {
            rawRoll = -rawRoll
        }

        // ── 3. Low-pass filter to kill accelerometer jitter ───────────────
        filteredAngle = if (!filterInitialized) {
            filterInitialized = true
            rawRoll
        } else {
            filteredAngle + filterAlpha * (rawRoll - filteredAngle)
        }

        currentAngle.floatValue = filteredAngle

        // ── 4. Deadzone + normalize to -1.0 … 1.0 ────────────────────────
        val dz       = deadzone.value
        val maxAngle = maxTiltAngle.value

        val steerValue: Float = if (abs(filteredAngle) < dz) {
            0f
        } else {
            val sign      = if (filteredAngle > 0) 1f else -1f
            val magnitude = (abs(filteredAngle) - dz) / (maxAngle - dz)
            (sign * magnitude).coerceIn(-1f, 1f)
        }

        currentTilt.floatValue  = steerValue
        currentSteerState.value = when {
            steerValue >  0.05f -> "RIGHT"
            steerValue < -0.05f -> "LEFT"
            else                -> "CENTER"
        }

        // ── 5. Send based on mode ─────────────────────────────────────────
        when (steeringMode.value) {
            "GAMEPAD" -> {
                // Analog axis: only send when change is significant
                if (abs(steerValue - lastSentGamepadValue) > 0.02f) {
                    wsManager.sendSteer(steerValue)
                    lastSentGamepadValue = steerValue
                }
            }

            "KEYBOARD_AD", "KEYBOARD_ARROWS" -> {
                // ── PWM mode ──────────────────────────────────────────────
                //
                // Instead of sending binary key UP/DOWN here on the app side,
                // we send the continuous float value to the server.
                // The server's PWMSteeringController loop converts it into
                // rapidly pulsed key presses (like PWM in electronics):
                //
                //   steer = 0.20  →  key ON for 10ms, OFF for 40ms  (light touch)
                //   steer = 0.50  →  key ON for 25ms, OFF for 25ms  (half lock)
                //   steer = 1.00  →  key held continuously           (full lock)
                //
                // Speed Dreams physics sees rapid key events and its internal
                // steering accumulates proportionally → real steering feel.
                //
                if (abs(steerValue - lastSentPWMValue) > PWM_SEND_THRESHOLD) {
                    wsManager.sendSteerPWM(steerValue)
                    lastSentPWMValue = steerValue
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ─────────────────────────────────────────────────────────────────────────────
// DataStore extension
// ─────────────────────────────────────────────────────────────────────────────

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private val wsManager = WebSocketManager()
    private lateinit var sensorHandler: SensorHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorHandler = SensorHandler(this, wsManager)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary    = Color(0xFF64B5F6),
                    background = Color(0xFF121212),
                    surface    = Color(0xFF1E1E1E),
                    onSurface  = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val connState by wsManager.connectionState
                    if (connState == ConnectionState.DISCONNECTED ||
                        connState == ConnectionState.CONNECTING
                    ) {
                        ConnectionScreen(wsManager, this@MainActivity)
                    } else {
                        ControllerScreen(wsManager, sensorHandler, this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wsManager.connectionState.value == ConnectionState.CONNECTED) sensorHandler.start()
    }

    override fun onPause() {
        super.onPause()
        sensorHandler.stop()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Connection Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectionScreen(wsManager: WebSocketManager, context: Context) {
    val IP_KEY   = stringPreferencesKey("saved_ip")
    val PORT_KEY = stringPreferencesKey("saved_port")
    val coroutineScope = rememberCoroutineScope()

    var ip   by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }

    LaunchedEffect(Unit) {
        context.dataStore.data.collect { prefs ->
            prefs[IP_KEY]?.let   { ip   = it }
            prefs[PORT_KEY]?.let { port = it }
        }
    }

    val connState by wsManager.connectionState
    val errorMsg  by wsManager.errorMessage

    Column(
        modifier              = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(bottom = 32.dp)
        ) {
            Icon(
                painter           = painterResource(id = R.drawable.ic_drivedroid),
                contentDescription = "DriveDroid logo",
                tint              = Color.Unspecified,
                modifier          = Modifier.size(40.dp).padding(end = 12.dp)
            )
            Text(
                text       = "DriveDroid",
                fontSize   = 42.sp,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value         = ip,
            onValueChange = { ip = it },
            label         = { Text("Server IP Address") },
            placeholder   = { Text("e.g. 192.168.1.5") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value         = port,
            onValueChange = { port = it },
            label         = { Text("Port") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(0.5f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick   = {
                coroutineScope.launch {
                    context.dataStore.edit { s ->
                        s[IP_KEY]   = ip
                        s[PORT_KEY] = port
                    }
                }
                wsManager.connect(ip, port)
            },
            enabled  = ip.isNotBlank() && connState != ConnectionState.CONNECTING,
            modifier = Modifier.fillMaxWidth(0.5f).height(56.dp)
        ) {
            if (connState == ConnectionState.CONNECTING) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...")
            } else {
                Text("CONNECT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        errorMsg?.let {
            Text(text = it, color = Color.Red, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Controller Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ControllerScreen(wsManager: WebSocketManager, sensorHandler: SensorHandler, context: Context) {
    val CTRL_MODE_KEY  = stringPreferencesKey("controller_mode")
    val STEER_MODE_KEY = stringPreferencesKey("steering_mode")
    val BRAKE_KEY      = stringPreferencesKey("brake_action")
    val ACCEL_KEY      = stringPreferencesKey("accel_action")
    val MAX_ANGLE_KEY  = floatPreferencesKey("max_tilt_angle")
    val DEADZONE_KEY   = floatPreferencesKey("deadzone")
    val coroutineScope = rememberCoroutineScope()

    var showSettings    by remember { mutableStateOf(false) }
    var controllerMode  by remember { mutableStateOf("MOTION") }
    var brakeAction     by remember { mutableStateOf("BRAKE_S") }
    var accelAction     by remember { mutableStateOf("ACCELERATE_W") }
    var spaceAction     by remember { mutableStateOf("HANDBRAKE") }

    LaunchedEffect(Unit) {
        context.dataStore.data.collect { prefs ->
            prefs[CTRL_MODE_KEY]?.let  { controllerMode                     = it }
            prefs[STEER_MODE_KEY]?.let { sensorHandler.steeringMode.value   = it }
            prefs[BRAKE_KEY]?.let      { brakeAction                        = it }
            prefs[ACCEL_KEY]?.let      { accelAction                        = it }
            prefs[MAX_ANGLE_KEY]?.let  { sensorHandler.maxTiltAngle.value   = it }
            prefs[DEADZONE_KEY]?.let   { sensorHandler.deadzone.value       = it }
        }
    }

    // Notify server whenever steering mode changes so it can hot-swap PWM keys
    LaunchedEffect(sensorHandler.steeringMode.value) {
        wsManager.sendSteerMode(sensorHandler.steeringMode.value)
    }

    LaunchedEffect(controllerMode) {
        if (controllerMode == "MOTION") sensorHandler.start()
        else sensorHandler.stop()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top-right: Settings + Disconnect ──────────────────────────────
        Row(
            modifier          = Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showSettings = true },
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) { Text("⚙ Settings") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { sensorHandler.stop(); wsManager.disconnect() },
                colors  = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f))
            ) { Text("Disconnect") }
        }

        if (controllerMode == "MOTION") {
            // ── Steering indicator ─────────────────────────────────────────
            Column(
                modifier            = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val tilt  by sensorHandler.currentTilt
                val angle by sensorHandler.currentAngle
                val dir   by sensorHandler.currentSteerState
                val pct   = (abs(tilt) * 100).toInt()

                Text(
                    "Steering: $dir ${if (dir != "CENTER") "$pct%" else ""}",
                    fontWeight = FontWeight.Bold, color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(8.dp))

                val barWidth = 250.dp
                Box(
                    modifier = Modifier
                        .width(barWidth).height(12.dp)
                        .background(Color.DarkGray, RoundedCornerShape(6.dp))
                ) {
                    val indicatorColor = when {
                        abs(tilt) > 0.7f -> Color(0xFFFF5722)
                        abs(tilt) > 0.3f -> MaterialTheme.colorScheme.primary
                        dir == "CENTER"  -> Color.Green
                        else             -> Color(0xFF81C784)
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .offset(x = (barWidth / 2) + (barWidth / 2) * tilt - 6.dp)
                            .background(indicatorColor, RoundedCornerShape(6.dp))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("${String.format("%.1f", angle)}°", fontSize = 10.sp, color = Color.Gray)

                // PWM mode hint
                if (sensorHandler.steeringMode.value != "GAMEPAD") {
                    Text(
                        "PWM steering active • tilt = proportional key pulse",
                        fontSize = 9.sp, color = Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            GamepadButton(
                text      = "BRAKE",
                color     = Color.Red.copy(alpha = 0.8f),
                modifier  = Modifier.align(Alignment.BottomStart).padding(32.dp).size(120.dp, 100.dp),
                onPress   = { wsManager.sendCommand(brakeAction, "DOWN") },
                onRelease = { wsManager.sendCommand(brakeAction, "UP") }
            )
            GamepadButton(
                text      = "RACE",
                color     = Color(0xFF43A047).copy(alpha = 0.8f),
                modifier  = Modifier.align(Alignment.BottomEnd).padding(32.dp).size(120.dp, 100.dp),
                onPress   = { wsManager.sendCommand(accelAction, "DOWN") },
                onRelease = { wsManager.sendCommand(accelAction, "UP") }
            )
            GamepadButton(
                text      = "SPACE",
                color     = Color.White.copy(alpha = 0.3f),
                modifier  = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(160.dp, 80.dp),
                onPress   = { wsManager.sendCommand(spaceAction, "DOWN") },
                onRelease = { wsManager.sendCommand(spaceAction, "UP") }
            )

        } else {
            // ── Touch mode ─────────────────────────────────────────────────
            val leftAction  = if (sensorHandler.steeringMode.value == "KEYBOARD_ARROWS") "STEER_LEFT" else "STEER_A"
            val rightAction = if (sensorHandler.steeringMode.value == "KEYBOARD_ARROWS") "STEER_RIGHT" else "STEER_D"

            Row(
                modifier               = Modifier.align(Alignment.BottomStart).padding(32.dp),
                horizontalArrangement  = Arrangement.spacedBy(12.dp)
            ) {
                GamepadButton("◀ LEFT",  MaterialTheme.colorScheme.primary.copy(alpha=0.8f), Modifier.size(130.dp,80.dp),
                    { wsManager.sendCommand(leftAction, "DOWN") }, { wsManager.sendCommand(leftAction, "UP") })
                GamepadButton("RIGHT ▶", MaterialTheme.colorScheme.primary.copy(alpha=0.8f), Modifier.size(130.dp,80.dp),
                    { wsManager.sendCommand(rightAction, "DOWN") }, { wsManager.sendCommand(rightAction, "UP") })
            }
            Column(
                modifier              = Modifier.align(Alignment.BottomEnd).padding(32.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp),
                horizontalAlignment   = Alignment.End
            ) {
                GamepadButton("SPACE", Color.White.copy(alpha=0.3f), Modifier.size(160.dp,60.dp),
                    { wsManager.sendCommand(spaceAction, "DOWN") }, { wsManager.sendCommand(spaceAction, "UP") })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GamepadButton("BRAKE", Color.Red.copy(alpha=0.8f), Modifier.size(100.dp,80.dp),
                        { wsManager.sendCommand(brakeAction, "DOWN") }, { wsManager.sendCommand(brakeAction, "UP") })
                    GamepadButton("RACE", Color(0xFF43A047).copy(alpha=0.8f), Modifier.size(100.dp,80.dp),
                        { wsManager.sendCommand(accelAction, "DOWN") }, { wsManager.sendCommand(accelAction, "UP") })
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            sensorHandler         = sensorHandler,
            controllerMode        = controllerMode,
            onControllerModeChange = { controllerMode = it },
            currentBrake          = brakeAction,
            onBrakeChange         = { brakeAction = it },
            currentAccel          = accelAction,
            onAccelChange         = { accelAction = it },
            onDismiss             = { showSettings = false },
            onSave                = { mode, steerMode, brake, accel, maxAngle, dz ->
                coroutineScope.launch {
                    context.dataStore.edit { s ->
                        s[CTRL_MODE_KEY]  = mode
                        s[STEER_MODE_KEY] = steerMode
                        s[BRAKE_KEY]      = brake
                        s[ACCEL_KEY]      = accel
                        s[MAX_ANGLE_KEY]  = maxAngle
                        s[DEADZONE_KEY]   = dz
                    }
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GamepadButton(
    text: String, color: Color, modifier: Modifier = Modifier,
    onPress: () -> Unit, onRelease: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPressed) color else color.copy(alpha = color.alpha * 0.5f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true; onPress()
                    try { awaitRelease() } finally { isPressed = false; onRelease() }
                })
            }
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsDialog(
    sensorHandler: SensorHandler,
    controllerMode: String,
    onControllerModeChange: (String) -> Unit,
    currentBrake: String, onBrakeChange: (String) -> Unit,
    currentAccel: String, onAccelChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Float, Float) -> Unit
) {
    var maxAngle          by remember { mutableStateOf(sensorHandler.maxTiltAngle.value) }
    var deadzoneAngle     by remember { mutableStateOf(sensorHandler.deadzone.value) }
    var selectedSteerMode by remember { mutableStateOf(sensorHandler.steeringMode.value) }
    var selectedCtrlMode  by remember { mutableStateOf(controllerMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier            = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Control Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                HorizontalDivider()

                Text("Controller Mode", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("Motion", selectedCtrlMode == "MOTION", Modifier.weight(1f)) { selectedCtrlMode = "MOTION" }
                    MappingOption("Touch",  selectedCtrlMode == "TOUCH",  Modifier.weight(1f)) { selectedCtrlMode = "TOUCH" }
                }

                HorizontalDivider()

                Text("Steering Output", fontWeight = FontWeight.Bold)
                Text(
                    if (selectedCtrlMode == "MOTION")
                        "A/D and Arrows use PWM pulsing for proportional feel"
                    else
                        "Choose which keys to use for left/right",
                    fontSize = 11.sp, color = Color.Gray
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("A/D",    selectedSteerMode == "KEYBOARD_AD",     Modifier.weight(1f)) { selectedSteerMode = "KEYBOARD_AD" }
                    MappingOption("Arrows", selectedSteerMode == "KEYBOARD_ARROWS", Modifier.weight(1f)) { selectedSteerMode = "KEYBOARD_ARROWS" }
                    if (selectedCtrlMode == "MOTION") {
                        MappingOption("Gamepad", selectedSteerMode == "GAMEPAD", Modifier.weight(1f)) { selectedSteerMode = "GAMEPAD" }
                    } else if (selectedSteerMode == "GAMEPAD") {
                        selectedSteerMode = "KEYBOARD_AD"
                    }
                }

                if (selectedCtrlMode == "MOTION") {
                    HorizontalDivider()
                    Text("Sensitivity", fontWeight = FontWeight.Bold)
                    Text("Max tilt: ${maxAngle.toInt()}°", fontSize = 12.sp, color = Color.Gray)
                    Slider(value = maxAngle, onValueChange = { maxAngle = it }, valueRange = 10f..60f, steps = 9)
                    Text("Deadzone: ${deadzoneAngle.toInt()}°", fontSize = 12.sp, color = Color.Gray)
                    Slider(value = deadzoneAngle, onValueChange = { deadzoneAngle = it }, valueRange = 1f..15f, steps = 13)
                }

                HorizontalDivider()

                Text("Pedals Mapping", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("W/S",     currentAccel == "ACCELERATE_W",  Modifier.weight(1f)) {
                        onBrakeChange("BRAKE_S");   onAccelChange("ACCELERATE_W")
                    }
                    MappingOption("Up/Down", currentAccel == "ACCELERATE_UP", Modifier.weight(1f)) {
                        onBrakeChange("BRAKE_DOWN"); onAccelChange("ACCELERATE_UP")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        sensorHandler.maxTiltAngle.value  = maxAngle
                        sensorHandler.deadzone.value      = deadzoneAngle
                        sensorHandler.steeringMode.value  = selectedSteerMode
                        onControllerModeChange(selectedCtrlMode)
                        onSave(selectedCtrlMode, selectedSteerMode, currentBrake, currentAccel, maxAngle, deadzoneAngle)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
fun MappingOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick         = onClick,
        modifier        = modifier.heightIn(min = 40.dp),
        contentPadding  = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors          = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray
        )
    ) {
        Text(
            text      = label,
            fontSize  = 13.sp,
            maxLines  = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}