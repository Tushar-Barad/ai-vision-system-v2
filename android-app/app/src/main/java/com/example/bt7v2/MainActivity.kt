package com.example.bt7v2

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
    private var currentLanguage = "en"

    // IMPORTANT: Replace with your own OpenAI API key.
    // Never commit real API keys to version control.
    // Consider using local.properties or environment variables.
    private val API_KEY = "YOUR_OPENAI_API_KEY_HERE"

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
        if (perms.values.all { it }) toast("All permissions granted!")
        else toast("Please grant all permissions!")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter
        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try {
            objectDetector = ObjectDetector(this)
            Log.d(TAG, "ObjectDetector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector init failed", e)
            objectDetector = null
        }
        currentLanguage = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", "en") ?: "en"
        textToSpeech = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) setupTTS(currentLanguage) }
        requestPerms()
        setContent { MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { BT7App() } } }
    }

    private fun setupTTS(langCode: String) {
        textToSpeech?.apply {
            language = when (langCode) { "hi" -> Locale("hi","IN"); "gu" -> Locale("gu","IN"); else -> Locale.US }
            when (langCode) { "gu" -> { setSpeechRate(0.85f); setPitch(1.0f) }; else -> { setSpeechRate(0.9f); setPitch(1.0f) } }
            voices?.filter { it.locale == this.language && it.quality >= 400 }?.firstOrNull()?.let { voice = it }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO, Manifest.permission.VIBRATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            perms.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        else perms.addAll(listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
        permLauncher.launch(perms.toTypedArray())
    }

    @Composable fun BT7App() {
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

        if (showLanguageDialog && !connected) {
            AlertDialog(onDismissRequest = {},
                title = { Text("Select Language\nભાષા પસંદ કરો\nभाषा चुनें", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                text = {
                    Column {
                        LanguageButton("English") { setLanguage("en"); showLanguageDialog = false }
                        Spacer(Modifier.height(12.dp))
                        LanguageButton("Hindi / हिंदी") { setLanguage("hi"); showLanguageDialog = false }
                        Spacer(Modifier.height(12.dp))
                        LanguageButton("Gujarati / ગુજરાતી") { setLanguage("gu"); showLanguageDialog = false }
                    }
                }, confirmButton = {})
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A1A))) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A3A)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🦯", fontSize = 40.sp)
                        Text("BT7 SMART STICK", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        Text("AI Vision Assistant", fontSize = 12.sp, color = Color(0xFF888888))
                        Spacer(Modifier.height(8.dp))
                        Text("Developed by Tushar Barad", fontSize = 11.sp, color = Color(0xFF00BCD4), fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (connected) Color(0xFF1B5E20) else Color(0xFF424242)),
                    shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (connected) "🟢" else "🔴", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (connected) "Connected" else "Not Connected", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (connected) Text(devName, fontSize = 11.sp, color = Color(0xFFCCCCCC))
                        }
                        if (connected) IconButton(onClick = { showSettings = true }) { Text("⚙️", fontSize = 20.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (connected) {
                    Card(Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (working) Color(0xFFFF6F00) else Color(0xFF1A1A3A)),
                        shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            if (working) { CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
                            Text(mode, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth().height(240.dp), colors = CardDefaults.cardColors(containerColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            if (img != null) Image(img!!.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
                            else CameraView(Modifier.fillMaxSize()) { imageCapture = it }
                            if (scanning) {
                                Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
                                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🔄 SCANNING...", color = Color(0xFF4CAF50), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    if (objects.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(objects, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A3A)), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📋", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                                Text("Result", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            }
                            Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Color(0xFF333355)); Spacer(Modifier.height(8.dp))
                            if (res.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Press button to use features", fontSize = 14.sp, color = Color(0xFF666666), textAlign = TextAlign.Center) }
                            else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Text(res, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { cancelAll(); disconnect(); connected=false; mode="Disconnected"; img=null; res=""; devName=""; scanning=false; speak("Disconnected") },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), shape = RoundedCornerShape(12.dp)) {
                        Text("DISCONNECT", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("🔌", fontSize = 64.sp); Spacer(Modifier.height(16.dp))
                    Text("Connect ESP32 to begin", fontSize = 16.sp, color = Color(0xFF888888))
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (hasBTPerm()) { devices = getPaired(); if (devices.isNotEmpty()) showDialog=true else { toast("No paired devices!"); speak("No paired devices") } }
                    }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(12.dp)) {
                        Text("CONNECT BT7", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (showDialog) {
                AlertDialog(onDismissRequest = { showDialog=false },
                    title = { Text("Select Device", fontWeight = FontWeight.Bold) },
                    text = { LazyColumn { items(devices) { dev ->
                        Button(onClick = { scope.launch {
                            mode="Connecting..."; speak("Connecting")
                            if (connectDev(dev)) {
                                connected=true; devName=dev.name?:"BT7"; mode="Ready"; showDialog=false
                                beep(ToneGenerator.TONE_PROP_BEEP,100); speak("Connected. BT7 ready")
                                startListen { cmd -> scope.launch { handleCmd(cmd,{mode=it},{img=it},{res=it},{working=it},{scanning=it},{objects=it}) } }
                            } else { mode="Failed"; beep(ToneGenerator.TONE_SUP_ERROR,200); speak("Connection failed") }
                        } }, modifier = Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                            Column(Modifier.padding(8.dp)) { Text(dev.name?:"Unknown", fontWeight=FontWeight.Bold); Text(dev.address, fontSize=11.sp) }
                        }
                    } } },
                    confirmButton = {}, dismissButton = { TextButton(onClick={showDialog=false}){Text("Cancel")} })
            }
            if (showSettings) {
                SettingsDialog(onDismiss={showSettings=false}) { c1,c2,c3,g -> saveCont(c1,c2,c3); saveVoiceGender(g); setVoiceGender(g); showSettings=false; toast("Settings saved!") }
            }
        }
    }

    @Composable fun LanguageButton(name: String, onClick: () -> Unit) {
        Button(onClick=onClick, modifier=Modifier.fillMaxWidth().height(60.dp), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF1A1A3A))) {
            Text(name, fontSize=18.sp, fontWeight=FontWeight.Bold, color=Color.White)
        }
    }

    @Composable fun SettingsDialog(onDismiss: () -> Unit, onSave: (String,String,String,String)->Unit) {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var c1 by remember { mutableStateOf(sp.getString(C1,"")?:"") }
        var c2 by remember { mutableStateOf(sp.getString(C2,"")?:"") }
        var c3 by remember { mutableStateOf(sp.getString(C3,"")?:"") }
        var vg by remember { mutableStateOf(sp.getString("voice_gender","female")?:"female") }
        AlertDialog(onDismissRequest=onDismiss, title={Text("Settings ⚙️", fontWeight=FontWeight.Bold)},
            text={ Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Emergency Contacts:", fontSize=16.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(12.dp))
                OutlinedTextField(c1,{c1=it},label={Text("Contact 1 (Primary)")},modifier=Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                OutlinedTextField(c2,{c2=it},label={Text("Contact 2")},modifier=Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                OutlinedTextField(c3,{c3=it},label={Text("Contact 3")},modifier=Modifier.fillMaxWidth()); Spacer(Modifier.height(16.dp))
                Text("Voice:", fontSize=16.sp, fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(onClick={vg="male"},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=if(vg=="male")Color(0xFF4CAF50) else Color.Gray)){Text("Male")}
                    Spacer(Modifier.width(8.dp))
                    Button(onClick={vg="female"},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=if(vg=="female")Color(0xFF4CAF50) else Color.Gray)){Text("Female")}
                }
            }},
            confirmButton={Button(onClick={onSave(c1,c2,c3,vg)}){Text("Save")}},
            dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
    }

    @Composable fun CameraView(modifier: Modifier, onReady: (ImageCapture)->Unit) {
        val life = LocalLifecycleOwner.current
        AndroidView(factory={c->
            val view=PreviewView(c)
            ProcessCameraProvider.getInstance(c).also{f->f.addListener({
                val prov=f.get()
                val prev=Preview.Builder().build().also{it.setSurfaceProvider(view.surfaceProvider)}
                imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                onReady(imageCapture!!)
                try{prov.unbindAll();prov.bindToLifecycle(life,CameraSelector.DEFAULT_BACK_CAMERA,prev,imageCapture)}catch(e:Exception){e.printStackTrace()}
            },ContextCompat.getMainExecutor(c))}
            view
        },modifier=modifier)
    }

    private suspend fun handleCmd(cmd:String,onMode:(String)->Unit,onImg:(Bitmap?)->Unit,onRes:(String)->Unit,onWork:(Boolean)->Unit,onScan:(Boolean)->Unit,onObj:(String)->Unit) {
        cancelAll()
        when(cmd) {
            "VISION"    -> { beep(ToneGenerator.TONE_PROP_BEEP,80); onMode("Vision AI"); speak("Vision mode"); procVision(onImg,onRes,onWork); onMode("Ready") }
            "TEXT"      -> { beep(ToneGenerator.TONE_PROP_BEEP,80); onMode("Text Reader"); speak("Text reader"); procText(onImg,onRes,onWork); onMode("Ready") }
            "CURRENCY"  -> { beep(ToneGenerator.TONE_PROP_BEEP,80); onMode("Currency"); speak("Currency mode"); procCurr(onImg,onRes,onWork); onMode("Ready") }
            "EMERGENCY" -> { beep(ToneGenerator.TONE_SUP_ERROR,300); onMode("EMERGENCY"); speak("Emergency! Sending alert"); procEmerg(onRes); onMode("Ready") }
            "ASSISTANT" -> { beep(ToneGenerator.TONE_PROP_BEEP,80); onMode("Voice Assistant"); speak("Voice assistant ready"); procVoice(onRes,onWork); onMode("Ready") }
            "NAVIGATE"  -> { beep(ToneGenerator.TONE_PROP_BEEP,80); onMode("Navigation"); speak("Navigation"); procNav(onRes,onWork); onMode("Ready") }
            "SCANNER"   -> {
                if (scanJob?.isActive==true) { scanJob?.cancel(); onScan(false); onMode("Ready"); speak("Scanner stopped"); onObj("") }
                else { beep(ToneGenerator.TONE_PROP_BEEP,80); onScan(true); onMode("Scanner Active"); speak("Scanner started"); onObj("Starting..."); procScan(onObj) }
            }
        }
    }

    private fun getLanguagePrompt(base: String) = when(currentLanguage) {
        "gu" -> "You are helping a blind person. $base Respond in Gujarati."
        "hi" -> "You are helping a blind person. $base Respond in Hindi."
        else -> "You are helping a blind person. $base Respond in English."
    }

    private suspend fun procVision(onImg:(Bitmap?)->Unit,onRes:(String)->Unit,onWork:(Boolean)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            onWork(true); val bmp=capImg()
            if(bmp!=null){onImg(bmp);val d=callAI(bmp,getLanguagePrompt("Describe this image in 2-3 short sentences. Focus on objects, people, obstacles, text, dangers. Be brief and practical."));onRes(d);speak(d)}
            else{onRes("Camera capture failed.");speak("Camera capture failed")}
            onWork(false);delay(2000);onImg(null)
        }
    }

    private suspend fun procText(onImg:(Bitmap?)->Unit,onRes:(String)->Unit,onWork:(Boolean)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            onWork(true); val bmp=capImg()
            if(bmp!=null){onImg(bmp);val t=callAI(bmp,getLanguagePrompt("Read ALL text visible in this image. Return only the text. If no text, say 'No text found'."));onRes("Text: $t");speak(t)}
            else{onRes("Camera capture failed");speak("Camera capture failed")}
            onWork(false);delay(2000);onImg(null)
        }
    }

    private suspend fun procCurr(onImg:(Bitmap?)->Unit,onRes:(String)->Unit,onWork:(Boolean)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            onWork(true); val bmp=capImg()
            if(bmp!=null){onImg(bmp);val c=callAI(bmp,getLanguagePrompt("Identify Indian Rupee notes. State denomination and total. If none, say 'No currency detected'."));onRes(c);speak(c)}
            else{onRes("Camera capture failed");speak("Camera capture failed")}
            onWork(false);delay(2000);onImg(null)
        }
    }

    private suspend fun procEmerg(onRes:(String)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            try {
                if(ActivityCompat.checkSelfPermission(this@MainActivity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener{loc->{
                        if(loc!=null){
                            val msg="EMERGENCY! I need help! Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                            val sp=getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                            val c1=sp.getString(C1,"");val c2=sp.getString(C2,"");val c3=sp.getString(C3,"")
                            if(!c1.isNullOrEmpty())sendSMS(c1,msg);if(!c2.isNullOrEmpty())sendSMS(c2,msg);if(!c3.isNullOrEmpty())sendSMS(c3,msg)
                            if(!c1.isNullOrEmpty())makeCall(c1)
                            onRes("EMERGENCY SENT!\nCalling primary contact...");speak("Emergency alert sent. Calling primary contact")
                        }else{onRes("Location unavailable");speak("Cannot get location")}
                    }
                }else{onRes("Location permission needed");speak("Location permission needed")}
            }catch(e:Exception){onRes("Emergency failed: ${e.message}");speak("Emergency failed")}
        }
    }

    private suspend fun procVoice(onRes:(String)->Unit,onWork:(Boolean)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PROMPT,"Ask me anything...")}
            try{startActivityForResult(i,REQUEST_VOICE)}catch(e:Exception){onRes("Voice recognition unavailable");speak("Voice recognition unavailable");onWork(false)}
        }
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_VOICE&&resultCode==RESULT_OK){
            val q=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)?:""
            CoroutineScope(Dispatchers.Main).launch{speak(callAIText(q))}
        }
    }

    private suspend fun procNav(onRes:(String)->Unit,onWork:(Boolean)->Unit) {
        currentJob=CoroutineScope(Dispatchers.Main).launch {
            onWork(true)
            try {
                if(ActivityCompat.checkSelfPermission(this@MainActivity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                    fusedLocationClient.lastLocation.addOnSuccessListener{loc->{
                        if(loc!=null){
                            val geo=Geocoder(this@MainActivity,Locale.getDefault())
                            val a=geo.getFromLocation(loc.latitude,loc.longitude,1)
                            val addr=if(a?.isNotEmpty()==true)a[0].getAddressLine(0) else "Lat:${loc.latitude}, Lon:${loc.longitude}"
                            onRes("Your location:\n$addr");speak("You are at $addr")
                        }else{onRes("Location unavailable");speak("Cannot get location")}
                        onWork(false)
                    }
                }else{onRes("Location permission needed");speak("Location permission needed");onWork(false)}
            }catch(e:Exception){onRes("Navigation failed");speak("Navigation failed");onWork(false)}
        }
    }

    private suspend fun procScan(onObj:(String)->Unit) {
        scanJob?.cancel()
        scanJob=CoroutineScope(Dispatchers.Default).launch {
            try {
                while(isActive){
                    val bmp=capImg()
                    if(bmp==null){withContext(Dispatchers.Main){onObj("Camera not ready. Retry...")};delay(1000);continue}
                    if(objectDetector==null){withContext(Dispatchers.Main){onObj("TFLite model not loaded")};delay(3000);continue}
                    val found=objectDetector!!.detectObjects(bmp)
                    if(found.isNotEmpty()){
                        withContext(Dispatchers.Main){onObj(found.joinToString(", "){"${it.label} at ${it.distance}"})}
                        found.forEach{obj->{
                            if(obj.label.contains("stair",true)) when {
                                obj.distance.contains("Very close",true)->{vibrate(1000);beep(ToneGenerator.TONE_SUP_ERROR,500);delay(200);beep(ToneGenerator.TONE_SUP_ERROR,500);speak("DANGER! Stairs very close! STOP!")}
                                obj.distance.contains("Close",true)->{vibrate(500);beep(ToneGenerator.TONE_SUP_ERROR,300);speak("Warning! Stairs close ahead!")}
                                obj.distance.contains("Medium",true)->{vibrate(200);beep(ToneGenerator.TONE_PROP_BEEP,200);speak("Caution. Stairs ahead")}
                                else->beep(ToneGenerator.TONE_PROP_BEEP,100)
                            }
                            else if(obj.label.contains("person",true)&&obj.distance.contains("close",true)) beep(ToneGenerator.TONE_PROP_BEEP,100)
                        }
                    }else withContext(Dispatchers.Main){onObj("Path clear")}
                    delay(500)
                }
            }catch(e:CancellationException){withContext(Dispatchers.Main){onObj("Scanner stopped")}}
            catch(e:Exception){withContext(Dispatchers.Main){onObj("Scanner error: ${e.message}")}}
        }
    }

    private fun vibrate(d:Long){try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)vibrator?.vibrate(VibrationEffect.createOneShot(d,VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION")vibrator?.vibrate(d)}catch(e:Exception){Log.e(TAG,"Vibration error",e)}}

    private suspend fun capImg():Bitmap?=withContext(Dispatchers.IO){
        try{
            if(imageCapture==null){withContext(Dispatchers.Main){toast("Camera not initialized")};return@withContext null}
            var bmp:Bitmap?=null;val latch=java.util.concurrent.CountDownLatch(1)
            imageCapture?.takePicture(cameraExecutor,object:ImageCapture.OnImageCapturedCallback(){
                override fun onCaptureSuccess(image:ImageProxy){try{val buf=image.planes[0].buffer;val b=ByteArray(buf.remaining());buf.get(b);bmp=BitmapFactory.decodeByteArray(b,0,b.size)}catch(e:Exception){Log.e(TAG,"Image error",e)}finally{image.close();latch.countDown()}}
                override fun onError(e:ImageCaptureException){Log.e(TAG,"Capture error",e);runOnUiThread{toast("Camera error: ${e.message}")};latch.countDown()}
            })
            latch.await(3,java.util.concurrent.TimeUnit.SECONDS);bmp
        }catch(e:Exception){withContext(Dispatchers.Main){toast("Capture failed: ${e.message}")};null}
    }

    private suspend fun callAI(bmp:Bitmap,prompt:String):String=withContext(Dispatchers.IO){
        try{
            val s=ByteArrayOutputStream();Bitmap.createScaledBitmap(bmp,512,512,true).compress(Bitmap.CompressFormat.JPEG,80,s)
            val b64=Base64.encodeToString(s.toByteArray(),Base64.NO_WRAP)
            val conn=(URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply{requestMethod="POST";setRequestProperty("Content-Type","application/json");setRequestProperty("Authorization","Bearer $API_KEY");doOutput=true;connectTimeout=30000;readTimeout=30000}
            val json=JSONObject().apply{put("model","gpt-4o-mini");put("messages",JSONArray().put(JSONObject().apply{put("role","user");put("content",JSONArray().put(JSONObject().apply{put("type","text");put("text",prompt)}).put(JSONObject().apply{put("type","image_url");put("image_url",JSONObject().put("url","data:image/jpeg;base64,$b64"))}))}));put("max_tokens",500)}
            OutputStreamWriter(conn.outputStream).use{it.write(json.toString())}
            if(conn.responseCode==HttpURLConnection.HTTP_OK) JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            else "API Error ${conn.responseCode}"
        }catch(e:Exception){"Error: ${e.message}"}
    }

    private suspend fun callAIText(q:String):String=withContext(Dispatchers.IO){
        try{
            val lang=when(currentLanguage){"gu"->" Respond in Gujarati.";"hi"->" Respond in Hindi.";else->" Respond in English."}
            val conn=(URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply{requestMethod="POST";setRequestProperty("Content-Type","application/json");setRequestProperty("Authorization","Bearer $API_KEY");doOutput=true;connectTimeout=30000;readTimeout=30000}
            val json=JSONObject().apply{put("model","gpt-4o-mini");put("messages",JSONArray().put(JSONObject().apply{put("role","user");put("content",q+lang)}));put("max_tokens",200)}
            OutputStreamWriter(conn.outputStream).use{it.write(json.toString())}
            if(conn.responseCode==HttpURLConnection.HTTP_OK) JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            else "I could not process that"
        }catch(e:Exception){"Error: ${e.message}"}
    }

    private fun sendSMS(p:String,m:String){try{if(ActivityCompat.checkSelfPermission(this,Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED){val s=SmsManager.getDefault();s.sendMultipartTextMessage(p,null,s.divideMessage(m),null,null);runOnUiThread{toast("SMS sent to $p")}}else runOnUiThread{toast("SMS permission not granted")}}catch(e:Exception){runOnUiThread{toast("SMS failed: ${e.message}")}}
    private fun makeCall(p:String){try{if(ActivityCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED)startActivity(Intent(Intent.ACTION_CALL).apply{data=Uri.parse("tel:$p")})}catch(e:Exception){e.printStackTrace()}}
    private fun speak(t:String){textToSpeech?.speak(t,TextToSpeech.QUEUE_FLUSH,null,null)}
    private fun beep(t:Int,d:Int){toneGen?.startTone(t,d)}
    private fun toast(m:String){Toast.makeText(this,m,Toast.LENGTH_SHORT).show()}
    private fun setLanguage(c:String){currentLanguage=c;setupTTS(c);getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("language",c).apply()}
    private fun setVoiceGender(g:String){textToSpeech?.voice=textToSpeech?.voices?.find{it.name.contains(g,true)&&it.locale==textToSpeech?.language}?:textToSpeech?.defaultVoice}
    private fun saveVoiceGender(g:String){getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("voice_gender",g).apply()}
    private fun hasBTPerm()=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED else true
    private fun getPaired():List<BluetoothDevice>{if(!hasBTPerm())return emptyList();return try{bluetoothAdapter?.bondedDevices?.toList()?:emptyList()}catch(e:SecurityException){emptyList()}}
    private suspend fun connectDev(d:BluetoothDevice):Boolean=withContext(Dispatchers.IO){try{if(!hasBTPerm())return@withContext false;bluetoothSocket=d.createRfcommSocketToServiceRecord(UUID_SPP);bluetoothAdapter?.cancelDiscovery();bluetoothSocket?.connect();outputStream=bluetoothSocket?.outputStream;inputStream=bluetoothSocket?.inputStream;true}catch(e:Exception){e.printStackTrace();false}}
    private fun startListen(onCmd:(String)->Unit){Thread{val buf=ByteArray(1024);while(true){try{val n=inputStream?.read(buf)?:-1;if(n>0){val m=String(buf,0,n).trim();if(m.isNotEmpty())runOnUiThread{onCmd(m)}}}catch(e:IOException){break}}}.start()}
    private fun disconnect(){try{outputStream?.close();inputStream?.close();bluetoothSocket?.close()}catch(e:IOException){e.printStackTrace()}}
    private fun cancelAll(){currentJob?.cancel();scanJob?.cancel()}
    private fun saveCont(c1:String,c2:String,c3:String){getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().apply{putString(C1,c1);putString(C2,c2);putString(C3,c3);apply()}}
    override fun onDestroy(){super.onDestroy();cancelAll();disconnect();cameraExecutor.shutdown();textToSpeech?.shutdown();toneGen?.release();objectDetector?.close()}
}