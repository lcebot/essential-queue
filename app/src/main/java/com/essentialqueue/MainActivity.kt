@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.essentialqueue

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    companion object {
        /** Semi-auto mode may only start the editor while this activity is in the foreground. */
        @Volatile var resumedInstance: MainActivity? = null
            private set
    }

    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppSettings.load(this)
        Queue.listener = { tick.intValue++ }
        setContent { AppTheme { App(tick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumedInstance = this
        tick.intValue++
        Queue.onAppResumed()
    }

    override fun onPause() {
        resumedInstance = null
        super.onPause()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
private fun App(tick: Int) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<EssentialScanner.Photo>>(emptyList()) }
    val selected = remember { mutableStateMapOf<Long, Boolean>() }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var lastScan by remember { mutableStateOf<EssentialScanner.Result?>(null) }
    var showProcessed by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }

    // DateRangePicker represents a day as UTC midnight in milliseconds.
    val todayUtc = remember { LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    var rangeStart by remember { mutableLongStateOf(todayUtc - 6 * DAY_MS) }
    var rangeEnd by remember { mutableLongStateOf(todayUtc) }
    var pickerOpen by remember { mutableStateOf(false) }

    var hasPhotoPerm by remember { mutableStateOf(hasPhotoPermission(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPhotoPerm = hasPhotoPermission(ctx)
    }
    // These can change outside the app, so re-read them whenever the activity resumes or the queue changes.
    val a11yOn = remember(tick) { isA11yEnabled(ctx) }
    val editorOk = remember(tick) { isEditorInstalled(ctx) }
    LaunchedEffect(tick) { hasPhotoPerm = hasPhotoPermission(ctx) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }

    val running = Queue.state in setOf(Queue.State.WAITING, Queue.State.OPENING, Queue.State.CLICKED)
    val visible = photos.filter { (showProcessed || !it.alreadyProcessed) && (showSent || !it.sentBefore) }
    val selectedCount = visible.count { selected[it.id] == true }

    fun doScan() {
        if (!hasPhotoPerm) { permLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES); return }
        scanning = true; scanProgress = 0f
        scope.launch {
            // Photos are taken in local time, so convert the picked days to local midnights.
            val from = utcDayToLocalStart(rangeStart)
            val to = utcDayToLocalStart(rangeEnd + DAY_MS)
            val r = withContext(Dispatchers.IO) {
                EssentialScanner.scan(ctx, from, to) { d, t -> scanProgress = if (t == 0) 1f else d.toFloat() / t }
            }
            val sent = History.load(ctx)
            r.candidates.forEach { it.sentBefore = it.historyKey in sent }
            photos = r.candidates
            selected.clear()
            r.candidates.forEach { selected[it.id] = !it.alreadyProcessed && !it.sentBefore }
            lastScan = r
            scanning = false
        }
    }

    fun startQueue() {
        if (Queue.state == Queue.State.PAUSED && Queue.current != null) { Queue.start(ctx); return }
        Queue.load(visible.filter { selected[it.id] == true })
        Queue.start(ctx)
    }

    // The queue runs without it, but the progress notification of the foreground service needs it.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startQueue() }
    fun requestStart() {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startQueue()
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(stringResource(R.string.subtitle)) },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            val canStart = !running && (selectedCount > 0 || Queue.state == Queue.State.PAUSED) &&
                editorOk && (Queue.mode == Queue.Mode.SEMI || a11yOn)
            AnimatedVisibility(visible = running || canStart) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (running) { haptic.tap(); Queue.pause(Queue.Msg(R.string.msg_paused_manually)) }
                        else { haptic.confirm(); requestStart() }
                    },
                    icon = { Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null) },
                    text = {
                        Text(when {
                            running -> stringResource(R.string.fab_pause)
                            Queue.state == Queue.State.PAUSED -> stringResource(R.string.fab_continue)
                            else -> pluralStringResource(R.plurals.fab_start, selectedCount, selectedCount)
                        })
                    },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    now, running,
                    onSkip = { haptic.tap(); Queue.skip() },
                    onMarkDone = { haptic.confirm(); Queue.markCurrentDone(ctx) },
                )
            }

            if (!hasPhotoPerm || !editorOk || (Queue.mode == Queue.Mode.AUTO && !a11yOn)) {
                item {
                    SetupCard(
                        hasPhotoPerm, editorOk, a11yOn, Queue.mode == Queue.Mode.AUTO,
                        onPhotoPerm = { haptic.tap(); permLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                        onA11y = { haptic.tap(); ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    )
                }
            }

            // Settings cannot change while a queue runs, so they step aside to keep the status in view.
            item {
                AnimatedVisibility(visible = !running) {
                DateRangeCard(
                    start = rangeStart, end = rangeEnd, today = todayUtc,
                    enabled = !running && !scanning,
                    onPick = { haptic.tap(); pickerOpen = true },
                    onQuick = { days -> haptic.toggle(true); rangeEnd = todayUtc; rangeStart = todayUtc - (days - 1) * DAY_MS },
                    onScan = { haptic.confirm(); doScan() },
                )
                }
            }

            item {
                AnimatedVisibility(visible = !running) {
                    SettingsCard(enabled = !running, tick = tick)
                }
            }

            item {
                Column(Modifier.fillMaxWidth().animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.section_photos), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        if (photos.isNotEmpty()) {
                            TextButton(onClick = {
                                haptic.tap()
                                val all = visible.all { selected[it.id] == true }
                                visible.forEach { selected[it.id] = !all }
                            }, enabled = !running) { Text(stringResource(if (visible.all { selected[it.id] == true }) R.string.select_none else R.string.select_all)) }
                        }
                    }
                    if (scanning) {
                        Spacer(Modifier.height(8.dp))
                        LinearWavyProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth())
                    }
                    lastScan?.let { r ->
                        // Formatted at render time so a language change applies immediately.
                        val sep = stringResource(R.string.list_separator)
                        val byMode = r.candidates.groupingBy { it.mode }.eachCount()
                        val summary = stringResource(R.string.summary_scanned, r.scanned, r.candidates.size) +
                            (if (byMode.isNotEmpty()) stringResource(R.string.summary_modes,
                                byMode.entries.joinToString(sep) { "${it.key} ${it.value}" }) else "") +
                            (if (r.broken > 0) stringResource(R.string.summary_broken, r.broken) else "")
                        Spacer(Modifier.height(4.dp))
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (photos.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = showProcessed, onClick = { showProcessed = !showProcessed; haptic.toggle(showProcessed) },
                                label = { Text(stringResource(R.string.filter_show_processed)) },
                                leadingIcon = if (showProcessed) {{ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }} else null)
                            FilterChip(selected = showSent, onClick = { showSent = !showSent; haptic.toggle(showSent) },
                                label = { Text(stringResource(R.string.filter_show_sent)) },
                                leadingIcon = if (showSent) {{ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }} else null)
                        }
                    }
                }
            }

            if (!scanning) {
                when {
                    lastScan == null -> item { EmptyHint() }
                    photos.isEmpty() -> item { ListNote(stringResource(R.string.empty_none)) }
                    visible.isEmpty() -> item { ListNote(stringResource(R.string.empty_filtered)) }
                }
            }

            itemsIndexed(visible, key = { _, p -> p.id }) { i, p ->
                PhotoRow(
                    p = p,
                    checked = selected[p.id] == true,
                    enabled = !running,
                    isCurrent = running && Queue.current?.id == p.id,
                    shape = groupedShape(i, visible.size),
                    onToggle = {
                        val on = !(selected[p.id] ?: false)
                        selected[p.id] = on
                        haptic.toggle(on)
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    // The queue can stop while the user is watching another screen, so the stop itself is felt too.
    // A pause the user asked for already had its own tap.
    // Only real transitions count, so reopening the app on a paused or finished queue stays silent.
    var lastState by remember { mutableStateOf(Queue.state) }
    LaunchedEffect(Queue.state) {
        val state = Queue.state
        if (state != lastState) {
            when (state) {
                Queue.State.PAUSED -> if (Queue.message?.id != R.string.msg_paused_manually) haptic.reject()
                Queue.State.DONE -> haptic.confirm()
                else -> Unit
            }
        }
        lastState = state
    }

    if (pickerOpen) {
        RangePickerDialog(
            start = rangeStart, end = rangeEnd, today = todayUtc,
            onDismiss = { pickerOpen = false },
            onConfirm = { a, b -> rangeStart = a; rangeEnd = b; pickerOpen = false },
        )
    }
}

/*
 * Haptic vocabulary, so every control speaks the same language:
 * tap for plain actions, confirm for actions that commit something, toggle for on/off state,
 * tick for stepping through values, and reject when the queue stops on its own.
 */
private fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.ContextClick)
private fun HapticFeedback.confirm() = performHapticFeedback(HapticFeedbackType.Confirm)
private fun HapticFeedback.reject() = performHapticFeedback(HapticFeedbackType.Reject)
private fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
private fun HapticFeedback.toggle(on: Boolean) =
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

private const val DAY_MS = 24L * 3600 * 1000
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun utcDayToLocalStart(utcMs: Long): Long =
    Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun fmtUtcDay(utcMs: Long): String =
    Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT)

