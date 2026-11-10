package dev.stance.deck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stance.deck.core.*
import kotlinx.coroutines.delay

@Composable
fun StanceTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun StanceApp(vm: DeckViewModel) {
    StanceTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val screen by vm.screen.collectAsStateWithLifecycle()
            when (val s = screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Pair -> PairScreen(vm)
                is Screen.Pc -> PcScreen(vm, s.pcPublic)
            }
        }
    }
}

// ---- home ---------------------------------------------------------------------

@Composable
fun HomeScreen(vm: DeckViewModel) {
    val pcs by vm.pcs.collectAsStateWithLifecycle()
    HomeContent(pcs, { vm.go(Screen.Pair) }, { vm.go(Screen.Pc(it)) }, { vm.forget(it) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(pcs: List<PcRecord>, onAdd: () -> Unit, onOpen: (String) -> Unit, onForget: (String) -> Unit) {
    var confirmForget by remember { mutableStateOf<PcRecord?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Stance Deck") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd() },
                icon = { Icon(Icons.Default.QrCodeScanner, null) },
                text = { Text("Add PC") },
                modifier = Modifier.testTag("add-pc"),
            )
        },
    ) { pad ->
        if (pcs.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Computer, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("No PC yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "On your PC run  stance deck pair  (or open the Phone card in the bar panel), then scan the code.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pcs, key = { it.pcPublic }) { pc ->
                    Card(onClick = { onOpen(pc.pcPublic) }, Modifier.fillMaxWidth().testTag("pc-card")) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, null, Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    fingerprint(pc.pcPublic) + "  ·  " + (pc.hosts.firstOrNull() ?: "?") + ":" + pc.port,
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                )
                            }
                            IconButton(onClick = { confirmForget = pc }) { Icon(Icons.Default.Delete, "Forget") }
                        }
                    }
                }
            }
        }
    }
    confirmForget?.let { pc ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget ${pc.name}?") },
            text = { Text("This phone stays listed on the PC until you run  stance deck unpair.") },
            confirmButton = { TextButton(onClick = { onForget(pc.pcPublic); confirmForget = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } },
        )
    }
}

// ---- pairing --------------------------------------------------------------------

