package com.example.beaconquest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.pow

// ============================================================================
// 1. MODELE DANYCH I FABUŁA GRY
// ============================================================================

data class DetectedBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val distanceMeters: Double
)

data class StoryStage(
    val stepIndex: Int,
    val title: String,
    val targetMajor: Int,
    val targetMinor: Int,
    val hintToFind: String,
    val unlockedStoryText: String
)

enum class GameStatus {
    INTRO,
    SEARCHING,
    BEACON_FOUND_DIALOG,
    COMPLETED
}

// ============================================================================
// 2. NATYWNY PARSER IBEACON & SKANER BLUETOOTH LE
// ============================================================================

class IBeaconScanner(
    context: Context,
    private val onBeaconDetected: (DetectedBeacon) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val appleData = scanRecord.getManufacturerSpecificData(0x004C) ?: return

            if (appleData.size >= 23 && appleData[0] == 0x02.toByte() && appleData[1] == 0x15.toByte()) {
                val buffer = ByteBuffer.wrap(appleData, 2, 16)
                val mostSigBits = buffer.long
                val leastSigBits = buffer.long
                val uuid = UUID(mostSigBits, leastSigBits).toString().uppercase()

                val major = ((appleData[18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
                val minor = ((appleData[20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
                val txPower = appleData[22].toInt()

                val distance = estimateDistance(result.rssi, txPower)
                onBeaconDetected(DetectedBeacon(uuid, major, minor, result.rssi, distance))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning || bluetoothAdapter?.isEnabled != true) return
        scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return -1.0
        val calibratedTx = if (txPower == 0) -59 else txPower
        return 10.0.pow((calibratedTx - rssi) / (10.0 * 2.0))
    }
}

// ============================================================================
// 3. VIEWMODEL ZARZĄDZAJĄCY STANEM GRY
// ============================================================================

class QuestViewModel : ViewModel() {

    private val requiredUuidFilter: String? = null
    private val unlockDistanceThreshold = 2.0

    val stages = listOf(
        StoryStage(
            stepIndex = 0,
            title = "Etap 1: Strażnik Czasu",
            targetMajor = 1,
            targetMinor = 1,
            hintToFind = "Wskazówka: Szukaj tam, gdzie wskazówki płyną nieustannie, a stary mechanizm odmierza kolejne godziny w głównym holu.",
            unlockedStoryText = "Za tarczą starego zegara znajdujesz zakurzoną kopertę! Napis głosi:\n\n\"Dobrze zacząłeś, wędrowcze. Moja receptura na Eliksir Światła została podzielona na trzy części. Pierwszą już masz: to Kropla Rosy. Aby poznać drugą, udaj się tam, gdzie spoczywa wiedza...\""
        ),
        StoryStage(
            stepIndex = 1,
            title = "Etap 2: Szept Pergaminu",
            targetMajor = 1,
            targetMinor = 2,
            hintToFind = "Wskazówka: Udaj się do miejsca pełnego książek. Szukaj na najniższej półce w pobliżu zielonego fotela.",
            unlockedStoryText = "Pomiędzy stronami starego zielnika wypada mosiężny kluczyk i kolejna notatka:\n\n\"Drugi składnik to Liść Srebrzystej Paproci. Został Ci już tylko ostatni krok. Ukryłem sam eliksir w miejscu, gdzie ogień niegdyś dawał ciepło domownikom.\""
        ),
        StoryStage(
            stepIndex = 2,
            title = "Etap 3: Serce Płomienia",
            targetMajor = 1,
            targetMinor = 3,
            hintToFind = "Wskazówka: Zbadaj stary kominek lub miejsce przy głównym piecu. Zwróć uwagę na lewą wnękę.",
            unlockedStoryText = "Mosiężny kluczyk idealnie pasuje do ukrytej skrytki w cegle! Wyciągasz z niej fiolkę, która zaczyna delikatnie lśnić w obiektywie Twojej kamery. Tajemnica Alchemika została rozwiązana!"
        )
    )

    private val _gameStatus = MutableStateFlow(GameStatus.INTRO)
    val gameStatus: StateFlow<GameStatus> = _gameStatus.asStateFlow()

    private val _currentStageIndex = MutableStateFlow(0)
    val currentStageIndex: StateFlow<Int> = _currentStageIndex.asStateFlow()

    private val _targetBeaconDistance = MutableStateFlow<Double?>(null)
    val targetBeaconDistance: StateFlow<Double?> = _targetBeaconDistance.asStateFlow()

    private val _closestOtherBeacon = MutableStateFlow<DetectedBeacon?>(null)
    val closestOtherBeacon: StateFlow<DetectedBeacon?> = _closestOtherBeacon.asStateFlow()

    fun startGame() {
        _currentStageIndex.value = 0
        _targetBeaconDistance.value = null
        _gameStatus.value = GameStatus.SEARCHING
    }

    fun onBeaconReceived(beacon: DetectedBeacon) {
        if (_gameStatus.value != GameStatus.SEARCHING) return

        if (requiredUuidFilter != null && !beacon.uuid.equals(requiredUuidFilter, ignoreCase = true)) {
            return
        }

        val currentStage = stages[_currentStageIndex.value]

        if (beacon.major == currentStage.targetMajor && beacon.minor == currentStage.targetMinor) {
            _targetBeaconDistance.value = beacon.distanceMeters

            if (beacon.distanceMeters in 0.0..unlockDistanceThreshold) {
                _gameStatus.value = GameStatus.BEACON_FOUND_DIALOG
            }
        } else {
            _closestOtherBeacon.value = beacon
        }
    }

    fun proceedToNextStage() {
        val nextIndex = _currentStageIndex.value + 1
        _targetBeaconDistance.value = null
        _closestOtherBeacon.value = null

        if (nextIndex < stages.size) {
            _currentStageIndex.value = nextIndex
            _gameStatus.value = GameStatus.SEARCHING
        } else {
            _gameStatus.value = GameStatus.COMPLETED
        }
    }
}

// ============================================================================
// 4. GŁÓWNA AKTYWNOŚĆ I INTERFEJS JETPACK COMPOSE
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFFFFD54F),
                    surface = Color(0xCC121212)
                )
            ) {
                BeaconQuestScreen()
            }
        }
    }
}