@Composable
private fun DateRangeCard(
    start: Long, end: Long, today: Long, enabled: Boolean,
    onPick: () -> Unit, onQuick: (Int) -> Unit, onScan: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.date_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = onPick, enabled = enabled, shape = RoundedCornerShape(20.dp),
                color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DateRange, null)
                    Spacer(Modifier.width(12.dp))
                    // titleMedium keeps a full yyyy-MM-dd on one line on narrow phones, and maxLines guards
                    // against large font scales breaking a date in the middle.
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.date_from), style = MaterialTheme.typography.labelMedium)
                        Text(fmtUtcDay(start), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null,
                        Modifier.padding(horizontal = 8.dp).size(20.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.date_to), style = MaterialTheme.typography.labelMedium)
                        Text(fmtUtcDay(end), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to R.string.quick_today, 7 to R.string.quick_7_days, 30 to R.string.quick_30_days).forEach { (d, labelRes) ->
                    val sel = end == today && start == today - (d - 1) * DAY_MS
                    FilterChip(selected = sel, onClick = { onQuick(d) }, enabled = enabled, label = { Text(stringResource(labelRes)) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onScan, enabled = enabled, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_button), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun RangePickerDialog(
    start: Long, end: Long, today: Long,
    onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start,
        initialSelectedEndDateMillis = end,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
        },
    )
    val haptic = LocalHapticFeedback.current
    // Day cells live inside DateRangePicker, so watch the selection instead of the taps.
    LaunchedEffect(state) {
        snapshotFlow { state.selectedStartDateMillis to state.selectedEndDateMillis }
            .drop(1)
            .collect { haptic.tick() }
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val s = state.selectedStartDateMillis ?: return@TextButton
                    haptic.confirm()
                    onConfirm(s, state.selectedEndDateMillis ?: s)
                },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { haptic.tap(); onDismiss() }) { Text(stringResource(R.string.cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            title = { Text(stringResource(R.string.picker_title), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun Thumb(uri: Uri, mode: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, android.util.Size(192, 192), null).asImageBitmap() }
                .getOrNull()
        }
    }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Surface(shape = RoundedCornerShape(topEnd = 10.dp), color = cs.tertiary, contentColor = cs.onTertiary,
            modifier = Modifier.align(Alignment.BottomStart)) {
            Text(mode, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun StatusCard(now: Long, running: Boolean, onSkip: () -> Unit, onMarkDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val paused = Queue.state == Queue.State.PAUSED
    val container = when {
        paused -> cs.errorContainer
        running -> cs.primaryContainer
        Queue.state == Queue.State.DONE -> cs.tertiaryContainer
        else -> cs.surfaceContainerHigh
    }
    val onContainer = when {
        paused -> cs.onErrorContainer
        running -> cs.onPrimaryContainer
        Queue.state == Queue.State.DONE -> cs.onTertiaryContainer
        else -> cs.onSurface
    }
    Surface(color = container, contentColor = onContainer, shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(onContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    if (running) LoadingIndicator(Modifier.size(40.dp), color = onContainer)
                    else Icon(
                        when (Queue.state) {
                            Queue.State.PAUSED -> Icons.Rounded.PauseCircle
                            Queue.State.DONE -> Icons.Rounded.TaskAlt
                            else -> Icons.Rounded.CameraAlt
                        }, null, Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(when (Queue.state) {
                            Queue.State.IDLE -> R.string.state_idle
                            Queue.State.WAITING -> R.string.state_waiting
                            Queue.State.OPENING -> R.string.state_opening
                            Queue.State.CLICKED -> R.string.state_clicked
                            Queue.State.PAUSED -> R.string.state_paused
                            Queue.State.DONE -> R.string.state_done
                        }),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                    val total = Queue.items.size
                    if (total > 0) Text(stringResource(R.string.progress_submitted, minOf(Queue.index, total), total),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (Queue.state == Queue.State.WAITING && Queue.nextAllowedAt > now) {
                    val s = ((Queue.nextAllowedAt - now) / 1000.0).roundToInt()
                    Text(stringResource(R.string.countdown_seconds, s), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
            }
            val total = Queue.items.size
            if (total > 0) {
                Spacer(Modifier.height(16.dp))
                LinearWavyProgressIndicator(
                    progress = { Queue.index.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = onContainer,
                    trackColor = onContainer.copy(alpha = 0.2f),
                )
            }
            Queue.message?.let { m ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(m.id, *m.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
            }
            if (paused && Queue.current != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSkip, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.btn_skip)) }
                    OutlinedButton(onClick = onMarkDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.btn_mark_done)) }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    photoPerm: Boolean, editorOk: Boolean, a11yOn: Boolean, autoMode: Boolean,
    onPhotoPerm: () -> Unit, onA11y: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SetupRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.setup_photos_title),
                stringResource(R.string.setup_photos_desc), photoPerm, stringResource(R.string.setup_grant), onPhotoPerm)
            SetupRow(Icons.Rounded.Edit, stringResource(R.string.setup_editor_title),
                stringResource(R.string.setup_editor_desc), editorOk, null, {})
            if (autoMode) SetupRow(Icons.Rounded.TouchApp, stringResource(R.string.setup_a11y_title),
                stringResource(R.string.setup_a11y_desc, stringResource(R.string.a11y_label)) + "\n" +
                    stringResource(R.string.setup_a11y_keep), a11yOn,
                stringResource(R.string.setup_open), onA11y)
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, desc: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(icon, null) },
        supportingContent = { Text(desc) },
        trailingContent = {
            if (ok) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.cd_ready), tint = MaterialTheme.colorScheme.primary)
            else if (action != null) FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
            else Icon(Icons.Rounded.ErrorOutline, stringResource(R.string.cd_missing), tint = MaterialTheme.colorScheme.error)
        },
    ) {
        Text(title)
    }
}

@Composable
private fun SettingsCard(enabled: Boolean, tick: Int) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var interval by remember(tick) { mutableFloatStateOf((Queue.intervalMs / 1000).toFloat()) }
    var mode by remember(tick) { mutableStateOf(Queue.mode) }
    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.settings_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                ToggleButton(
                    checked = mode == Queue.Mode.AUTO,
                    onCheckedChange = {
                        if (mode != Queue.Mode.AUTO) haptic.toggle(true)
                        mode = Queue.Mode.AUTO; Queue.mode = mode; AppSettings.save(ctx)
                    },
                    enabled = enabled,
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.AutoMode, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mode_auto)) }
                ToggleButton(
                    checked = mode == Queue.Mode.SEMI,
                    onCheckedChange = {
                        if (mode != Queue.Mode.SEMI) haptic.toggle(true)
                        mode = Queue.Mode.SEMI; Queue.mode = mode; AppSettings.save(ctx)
                    },
                    enabled = enabled,
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.PanTool, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mode_semi)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (mode == Queue.Mode.AUTO) R.string.mode_auto_desc else R.string.mode_semi_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The service can be switched off by the system at any time, so the hint stays visible in Auto mode
            // and not only while the service is missing.
            if (mode == Queue.Mode.AUTO) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.setup_a11y_keep),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_interval), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                val secs = interval.roundToInt()
                Text(
                    if (secs == 0) stringResource(R.string.interval_none) else stringResource(R.string.interval_value, secs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Stateless Slider overloads are deprecated in the 1.5.0 alpha line, so drive it through SliderState.
            // The state keeps its default 0..1 range and is scaled to seconds here, because the range
            // parameter is not stable across the alpha releases.
            val maxSecs = (Queue.MAX_INTERVAL_MS / 1000).toFloat()
            val sliderState = remember(tick) {
                SliderState(
                    value = interval / maxSecs,
                    steps = 11,               // 5-second steps are fine enough without making the slider fiddly
                )
            }
            Slider(
                state = sliderState,
                onValueChange = {
                    sliderState.value = it
                    val snapped = (it * maxSecs / 5f).roundToInt() * 5f
                    // Tick once per step rather than per drag event, so the feedback matches the detents.
                    if (snapped != interval) {
                        val wasCautious = interval * 1000 >= Queue.CAUTIOUS_INTERVAL_MS
                        val isCautious = snapped * 1000 >= Queue.CAUTIOUS_INTERVAL_MS
                        // Crossing into the warning range gets a stronger cue than an ordinary step.
                        if (wasCautious && !isCautious) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        else haptic.tick()
                    }
                    interval = snapped
                },
                onValueChangeFinished = {
                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    Queue.intervalMs = interval.roundToInt() * 1000L; AppSettings.save(ctx)
                },
                enabled = enabled,
            )
            Text(stringResource(R.string.interval_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(visible = interval.roundToInt() * 1000L < Queue.CAUTIOUS_INTERVAL_MS) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.interval_warning),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(
    p: EssentialScanner.Photo, checked: Boolean, enabled: Boolean, isCurrent: Boolean,
    shape: RoundedCornerShape, onToggle: () -> Unit, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (isCurrent) cs.secondaryContainer else cs.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onToggle),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = { Thumb(p.uri, p.mode) },
            supportingContent = {
                val sizeText = stringResource(R.string.size_mb, p.size / 1_048_576.0)
                val processedText = stringResource(R.string.tag_processed)
                val sentText = stringResource(R.string.tag_sent)
                val infoSep = stringResource(R.string.info_separator)
                val tags = buildList {
                    add(p.name)
                    add(sizeText)
                    if (p.alreadyProcessed) add(processedText)
                    if (p.sentBefore) add(sentText)
                }
                Text(tags.joinToString(infoSep), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled) },
        ) {
            Text(Instant.ofEpochMilli(p.takenMs).atZone(ZoneId.systemDefault()).format(TIME_FMT),
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ListNote(text: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(MaterialShapes.Sunny.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.empty_desc), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Large outer corners and small inner ones make the rows read as one group, following M3 Expressive lists. */
private fun groupedShape(i: Int, n: Int): RoundedCornerShape {
    val big = 24.dp; val small = 6.dp
    return when {
        n == 1 -> RoundedCornerShape(big)
        i == 0 -> RoundedCornerShape(big, big, small, small)
        i == n - 1 -> RoundedCornerShape(small, small, big, big)
        else -> RoundedCornerShape(small)
    }
}

private fun hasPhotoPermission(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

private fun isEditorInstalled(ctx: Context) = try {
    ctx.packageManager.getPackageInfo(Queue.EDITOR_PKG, 0); true
} catch (_: PackageManager.NameNotFoundException) { false }

private fun isA11yEnabled(ctx: Context): Boolean {
    if (TapStartService.instance != null) return true
    val me = ComponentName(ctx, TapStartService::class.java).flattenToString()
    val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return list.split(':').any { it.equals(me, ignoreCase = true) }
}