@Composable
fun PairScreen(vm: DeckViewModel) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    // Pairing codes and fingerprints must not end up in screenshots or the recents view.
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val request by vm.pair.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    LaunchedEffect(scanError) { if (scanError != null) { delay(4000); vm.clearScanError() } }
    PairContent(
        granted = granted, request = request, scanError = scanError,
        onAllow = { launcher.launch(Manifest.permission.CAMERA) },
        onCode = { vm.codeFound(it) },
        onBack = { vm.go(Screen.Home) },
        onConfirm = { vm.confirmPair() },
        onCancel = { vm.cancelPair() },
        scanner = { m -> QrScanner(m) { vm.codeFound(it) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairContent(
    granted: Boolean, request: PairRequest?, scanError: String?,
    onAllow: () -> Unit, onCode: (String) -> Unit, onBack: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
    scanner: @Composable (Modifier) -> Unit,
) {
    var pasted by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Pair with a PC") },
            navigationIcon = { IconButton(onClick = { onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan the code from  stance deck pair  or the panel's Phone card. It is valid for two minutes.", style = MaterialTheme.typography.bodyMedium)
            if (granted) {
                scanner(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).testTag("scanner"))
            } else {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The camera is only used to read the pairing code.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onAllow() }) { Text("Allow camera") }
                    }
                }
            }
            OutlinedTextField(
                value = pasted, onValueChange = { pasted = it },
                label = { Text("Or paste the code") },
                placeholder = { Text("stance-deck:1?h=…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("paste"),
                trailingIcon = {
                    IconButton(onClick = { onCode(pasted) }, enabled = pasted.isNotBlank()) { Icon(Icons.Default.Check, "Use code") }
                },
            )
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    request?.let { r ->
        AlertDialog(
            onDismissRequest = { if (!r.working) onCancel() },
            title = { Text("Pair with ${r.qr.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Check that this fingerprint is the one shown on the PC:")
                    Text(
                        r.qr.pcFingerprint, style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("fingerprint"),
                    )
                    Text(r.qr.hosts.joinToString(", ") + ":" + r.qr.port, style = MaterialTheme.typography.bodySmall)
                    if (r.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(onClick = { onConfirm() }, enabled = !r.working, modifier = Modifier.testTag("pair-confirm")) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { onCancel() }, enabled = !r.working) { Text("Cancel") } },
        )
    }
}

// ---- deck -----------------------------------------------------------------------

private fun iconFor(name: String?): ImageVector = when (name) {
    "nightlight", "dark_mode", "bedtime" -> Icons.Default.Nightlight
    "light_mode", "wb_sunny", "brightness_high" -> Icons.Default.LightMode
    "brightness_medium", "brightness_6" -> Icons.Default.BrightnessMedium
    "volume_up", "speaker", "headphones", "headset" -> Icons.Default.VolumeUp
    "monitor", "desktop_windows", "display_settings" -> Icons.Default.DesktopWindows
    "check", "done" -> Icons.Default.Check
    "undo", "history" -> Icons.Default.Undo
    "work", "laptop" -> Icons.Default.Work
    "tv", "movie" -> Icons.Default.Tv
    "sports_esports", "videogame_asset" -> Icons.Default.SportsEsports
    "home" -> Icons.Default.Home
    else -> Icons.Default.TouchApp
}

@Composable
fun PcScreen(vm: DeckViewModel, pcPublic: String) {
    val pcs by vm.pcs.collectAsStateWithLifecycle()
    val pc = pcs.firstOrNull { it.pcPublic == pcPublic }
    val conn by vm.connection.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val c = conn
    LaunchedEffect(c) { c?.events?.collect { snack.showSnackbar(it, duration = SnackbarDuration.Short) } }
    val view = c?.view?.collectAsStateWithLifecycle()?.value
    PcContent(
        name = pc?.name ?: "PC", view = view, snack = snack,
        onBack = { vm.go(Screen.Home) },
        onRetry = { c?.retryNow() },
        onForget = { pc?.let { vm.forget(it.pcPublic) } },
        onPress = { c?.press(it) },
        onKeep = { c?.confirm() },
        onRevert = { c?.revert() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcContent(
    name: String, view: DeckView?, snack: SnackbarHostState,
    onBack: () -> Unit, onRetry: () -> Unit, onForget: () -> Unit,
    onPress: (String) -> Unit, onKeep: () -> Unit, onRevert: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (view == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            LinkBar(view.link, onRetry = onRetry, onForget = onForget)
            val pending = view.state?.pending
            if (pending != null && view.link is Link.Connected) {
                PendingBanner(pending, view.stateAtMs, onKeep = onKeep, onRevert = onRevert)
            }
            val buttons = view.hello?.buttons.orEmpty()
            if (buttons.isEmpty()) {
                if (view.link is Link.Connected) {
                    Text("No buttons configured on the PC (deck.toml).", Modifier.padding(24.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(buttons, key = { it.id }) { b ->
                        DeckButton(b, busy = b.id in view.busy, enabled = view.link is Link.Connected && b.id !in view.busy) { onPress(b.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckButton(b: Button, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val lit = b.on == true
    val colors = if (lit) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    Card(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.fillMaxWidth().height(112.dp).testTag("button-${b.id}")) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(20.dp), strokeWidth = 2.dp)
            else Icon(iconFor(b.icon), null, Modifier.align(Alignment.TopStart).size(28.dp))
            Text(
                b.label, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LinkBar(link: Link, onRetry: () -> Unit, onForget: () -> Unit) {
    val (text, color) = when (link) {
        Link.Idle -> "Idle" to MaterialTheme.colorScheme.surfaceVariant
        is Link.Connecting -> "Connecting…" to MaterialTheme.colorScheme.surfaceVariant
        Link.Connected -> return
        is Link.Waiting -> "${link.reason} · retrying in ${link.seconds} s" to MaterialTheme.colorScheme.errorContainer
        Link.Refused -> "This phone is no longer paired on the PC." to MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag("link-bar")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            when (link) {
                is Link.Waiting -> TextButton(onClick = onRetry) { Text("Retry") }
                Link.Refused -> TextButton(onClick = onForget) { Text("Remove") }
                else -> {}
            }
        }
    }
}

@Composable
private fun PendingBanner(p: Pending, atMs: Long, onKeep: () -> Unit, onRevert: () -> Unit) {
    var left by remember(p, atMs) { mutableLongStateOf(p.remainingMs) }
    LaunchedEffect(p, atMs) {
        while (true) {
            left = (p.remainingMs - (System.nanoTime() / 1_000_000 - atMs)).coerceAtLeast(0)
            if (left == 0L) break
            delay(200)
        }
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp).testTag("pending"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep this display setting?", style = MaterialTheme.typography.titleMedium)
            Text(p.label + "  ·  reverts in " + ((left + 999) / 1000) + " s", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (p.remainingMs <= 0) 0f else (left.toFloat() / p.remainingMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeep, modifier = Modifier.weight(1f).testTag("keep")) { Text("Keep") }
                OutlinedButton(onClick = onRevert, modifier = Modifier.weight(1f).testTag("revert")) { Text("Revert") }
            }
        }
    }
}