@Composable
fun BeaconQuestScreen(viewModel: QuestViewModel = viewModel()) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permsMap ->
        hasPermissions = permsMap.values.all { it }
    }

    if (!hasPermissions) {
        PermissionRequestView(onGrantClick = { permissionLauncher.launch(requiredPermissions) })
    } else {
        ActiveGameContent(viewModel = viewModel)
    }
}

@Composable
fun PermissionRequestView(onGrantClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E262E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Wymagane Uprawnienia",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Aby gra terenowa działała poprawnie, potrzebujemy dostępu do:\n\n" +
                            "• Kamery (podgląd świata gry na żywo)\n" +
                            "• Bluetooth oraz Lokalizacji (wykrywanie nadajników iBeacon w terenie)",
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onGrantClick) {
                    Text("Nadaj uprawnienia i graj", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ActiveGameContent(viewModel: QuestViewModel) {
    val context = LocalContext.current
    val gameStatus by viewModel.gameStatus.collectAsState()
    val stageIndex by viewModel.currentStageIndex.collectAsState()
    val distance by viewModel.targetBeaconDistance.collectAsState()
    val otherBeacon by viewModel.closestOtherBeacon.collectAsState()

    val currentStage = viewModel.stages[stageIndex]

    val scanner = remember {
        IBeaconScanner(context) { beacon ->
            viewModel.onBeaconReceived(beacon)
        }
    }

    DisposableEffect(Unit) {
        scanner.startScanning()
        onDispose { scanner.stopScanning() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewBackground(modifier = Modifier.fillMaxSize())

        if (gameStatus == GameStatus.SEARCHING) {
            ScannerReticleOverlay(
                distance = distance,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (gameStatus == GameStatus.SEARCHING || gameStatus == GameStatus.BEACON_FOUND_DIALOG) {
            TopHudOverlay(
                stage = currentStage,
                totalStages = viewModel.stages.size,
                distance = distance,
                otherBeacon = otherBeacon,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }

        AnimatedVisibility(
            visible = gameStatus != GameStatus.SEARCHING,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            when (gameStatus) {
                GameStatus.INTRO -> IntroModal(onStart = { viewModel.startGame() })
                GameStatus.BEACON_FOUND_DIALOG -> StoryUnlockedModal(
                    stage = currentStage,
                    isLastStage = stageIndex == viewModel.stages.lastIndex,
                    onContinue = { viewModel.proceedToNextStage() }
                )
                GameStatus.COMPLETED -> VictoryModal(onRestart = { viewModel.startGame() })
                else -> {}
            }
        }
    }
}

@Composable
fun CameraPreviewBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
fun TopHudOverlay(
    stage: StoryStage,
    totalStages: Int,
    distance: Double?,
    otherBeacon: DetectedBeacon?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC101418), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stage.title,
                color = Color(0xFF00E676),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "Postęp: ${stage.stepIndex + 1} / $totalStages",
                color = Color.LightGray,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stage.hintToFind,
            color = Color.White,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(8.dp))

        val (statusText, statusColor) = when {
            distance == null -> "Skanowanie eteru... (Szukam Major: ${stage.targetMajor}, Minor: ${stage.targetMinor})" to Color.Gray
            distance < 2.0 -> "GORĄCO! Sygnał tuż obok (${String.format("%.1f", distance)} m)" to Color(0xFF00E676)
            distance < 5.0 -> "CIEPŁO! Jesteś blisko (${String.format("%.1f", distance)} m)" to Color(0xFFFFD54F)
            else -> "ZIMNO. Wykryto słaby sygnał (${String.format("%.1f", distance)} m)" to Color(0xFF64B5F6)
        }

        Text(
            text = statusText,
            color = statusColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )

        if (distance == null && otherBeacon != null) {
            Text(
                text = "W pobliżu inny iBeacon (Major: ${otherBeacon.major}, Minor: ${otherBeacon.minor})",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun ScannerReticleOverlay(distance: Double?, modifier: Modifier = Modifier) {
    val ringColor = when {
        distance == null -> Color.White.copy(alpha = 0.35f)
        distance < 2.0 -> Color(0xFF00E676)
        distance < 5.0 -> Color(0xFFFFD54F)
        else -> Color(0xFF64B5F6)
    }

    Box(
        modifier = modifier
            .size(220.dp)
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(ringColor, CircleShape)
        )
    }
}

@Composable
fun IntroModal(onStart: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE161B22)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Tajemnica Alchemika",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF00E676)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Witaj w terenowej grze detektywistycznej!\n\nTwoim zadaniem jest odnalezienie ukrytych w okolicy artefaktów (nadajników iBeacon). Kieruj się wskazówkami na ekranie i obserwuj radar sygnału na tle obrazu z kamery.\n\nGdy podejdziesz do właściwego miejsca na mniej niż 2 metry, odblokujesz kolejny fragment historii!",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Rozpocznij Poszukiwania", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StoryUnlockedModal(
    stage: StoryStage,
    isLastStage: Boolean,
    onContinue: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF51B232A)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .border(2.dp, Color(0xFF00E676), RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ZNALEZIONO ARTEFAKT!",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stage.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stage.unlockedStoryText,
                color = Color(0xFFE0E0E0),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isLastStage) "Odbierz Nagrodę Finałową" else "Szukaj Kolejnej Wskazówki",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VictoryModal(onRestart: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5121E18)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "GRATULACJE!",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFD54F)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ukończyłeś grę terenową pomyślnie!\n\nWszystkie trzy części receptury zostały zebrane, a Eliksir Światła znów lśni pełnym blaskiem. Jesteś prawdziwym mistrzem zmysłów i technologii.",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Zagraj Ponownie", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}