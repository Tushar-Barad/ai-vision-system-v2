import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var textToSpeech: TextToSpeech? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var toneGen: ToneGenerator? = null
    private var objectDetector: ObjectDetector? = null
    private var vibrator: Vibrator? = null

    private var currentJob: Job? = null
    private var scanJob: Job? = null
    private var currentLanguage = "en" // Track current language

    private val API_KEY = "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"

    companion object {
        const val PREFS = "BT7_Prefs"
        const val C1 = "contact1"
        const val C2 = "contact2"
        const val C3 = "contact3"
        const val REQUEST_VOICE = 100
        const val TAG = "BT7_Scanner"
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            toast("✅ All permissions granted!")
        } else {
            toast("⚠️ Please grant all permissions!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Initialize ObjectDetector
        try {
            objectDetector = ObjectDetector(this)
            Log.d(TAG, "ObjectDetector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector", e)
            objectDetector = null
        }

        // Load saved language
        currentLanguage = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"

        textToSpeech = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                setupTTS(currentLanguage)
            }
        }

        requestPerms()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BT7App()
                }
            }
        }
    }

    private fun setupTTS(langCode: String) {
        textToSpeech?.apply {
            language = when(langCode) {
                "hi" -> Locale("hi", "IN")
                "gu" -> Locale("gu", "IN")
                else -> Locale.US
            }

            // Adjust speech parameters for better quality
            when(langCode) {
                "gu" -> {
                    setSpeechRate(0.85f)  // Slightly slower for clarity
                    setPitch(1.0f)
                }
                "hi" -> {
                    setSpeechRate(0.9f)
                    setPitch(1.0f)
                }
                else -> {
                    setSpeechRate(0.9f)
                    setPitch(1.0f)
                }
            }

            // Try to select best available voice
            val bestVoice = voices?.filter {
                it.locale == this.language &&
                        it.quality >= 400  // High quality voices
            }?.firstOrNull()

            if (bestVoice != null) {
                voice = bestVoice
                Log.d(TAG, "Using high quality voice: ${bestVoice.name}")
            }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            perms.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        permLauncher.launch(perms.toTypedArray())
    }

    @Composable
    fun BT7App() {
        var connected by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        var showDialog by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showLanguageDialog by remember { mutableStateOf(true) }
        var mode by remember { mutableStateOf("Ready") }
        var img by remember { mutableStateOf<Bitmap?>(null) }
        var res by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var devName by remember { mutableStateOf("") }
        var scanning by remember { mutableStateOf(false) }
        var objects by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        // Language Selection Dialog
        if (showLanguageDialog && !connected) {
            AlertDialog(
                onDismissRequest = { },
                title = {
                    Text(
                        text = "Select Language\nભાષા પસંદ કરો\nभाषा चुनें",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column {
                        LanguageButton(
                            flag = "🇬🇧",
                            name = "English",
                            onClick = {
                                setLanguage("en")
                                showLanguageDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LanguageButton(
                            flag = "🇮🇳",
                            name = "हिंदी (Hindi)",
                            onClick = {
                                setLanguage("hi")
                                showLanguageDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LanguageButton(
                            flag = "🇮🇳",
                            name = "ગુજરાતી (Gujarati)",
                            onClick = {
                                setLanguage("gu")
                                showLanguageDialog = false
                            }
                        )
                    }
                },
                confirmButton = {}
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A1A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A3A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "🦯", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "BT7 SMART STICK",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "AI Vision Assistant",
                            fontSize = 12.sp,
                            color = Color(0xFF888888)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Developed by Tushar Barad",
                            fontSize = 11.sp,
                            color = Color(0xFF00BCD4),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "BT7.pvt.ltd - AI & IoT Integration Company",
                            fontSize = 9.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (connected) Color(0xFF1B5E20) else Color(0xFF424242)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = if (connected) "🟢" else "🔴", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (connected) "Connected" else "Not Connected",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (connected) {
                                Text(
                                    text = devName,
                                    fontSize = 11.sp,
                                    color = Color(0xFFCCCCCC)
                                )
                            }
                        }
                        if (connected) {
                            IconButton(onClick = { showSettings = true }) {
                                Text(text = "⚙️", fontSize = 20.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (connected) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (working) Color(0xFFFF6F00) else Color(0xFF1A1A3A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (working) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = mode,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (img != null) {
                                Image(
                                    bitmap = img!!.asImageBitmap(),
                                    contentDescription = "Result",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CameraView(
                                    modifier = Modifier.fillMaxSize(),
                                    onReady = { imageCapture = it }
                                )
                            }

                            if (scanning) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "🔄 SCANNING...",
                                        color = Color(0xFF4CAF50),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (objects.isNotEmpty()) {
                                        Text(
                                            text = objects,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A3A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "📋", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Result",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF333355))
                            Spacer(modifier = Modifier.height(8.dp))

                            if (res.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Press button to use features",
                                        fontSize = 14.sp,
                                        color = Color(0xFF666666),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = res,
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            cancelAll()
                            disconnect()
                            connected = false
                            mode = "Disconnected"
                            img = null
                            res = ""
                            devName = ""
                            scanning = false
                            speak("Disconnected")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "DISCONNECT",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))

                    Text(text = "🔌", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connect ESP32 to begin",
                        fontSize = 16.sp,
                        color = Color(0xFF888888)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            if (hasBTPerm()) {
                                devices = getPaired()
                                if (devices.isNotEmpty()) {
                                    showDialog = true
                                } else {
                                    toast("No paired devices!")
                                    speak("No paired devices")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "CONNECT BT7",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text(text = "Select Device", fontWeight = FontWeight.Bold) },
                    text = {
                        LazyColumn {
                            items(devices) { dev ->
                                Button(
                                    onClick = {
                                        scope.launch {
                                            mode = "Connecting..."
                                            speak("Connecting")
                                            if (connectDev(dev)) {
                                                connected = true
                                                devName = dev.name ?: "BT7"
                                                mode = "Ready"
                                                showDialog = false
                                                beep(ToneGenerator.TONE_PROP_BEEP, 100)
                                                speak("Connected. BT7 ready")
                                                startListen { cmd ->
                                                    scope.launch {
                                                        handleCmd(
                                                            cmd,
                                                            { mode = it },
                                                            { img = it },
                                                            { res = it },
                                                            { working = it },
                                                            { scanning = it },
                                                            { objects = it }
                                                        )
                                                    }
                                                }
                                            } else {
                                                mode = "Failed"
                                                beep(ToneGenerator.TONE_SUP_ERROR, 200)
                                                speak("Connection failed")
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = dev.name ?: "Unknown",
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(text = dev.address, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text(text = "Cancel")
                        }
                    }
                )
            }

            if (showSettings) {
                SettingsDialog(
                    onDismiss = { showSettings = false },
                    onSave = { c1, c2, c3, voiceGender ->
                        saveCont(c1, c2, c3)
                        saveVoiceGender(voiceGender)
                        setVoiceGender(voiceGender)
                        showSettings = false
                        toast("Settings saved!")
                    }
                )
            }
        }
    }

    @Composable
    fun LanguageButton(flag: String, name: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A1A3A)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = flag, fontSize = 32.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }

    @Composable
    fun SettingsDialog(
        onDismiss: () -> Unit,
        onSave: (String, String, String, String) -> Unit
    ) {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var c1 by remember { mutableStateOf(sp.getString(C1, "") ?: "") }
        var c2 by remember { mutableStateOf(sp.getString(C2, "") ?: "") }
        var c3 by remember { mutableStateOf(sp.getString(C3, "") ?: "") }
        var voiceGender by remember { mutableStateOf(sp.getString("voice_gender", "female") ?: "female") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = "Settings ⚙️", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = "Emergency Contacts:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = c1,
                        onValueChange = { c1 = it },
                        label = { Text(text = "Contact 1 (Primary)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = c2,
                        onValueChange = { c2 = it },
                        label = { Text(text = "Contact 2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = c3,
                        onValueChange = { c3 = it },
                        label = { Text(text = "Contact 3") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Voice:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { voiceGender = "male" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (voiceGender == "male") Color(0xFF4CAF50) else Color.Gray
                            )
                        ) {
                            Text("👨 Male")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { voiceGender = "female" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (voiceGender == "female") Color(0xFF4CAF50) else Color.Gray
                            )
                        ) {
                            Text("👩 Female")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onSave(c1, c2, c3, voiceGender) }) {
                    Text(text = "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    @Composable
    fun CameraView(modifier: Modifier, onReady: (ImageCapture) -> Unit) {
        val ctx = LocalContext.current
        val life = LocalLifecycleOwner.current

        AndroidView(
            factory = { c ->
                val view = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    onReady(imageCapture!!)
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            life,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(c))
                view
            },
            modifier = modifier
        )
    }

    private suspend fun handleCmd(
        cmd: String,
        onMode: (String) -> Unit,
        onImg: (Bitmap?) -> Unit,
        onRes: (String) -> Unit,
        onWork: (Boolean) -> Unit,
        onScan: (Boolean) -> Unit,
        onObj: (String) -> Unit
    ) {
        cancelAll()

        when (cmd) {
            "VISION" -> {
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
                onMode("👁️ Vision AI")
                speak("Vision mode")
                procVision(onImg, onRes, onWork)
                onMode("Ready")
            }
            "TEXT" -> {
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
                onMode("📝 Text Reader")
                speak("Text reader")
                procText(onImg, onRes, onWork)
                onMode("Ready")
            }
            "CURRENCY" -> {
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
                onMode("💵 Currency")
                speak("Currency mode")
                procCurr(onImg, onRes, onWork)
                onMode("Ready")
            }
            "EMERGENCY" -> {
                beep(ToneGenerator.TONE_SUP_ERROR, 300)
                onMode("🚨 EMERGENCY")
                speak("Emergency! Sending alert")
                procEmerg(onRes)
                onMode("Ready")
            }
            "ASSISTANT" -> {
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
                onMode("🤖 Voice Assistant")
                speak("Voice assistant ready")
                procVoice(onRes, onWork)
                onMode("Ready")
            }
            "NAVIGATE" -> {
                beep(ToneGenerator.TONE_PROP_BEEP, 80)
                onMode("🗺️ Navigation")
                speak("Navigation")
                procNav(onRes, onWork)
                onMode("Ready")
            }
            "SCANNER" -> {
                if (scanJob?.isActive == true) {
                    scanJob?.cancel()
                    onScan(false)
                    onMode("Ready")
                    speak("Scanner stopped")
                    beep(ToneGenerator.TONE_PROP_BEEP, 80)
                    onObj("")
                } else {
                    beep(ToneGenerator.TONE_PROP_BEEP, 80)
                    onScan(true)
                    onMode("🔄 Scanner Active")
                    speak("Scanner started")
                    onObj("Starting scanner...")
                    procScan(onObj)
                }
            }
        }
    }

    private fun getLanguagePrompt(basePrompt: String): String {
        return when(currentLanguage) {
            "gu" -> """
                તમે એક અંધ વ્યક્તિને મદદ કરી રહ્યા છો. 
                $basePrompt
                ગુજરાતીમાં જવાબ આપો.
                (You are helping a blind person. $basePrompt. Respond in Gujarati language.)
            """.trimIndent()

            "hi" -> """
                आप एक नेत्रहीन व्यक्ति की मदद कर रहे हैं।
                $basePrompt
                हिंदी में जवाब दें।
                (You are helping a blind person. $basePrompt. Respond in Hindi language.)
            """.trimIndent()

            else -> """
                You are helping a blind person.
                $basePrompt
                Respond in English language.
            """.trimIndent()
        }
    }

    private suspend fun procVision(
        onImg: (Bitmap?) -> Unit,
        onRes: (String) -> Unit,
        onWork: (Boolean) -> Unit
    ) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            val bmp = capImg()
            if (bmp != null) {
                onImg(bmp)
                val prompt = getLanguagePrompt(
                    "Describe this image in 2-3 short sentences. Focus on: important objects, people, obstacles, text visible, dangers. Be brief and practical."
                )
                val desc = callAI(bmp, prompt)
                onRes(desc)
                speak(desc)
            } else {
                onRes("Camera capture failed. Check permissions.")
                speak("Camera capture failed")
            }
            onWork(false)
            delay(2000)
            onImg(null)
        }
    }

    private suspend fun procText(
        onImg: (Bitmap?) -> Unit,
        onRes: (String) -> Unit,
        onWork: (Boolean) -> Unit
    ) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            val bmp = capImg()
            if (bmp != null) {
                onImg(bmp)
                val prompt = getLanguagePrompt(
                    "Read ALL text visible in this image. Only return the text content, nothing else. If no text found, say 'No text found'."
                )
                val txt = callAI(bmp, prompt)
                onRes("📝 Text: $txt")
                speak(txt)
            } else {
                onRes("Camera capture failed")
                speak("Camera capture failed")
            }
            onWork(false)
            delay(2000)
            onImg(null)
        }
    }

    private suspend fun procCurr(
        onImg: (Bitmap?) -> Unit,
        onRes: (String) -> Unit,
        onWork: (Boolean) -> Unit
    ) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            val bmp = capImg()
            if (bmp != null) {
                onImg(bmp)
                val prompt = getLanguagePrompt(
                    "Identify Indian Rupee currency notes in this image. Tell denomination and total amount. Example: 'One 500 rupees note, total 500 rupees'. If no currency, say 'No currency detected'."
                )
                val curr = callAI(bmp, prompt)
                onRes("💵 $curr")
                speak(curr)
            } else {
                onRes("Camera capture failed")
                speak("Camera capture failed")
            }
            onWork(false)
            delay(2000)
            onImg(null)
        }
    }

    private suspend fun procEmerg(onRes: (String) -> Unit) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            val msg =
                                "🚨 EMERGENCY! I need help! My location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                            val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            val c1 = sp.getString(C1, "")
                            val c2 = sp.getString(C2, "")
                            val c3 = sp.getString(C3, "")

                            if (!c1.isNullOrEmpty()) sendSMS(c1, msg)
                            if (!c2.isNullOrEmpty()) sendSMS(c2, msg)
                            if (!c3.isNullOrEmpty()) sendSMS(c3, msg)

                            if (!c1.isNullOrEmpty()) makeCall(c1)

                            onRes(
                                "🚨 EMERGENCY SENT!\n\n" +
                                        "Messages sent to:\n" +
                                        "${if (c1?.isNotEmpty() == true) "✓ $c1\n" else ""}" +
                                        "${if (c2?.isNotEmpty() == true) "✓ $c2\n" else ""}" +
                                        "${if (c3?.isNotEmpty() == true) "✓ $c3\n" else ""}" +
                                        "\nCalling primary contact..."
                            )
                            speak("Emergency alert sent to all contacts. Calling primary contact")
                        } else {
                            onRes("Location unavailable")
                            speak("Cannot get location")
                        }
                    }
                } else {
                    onRes("Location permission needed")
                    speak("Location permission needed")
                }
            } catch (e: Exception) {
                onRes("Emergency failed: ${e.message}")
                speak("Emergency failed")
            }
        }
    }

    private suspend fun procVoice(onRes: (String) -> Unit, onWork: (Boolean) -> Unit) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask me anything...")
            }
            try {
                startActivityForResult(intent, REQUEST_VOICE)
            } catch (e: Exception) {
                onRes("Voice recognition unavailable")
                speak("Voice recognition unavailable")
                onWork(false)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val query = results?.get(0) ?: ""
            CoroutineScope(Dispatchers.Main).launch {
                val answer = callAIText(query)
                speak(answer)
            }
        }
    }

    private suspend fun procNav(onRes: (String) -> Unit, onWork: (Boolean) -> Unit) {
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            val geo = Geocoder(this@MainActivity, Locale.getDefault())
                            val addrs = geo.getFromLocation(loc.latitude, loc.longitude, 1)
                            val addr = if (addrs?.isNotEmpty() == true) {
                                addrs[0].getAddressLine(0)
                            } else {
                                "Lat: ${loc.latitude}, Long: ${loc.longitude}"
                            }
                            onRes("📍 Your location:\n$addr")
                            speak("You are at $addr")
                        } else {
                            onRes("Location unavailable")
                            speak("Cannot get location")
                        }
                        onWork(false)
                    }
                } else {
                    onRes("Location permission needed")
                    speak("Location permission needed")
                    onWork(false)
                }
            } catch (e: Exception) {
                onRes("Navigation failed")
                speak("Navigation failed")
                onWork(false)
            }
        }
    }

    private suspend fun procScan(onObj: (String) -> Unit) {
        scanJob?.cancel()

        Log.d(TAG, "Starting scanner...")
        Log.d(TAG, "ObjectDetector status: ${if (objectDetector != null) "Initialized" else "NULL"}")

        scanJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                var scanCount = 0
                while (isActive) {
                    try {
                        scanCount++
                        Log.d(TAG, "Scan iteration: $scanCount")

                        val bmp = capImg()

                        if (bmp == null) {
                            withContext(Dispatchers.Main) {
                                onObj("⚠️ Camera not capturing. Retry...")
                            }
                            delay(1000)
                            continue
                        }

                        Log.d(TAG, "Image captured: ${bmp.width}x${bmp.height}")

                        if (objectDetector == null) {
                            withContext(Dispatchers.Main) {
                                onObj("❌ TensorFlow model not loaded")
                            }
                            Log.e(TAG, "ObjectDetector is NULL")
                            delay(3000)
                            continue
                        }

                        val detectedObjects = objectDetector!!.detectObjects(bmp)
                        Log.d(TAG, "Objects detected: ${detectedObjects.size}")

                        if (detectedObjects.isNotEmpty()) {
                            val objList = detectedObjects.joinToString(", ") {
                                "${it.label} at ${it.distance}"
                            }

                            withContext(Dispatchers.Main) {
                                onObj(objList)
                            }

                            Log.d(TAG, "Detected: $objList")

                            // Check for stairs with depth-based alerts
                            detectedObjects.forEach { obj ->
                                if (obj.label.contains("stair", true) ||
                                    obj.label.contains("stairs", true)) {

                                    when {
                                        obj.distance.contains("Very close", ignoreCase = true) -> {
                                            vibrate(1000)
                                            beep(ToneGenerator.TONE_SUP_ERROR, 500)
                                            delay(200)
                                            beep(ToneGenerator.TONE_SUP_ERROR, 500)
                                            speak("DANGER! Stairs very close! STOP!")
                                        }
                                        obj.distance.contains("Close", ignoreCase = true) -> {
                                            vibrate(500)
                                            beep(ToneGenerator.TONE_SUP_ERROR, 300)
                                            speak("Warning! Stairs close ahead!")
                                        }
                                        obj.distance.contains("Medium", ignoreCase = true) -> {
                                            vibrate(200)
                                            beep(ToneGenerator.TONE_PROP_BEEP, 200)
                                            speak("Caution. Stairs ahead")
                                        }
                                        else -> {
                                            beep(ToneGenerator.TONE_PROP_BEEP, 100)
                                        }
                                    }
                                } else if (obj.label.contains("person", true)) {
                                    if (obj.distance.contains("close", ignoreCase = true)) {
                                        beep(ToneGenerator.TONE_PROP_BEEP, 100)
                                    }
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onObj("✓ Path clear")
                            }
                            Log.d(TAG, "No objects detected")
                        }

                        delay(500)

                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e(TAG, "Scanner error in iteration $scanCount", e)
                            withContext(Dispatchers.Main) {
                                onObj("⚠️ Scanner error: ${e.message}")
                            }
                            delay(1000)
                        } else {
                            throw e
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Scanner cancelled")
                withContext(Dispatchers.Main) {
                    onObj("Scanner stopped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal scanner error", e)
                withContext(Dispatchers.Main) {
                    onObj("❌ Scanner failed: ${e.message}")
                }
            }
        }
    }

    private fun vibrate(duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    private suspend fun capImg(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (imageCapture == null) {
                Log.e(TAG, "ImageCapture is NULL")
                withContext(Dispatchers.Main) {
                    toast("Camera not initialized")
                }
                return@withContext null
            }

            var capturedBitmap: Bitmap? = null
            val latch = java.util.concurrent.CountDownLatch(1)

            imageCapture?.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            Log.d(TAG, "Image captured successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Image processing error", e)
                        } finally {
                            image.close()
                            latch.countDown()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Camera capture error", exception)
                        runOnUiThread {
                            toast("Camera error: ${exception.message}")
                        }
                        latch.countDown()
                    }
                }
            )

            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            capturedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            withContext(Dispatchers.Main) {
                toast("Capture failed: ${e.message}")
            }
            null
        }
    }

    private suspend fun callAI(bmp: Bitmap, prompt: String): String =
        withContext(Dispatchers.IO) {
            try {
                val resized = Bitmap.createScaledBitmap(bmp, 512, 512, true)
                val stream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                val url = URL("https://api.openai.com/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $API_KEY")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                val json = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$b64")
                                    })
                                })
                            })
                        })
                    })
                    put("max_tokens", 500)
                }

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json.toString())
                writer.flush()
                writer.close()

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val jsonRes = JSONObject(response)
                    jsonRes.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else {
                    val errorReader = BufferedReader(InputStreamReader(conn.errorStream))
                    val errorResponse = errorReader.readText()
                    errorReader.close()
                    "API Error ${conn.responseCode}: $errorResponse"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                "Error: ${e.message}"
            }
        }

    private suspend fun callAIText(query: String): String = withContext(Dispatchers.IO) {
        try {
            val languageInstruction = when(currentLanguage) {
                "gu" -> " Respond in Gujarati language."
                "hi" -> " Respond in Hindi language."
                else -> " Respond in English language."
            }

            val url = URL("https://api.openai.com/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            val json = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query + languageInstruction)
                    })
                })
                put("max_tokens", 200)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(json.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val jsonRes = JSONObject(response)
                jsonRes.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                "I couldn't process that question"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun sendSMS(phone: String, msg: String) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val sms = SmsManager.getDefault()
                val parts = sms.divideMessage(msg)
                sms.sendMultipartTextMessage(phone, null, parts, null, null)

                runOnUiThread {
                    toast("SMS sent to $phone")
                }
            } else {
                runOnUiThread {
                    toast("SMS permission not granted")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                toast("SMS failed: ${e.message}")
            }
        }
    }

    private fun makeCall(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone")
            }
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun beep(tone: Int, duration: Int) {
        toneGen?.startTone(tone, duration)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setLanguage(langCode: String) {
        currentLanguage = langCode
        setupTTS(langCode)

        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("language", langCode)
            .apply()

        toast("Language set to ${when(langCode) {
            "hi" -> "हिंदी"
            "gu" -> "ગુજરાતી"
            else -> "English"
        }}")
    }

    private fun setVoiceGender(gender: String) {
        textToSpeech?.voice = textToSpeech?.voices?.find { voice ->
            voice.name.contains(gender, ignoreCase = true) &&
                    voice.locale == textToSpeech?.language
        } ?: textToSpeech?.defaultVoice

        toast("Voice set to ${if (gender == "male") "Male" else "Female"}")
    }

    private fun saveVoiceGender(gender: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("voice_gender", gender)
            .apply()
    }

    private fun hasBTPerm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun getPaired(): List<BluetoothDevice> {
        if (!hasBTPerm()) return emptyList()
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private suspend fun connectDev(dev: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!hasBTPerm()) return@withContext false
                bluetoothSocket = dev.createRfcommSocketToServiceRecord(UUID_SPP)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    private fun startListen(onCmd: (String) -> Unit) {
        Thread {
            val buf = ByteArray(1024)
            while (true) {
                try {
                    val bytes = inputStream?.read(buf) ?: -1
                    if (bytes > 0) {
                        val msg = String(buf, 0, bytes).trim()
                        if (msg.isNotEmpty()) {
                            runOnUiThread { onCmd(msg) }
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
        }.start()
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun cancelAll() {
        currentJob?.cancel()
        scanJob?.cancel()
    }

    private fun saveCont(c1: String, c2: String, c3: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(C1, c1)
            putString(C2, c2)
            putString(C3, c3)
            apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAll()
        disconnect()
        cameraExecutor.shutdown()
        textToSpeech?.shutdown()
        toneGen?.release()
        objectDetector?.close()
    }
}
