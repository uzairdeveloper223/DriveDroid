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
import dev.uzair.drivedroid.R
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.asin

// State Management
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class WebSocketManager {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    var connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
    var errorMessage = mutableStateOf<String?>(null)

    fun connect(ip: String, port: String) {
        if (connectionState.value == ConnectionState.CONNECTING || connectionState.value == ConnectionState.CONNECTED) return

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
                    Log.d("Websocket", "Connected to $url")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("Websocket", "Message received: $text")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connectionState.value = ConnectionState.DISCONNECTED
                    Log.d("Websocket", "Closed: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connectionState.value = ConnectionState.DISCONNECTED
                    errorMessage.value = t.message
                    Log.e("Websocket", "Error: ${t.message}")
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

    // For button commands (pedals, handbrake)
    fun sendCommand(action: String, state: String) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        
        val json = JSONObject().apply {
            put("action", action)
            put("state", state)
        }
        webSocket?.send(json.toString())
    }
    
    // For analog steering: value from -1.0 (full left) to 1.0 (full right)
    fun sendSteer(value: Float) {
        if (connectionState.value != ConnectionState.CONNECTED) return
        
        val json = JSONObject().apply {
            put("action", "STEER")
            put("value", value.toDouble())
        }
        webSocket?.send(json.toString())
    }
}

class SensorHandler(private val context: Context, private val wsManager: WebSocketManager) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // Use Rotation Vector for highest accuracy, fall back to accelerometer
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    // Steering mode: "GAMEPAD", "KEYBOARD_AD", "KEYBOARD_ARROWS"
    var steeringMode = mutableStateOf("KEYBOARD_AD")
    
    // Max tilt angle in degrees: tilting this much = full steering lock
    var maxTiltAngle = mutableStateOf(30f)
    // Deadzone in degrees: tilts below this are treated as center
    var deadzone = mutableStateOf(3f) 

    // State tracking
    var currentSteerState = mutableStateOf("CENTER")
    
    // For UI Display: normalized -1.0 to 1.0
    var currentTilt = mutableFloatStateOf(0f)
    // Raw angle in degrees for display
    var currentAngle = mutableFloatStateOf(0f)
    
    // Track last sent value to reduce spam (for gamepad mode)
    private var lastSentValue = 0f
    // Track last keyboard steer direction (for keyboard mode)
    private var lastKeySteerDir = "CENTER"
    
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        // Priority: Rotation Vector > Gravity > Accelerometer
        val sensor = rotationVectorSensor ?: gravitySensor ?: accelerometer
        val sensorName = when (sensor) {
            rotationVectorSensor -> "Rotation Vector (best)"
            gravitySensor -> "Gravity (good)"
            accelerometer -> "Accelerometer (basic)"
            else -> "None"
        }
        Log.i("SensorHandler", "Using sensor: $sensorName")
        
        sensor?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        // Release any held keys in keyboard mode
        releaseKeyboardSteering()
        // Center steering in gamepad mode
        wsManager.sendSteer(0f)
        currentTilt.floatValue = 0f
        currentAngle.floatValue = 0f
        currentSteerState.value = "CENTER"
        lastSentValue = 0f
        lastKeySteerDir = "CENTER"
    }
    
    private fun getKeyActions(): Pair<String, String> {
        return when (steeringMode.value) {
            "KEYBOARD_AD" -> Pair("STEER_A", "STEER_D")
            "KEYBOARD_ARROWS" -> Pair("STEER_LEFT", "STEER_RIGHT")
            else -> Pair("STEER_A", "STEER_D") // fallback
        }
    }
    
    private fun releaseKeyboardSteering() {
        val (leftAction, rightAction) = getKeyActions()
        if (lastKeySteerDir == "LEFT") wsManager.sendCommand(leftAction, "UP")
        if (lastKeySteerDir == "RIGHT") wsManager.sendCommand(rightAction, "UP")
        lastKeySteerDir = "CENTER"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        var rollDegrees: Float
        
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rollDegrees = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            }
            Sensor.TYPE_GRAVITY -> {
                val gx = event.values[0]
                val gz = event.values[2]
                rollDegrees = Math.toDegrees(Math.atan2(gx.toDouble(), gz.toDouble())).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val yValue = event.values[1]
                rollDegrees = (yValue / 9.81f * 90f)
            }
            else -> return
        }
        
        // Adjust for screen rotation
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        
        when (rotation) {
            android.view.Surface.ROTATION_90 -> { }
            android.view.Surface.ROTATION_270 -> { rollDegrees = -rollDegrees } 
            else -> { }
        }
        
        currentAngle.floatValue = rollDegrees
        
        // Apply deadzone
        val deadzoneVal = deadzone.value
        val maxAngle = maxTiltAngle.value
        
        val steerValue: Float = if (abs(rollDegrees) < deadzoneVal) {
            0f
        } else {
            val sign = if (rollDegrees > 0) 1f else -1f
            val magnitude = (abs(rollDegrees) - deadzoneVal) / (maxAngle - deadzoneVal)
            (sign * magnitude).coerceIn(-1f, 1f)
        }
        
        currentTilt.floatValue = steerValue
        
        // Update state text
        val newState = when {
            steerValue > 0.05f -> "RIGHT"
            steerValue < -0.05f -> "LEFT"
            else -> "CENTER"
        }
        currentSteerState.value = newState
        
        // Send based on mode
        when (steeringMode.value) {
            "GAMEPAD" -> {
                // Analog: send float to gamepad axis
                if (abs(steerValue - lastSentValue) > 0.02f) {
                    wsManager.sendSteer(steerValue)
                    lastSentValue = steerValue
                }
            }
            "KEYBOARD_AD", "KEYBOARD_ARROWS" -> {
                // Binary: press/release keyboard keys
                if (newState != lastKeySteerDir) {
                    val (leftAction, rightAction) = getKeyActions()
                    
                    // Release old direction
                    if (lastKeySteerDir == "LEFT") wsManager.sendCommand(leftAction, "UP")
                    if (lastKeySteerDir == "RIGHT") wsManager.sendCommand(rightAction, "UP")
                    
                    // Press new direction
                    if (newState == "LEFT") wsManager.sendCommand(leftAction, "DOWN")
                    if (newState == "RIGHT") wsManager.sendCommand(rightAction, "DOWN")
                    
                    lastKeySteerDir = newState
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
}

// Extension to create DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    private val wsManager = WebSocketManager()
    private lateinit var sensorHandler: SensorHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable immersive full-screen mode for Android 15/16+
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Keep screen on since it's a controller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        sensorHandler = SensorHandler(this, wsManager)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF64B5F6), // Blue
                    background = Color(0xFF121212), // Dark
                    surface = Color(0xFF1E1E1E), // Slightly lighter dark
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val connState by wsManager.connectionState
                    
                    if (connState == ConnectionState.DISCONNECTED || connState == ConnectionState.CONNECTING) {
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
        if (wsManager.connectionState.value == ConnectionState.CONNECTED) {
            sensorHandler.start()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorHandler.stop()
    }
}

@Composable
fun ConnectionScreen(wsManager: WebSocketManager, context: Context) {
    // Keys for DataStore
    val IP_KEY = stringPreferencesKey("saved_ip")
    val PORT_KEY = stringPreferencesKey("saved_port")
    val coroutineScope = rememberCoroutineScope()

    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    
    // Load saved preferences on launch
    LaunchedEffect(Unit) {
        context.dataStore.data.collect { preferences ->
            preferences[IP_KEY]?.let { ip = it }
            preferences[PORT_KEY]?.let { port = it }
        }
    }
    
    val connState by wsManager.connectionState
    val errorMsg by wsManager.errorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_drivedroid),
                contentDescription = "DriveDroid logo",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 12.dp)
            )
            Text(
                text = "DriveDroid",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Server IP Address") },
            placeholder = { Text("e.g. 192.168.1.5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { 
                // Save to DataStore before connecting
                coroutineScope.launch {
                    context.dataStore.edit { settings ->
                        settings[IP_KEY] = ip
                        settings[PORT_KEY] = port
                    }
                }
                wsManager.connect(ip, port) 
            },
            enabled = ip.isNotBlank() && connState != ConnectionState.CONNECTING,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(56.dp)
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
            Text(
                text = it,
                color = Color.Red,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun ControllerScreen(wsManager: WebSocketManager, sensorHandler: SensorHandler, context: Context) {
    // DataStore keys for persistent settings
    val CTRL_MODE_KEY = stringPreferencesKey("controller_mode")
    val STEER_MODE_KEY = stringPreferencesKey("steering_mode")
    val BRAKE_KEY = stringPreferencesKey("brake_action")
    val ACCEL_KEY = stringPreferencesKey("accel_action")
    val MAX_ANGLE_KEY = floatPreferencesKey("max_tilt_angle")
    val DEADZONE_KEY = floatPreferencesKey("deadzone")
    val coroutineScope = rememberCoroutineScope()
    
    var showSettings by remember { mutableStateOf(false) }
    
    // Controller mode: "MOTION" or "TOUCH"
    var controllerMode by remember { mutableStateOf("MOTION") }
    
    // Configurable action states
    var brakeAction by remember { mutableStateOf("BRAKE_S") }
    var accelAction by remember { mutableStateOf("ACCELERATE_W") }
    var spaceAction by remember { mutableStateOf("HANDBRAKE") }
    
    // Load saved settings once
    LaunchedEffect(Unit) {
        context.dataStore.data.collect { prefs ->
            prefs[CTRL_MODE_KEY]?.let { controllerMode = it }
            prefs[STEER_MODE_KEY]?.let { sensorHandler.steeringMode.value = it }
            prefs[BRAKE_KEY]?.let { brakeAction = it }
            prefs[ACCEL_KEY]?.let { accelAction = it }
            prefs[MAX_ANGLE_KEY]?.let { sensorHandler.maxTiltAngle.value = it }
            prefs[DEADZONE_KEY]?.let { sensorHandler.deadzone.value = it }
        }
    }
    
    // Start/stop sensor based on controller mode
    LaunchedEffect(controllerMode) {
        if (controllerMode == "MOTION") {
            sensorHandler.start()
        } else {
            sensorHandler.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        
        // Top right: Settings + Disconnect
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showSettings = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text("⚙ Settings")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { 
                    sensorHandler.stop()
                    wsManager.disconnect() 
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha=0.7f))
            ) {
                Text("Disconnect")
            }
        }

        if (controllerMode == "MOTION") {
            // ===== MOTION MODE =====
            // Steering indicator at top center
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val tilt by sensorHandler.currentTilt
                val angle by sensorHandler.currentAngle
                val dir by sensorHandler.currentSteerState
                
                val percentage = (abs(tilt) * 100).toInt()
                Text(
                    "Steering: $dir ${if (dir != "CENTER") "${percentage}%" else ""}",
                    fontWeight = FontWeight.Bold, 
                    color = Color.LightGray
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val barWidth = 250.dp
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(12.dp)
                        .background(Color.DarkGray, RoundedCornerShape(6.dp))
                ) {
                    val offsetDp = (barWidth / 2) * tilt
                    val indicatorColor = when {
                        abs(tilt) > 0.7f -> Color(0xFFFF5722)
                        abs(tilt) > 0.3f -> MaterialTheme.colorScheme.primary
                        dir == "CENTER" -> Color.Green
                        else -> Color(0xFF81C784)
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .offset(x = (barWidth / 2) + offsetDp - 6.dp)
                            .background(indicatorColor, RoundedCornerShape(6.dp))
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Text("${String.format("%.1f", angle)}°", fontSize = 10.sp, color = Color.Gray)
            }

            // Brake (Left Bottom)
            GamepadButton(
                text = "BRAKE",
                color = Color.Red.copy(alpha=0.8f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .size(120.dp, 100.dp),
                onPress = { wsManager.sendCommand(brakeAction, "DOWN") },
                onRelease = { wsManager.sendCommand(brakeAction, "UP") }
            )

            // Accelerate (Right Bottom)
            GamepadButton(
                text = "RACE",
                color = Color(0xFF43A047).copy(alpha=0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .size(120.dp, 100.dp),
                onPress = { wsManager.sendCommand(accelAction, "DOWN") },
                onRelease = { wsManager.sendCommand(accelAction, "UP") }
            )

            // Space / Handbrake (Center Bottom)
            GamepadButton(
                text = "SPACE",
                color = Color.White.copy(alpha=0.3f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(160.dp, 80.dp),
                onPress = { wsManager.sendCommand(spaceAction, "DOWN") },
                onRelease = { wsManager.sendCommand(spaceAction, "UP") }
            )
        } else {
            // ===== TOUCH MODE =====
            val leftAction = if (sensorHandler.steeringMode.value == "KEYBOARD_ARROWS") "STEER_LEFT" else "STEER_A"
            val rightAction = if (sensorHandler.steeringMode.value == "KEYBOARD_ARROWS") "STEER_RIGHT" else "STEER_D"

            // LEFT / RIGHT steering buttons on the left side, next to each other
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GamepadButton(
                    text = "◀ LEFT",
                    color = MaterialTheme.colorScheme.primary.copy(alpha=0.8f),
                    modifier = Modifier.size(130.dp, 80.dp),
                    onPress = { wsManager.sendCommand(leftAction, "DOWN") },
                    onRelease = { wsManager.sendCommand(leftAction, "UP") }
                )
                GamepadButton(
                    text = "RIGHT ▶",
                    color = MaterialTheme.colorScheme.primary.copy(alpha=0.8f),
                    modifier = Modifier.size(130.dp, 80.dp),
                    onPress = { wsManager.sendCommand(rightAction, "DOWN") },
                    onRelease = { wsManager.sendCommand(rightAction, "UP") }
                )
            }
            
            // Right side: SPACE on top, RACE below, BRAKE next to RACE
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Space on top
                GamepadButton(
                    text = "SPACE",
                    color = Color.White.copy(alpha=0.3f),
                    modifier = Modifier.size(160.dp, 60.dp),
                    onPress = { wsManager.sendCommand(spaceAction, "DOWN") },
                    onRelease = { wsManager.sendCommand(spaceAction, "UP") }
                )
                // BRAKE and RACE side by side
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GamepadButton(
                        text = "BRAKE",
                        color = Color.Red.copy(alpha=0.8f),
                        modifier = Modifier.size(100.dp, 80.dp),
                        onPress = { wsManager.sendCommand(brakeAction, "DOWN") },
                        onRelease = { wsManager.sendCommand(brakeAction, "UP") }
                    )
                    GamepadButton(
                        text = "RACE",
                        color = Color(0xFF43A047).copy(alpha=0.8f),
                        modifier = Modifier.size(100.dp, 80.dp),
                        onPress = { wsManager.sendCommand(accelAction, "DOWN") },
                        onRelease = { wsManager.sendCommand(accelAction, "UP") }
                    )
                }
            }
        }
    }
    
    if (showSettings) {
        SettingsDialog(
            sensorHandler = sensorHandler,
            controllerMode = controllerMode,
            onControllerModeChange = { controllerMode = it },
            currentBrake = brakeAction,
            onBrakeChange = { brakeAction = it },
            currentAccel = accelAction,
            onAccelChange = { accelAction = it },
            onDismiss = { showSettings = false },
            onSave = { mode, steerMode, brake, accel, maxAngle, deadzone ->
                // Save all settings to DataStore
                coroutineScope.launch {
                    context.dataStore.edit { settings ->
                        settings[CTRL_MODE_KEY] = mode
                        settings[STEER_MODE_KEY] = steerMode
                        settings[BRAKE_KEY] = brake
                        settings[ACCEL_KEY] = accel
                        settings[MAX_ANGLE_KEY] = maxAngle
                        settings[DEADZONE_KEY] = deadzone
                    }
                }
            }
        )
    }
}

@Composable
fun GamepadButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPressed) color else color.copy(alpha = color.alpha * 0.5f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress()
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                            onRelease()
                        }
                    }
                )
            }
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
fun SettingsDialog(
    sensorHandler: SensorHandler,
    controllerMode: String,
    onControllerModeChange: (String) -> Unit,
    currentBrake: String,
    onBrakeChange: (String) -> Unit,
    currentAccel: String,
    onAccelChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Float, Float) -> Unit
) {
    var maxAngle by remember { mutableStateOf(sensorHandler.maxTiltAngle.value) }
    var deadzoneAngle by remember { mutableStateOf(sensorHandler.deadzone.value) }
    var selectedSteerMode by remember { mutableStateOf(sensorHandler.steeringMode.value) }
    var selectedCtrlMode by remember { mutableStateOf(controllerMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Control Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                
                HorizontalDivider()
                
                // Controller Mode
                Text("Controller Mode", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("Motion", selectedCtrlMode == "MOTION", Modifier.weight(1f)) {
                        selectedCtrlMode = "MOTION"
                    }
                    MappingOption("Touch", selectedCtrlMode == "TOUCH", Modifier.weight(1f)) {
                        selectedCtrlMode = "TOUCH"
                    }
                }
                
                HorizontalDivider()
                
                // Steering Output (Available to both)
                Text("Steering Output", fontWeight = FontWeight.Bold)
                Text("Choose based on your game's input support", fontSize = 11.sp, color = Color.Gray)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("A/D", selectedSteerMode == "KEYBOARD_AD", Modifier.weight(1f)) {
                        selectedSteerMode = "KEYBOARD_AD"
                    }
                    MappingOption("Arrows", selectedSteerMode == "KEYBOARD_ARROWS", Modifier.weight(1f)) {
                        selectedSteerMode = "KEYBOARD_ARROWS"
                    }
                    if (selectedCtrlMode == "MOTION") {
                        MappingOption("Gamepad", selectedSteerMode == "GAMEPAD", Modifier.weight(1f)) {
                            selectedSteerMode = "GAMEPAD"
                        }
                    } else if (selectedSteerMode == "GAMEPAD") {
                        // Force back to A/D if they select Touch mode, since Gamepad analog doesn't make sense for touch buttons
                        selectedSteerMode = "KEYBOARD_AD"
                    }
                }
                
                // Show motion-specific settings only in MOTION mode
                if (selectedCtrlMode == "MOTION") {
                    HorizontalDivider()
                    
                    // Steering Sensitivity
                    Text("Sensitivity", fontWeight = FontWeight.Bold)
                    Text("Max tilt: ${maxAngle.toInt()}°", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = maxAngle,
                        onValueChange = { maxAngle = it },
                        valueRange = 10f..60f,
                        steps = 9
                    )
                    
                    Text("Deadzone: ${deadzoneAngle.toInt()}°", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = deadzoneAngle,
                        onValueChange = { deadzoneAngle = it },
                        valueRange = 1f..15f,
                        steps = 13
                    )
                }
                
                HorizontalDivider()
                
                // Pedals Selection
                Text("Pedals Mapping", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingOption("W/S", currentAccel == "ACCELERATE_W", Modifier.weight(1f)) {
                        onBrakeChange("BRAKE_S")
                        onAccelChange("ACCELERATE_W")
                    }
                    MappingOption("Up/Down", currentAccel == "ACCELERATE_UP", Modifier.weight(1f)) {
                        onBrakeChange("BRAKE_DOWN")
                        onAccelChange("ACCELERATE_UP")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        sensorHandler.maxTiltAngle.value = maxAngle
                        sensorHandler.deadzone.value = deadzoneAngle
                        sensorHandler.steeringMode.value = selectedSteerMode
                        onControllerModeChange(selectedCtrlMode)
                        onSave(selectedCtrlMode, selectedSteerMode, currentBrake, currentAccel, maxAngle, deadzoneAngle)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
fun MappingOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray
        )
    ) {
        androidx.compose.material3.Text(
            text = label, 
            fontSize = 13.sp, 
            maxLines = 1, 
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

