package dev.lelonio.square.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.BatteryCharging
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Waveform
import dev.lelonio.square.R
import dev.lelonio.square.fusion.AppIcon
import dev.lelonio.square.fusion.Fusion
import dev.lelonio.square.fusion.display.RefreshRates
import dev.lelonio.square.fusion.spotify.SpotifyFreeAccount
import dev.lelonio.square.fusion.spotify.SpotifyFreeLoginScreen
import dev.lelonio.square.ui.TouchRefreshRate
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Square Fusion's settings, drawn with Square's own settings rows (Section,
 * ChoiceRow, ActionRow, InfoRow, DownloadSwitch) so they look like the rest
 * of the screen. Nothing here replaces a Square setting.
 */

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = InkDim,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
    )
}

// ── Account › Spotify ────────────────────────────────────────────────────

@Composable
internal fun SpotifyFreeSection(onSignIn: () -> Unit) {
    val connected by SpotifyFreeAccount.connected.collectAsStateWithLifecycle()
    Section("Spotify") {
        if (connected) {
            InfoRow("Spotify", stringResource(R.string.connected))
            RowDivider()
            ActionRow(stringResource(R.string.disconnect), destructive = true) { SpotifyFreeAccount.disconnect() }
        } else {
            ActionRow(stringResource(R.string.fusion_spotify_sign_in), destructive = false, onClick = onSignIn)
        }
    }
}

/**
 * The picture at the top of the home page and on the account card: one chosen
 * on the phone, or else the YouTube account's own; null for the generated
 * cover every missing picture gets.
 */
@Composable
internal fun rememberProfilePhoto(): String? {
    val context = LocalContext.current
    val account = remember(context) { (context.applicationContext as dev.lelonio.square.SquareApplication).youtubeAccount }
    val custom by dev.lelonio.square.fusion.ProfilePhoto.custom.collectAsStateWithLifecycle()
    val youtube by account.accountPhoto.collectAsStateWithLifecycle()
    return custom ?: youtube
}

/** Account › Profile picture: choose one on the phone, or go back to the account's own. */
@Composable
internal fun ProfilePhotoSection(name: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photo = rememberProfilePhoto()
    val custom by dev.lelonio.square.fusion.ProfilePhoto.custom.collectAsStateWithLifecycle()
    // The picture picked is framed first, in the circle it is shown in.
    var framing by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) framing = picked
    }
    framing?.let { picked ->
        dev.lelonio.square.ui.components.ImageCropDialog(
            source = picked,
            shape = dev.lelonio.square.ui.components.CropShape.CIRCLE,
            onCropped = { cropped ->
                framing = null
                scope.launch { dev.lelonio.square.fusion.ProfilePhoto.set(context, cropped) }
            },
            onDismiss = { framing = null },
        )
    }
    val choose = {
        pick.launch(
            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    Section(stringResource(R.string.fusion_profile_photo)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = choose)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.lelonio.square.ui.components.ProfilePicture(
                url = photo,
                name = name,
                size = 56.dp,
                modifier = Modifier.size(56.dp),
                decodeSize = 56.dp,
            )
            Text(
                stringResource(R.string.fusion_profile_photo_choose),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        if (custom != null) {
            RowDivider()
            ActionRow(stringResource(R.string.fusion_profile_photo_reset), destructive = false) {
                dev.lelonio.square.fusion.ProfilePhoto.clear()
            }
        }
    }
}

@Composable
internal fun SpotifyFreeLoginPage(header: @Composable () -> Unit, contentPadding: PaddingValues, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        header()
        SpotifyFreeLoginScreen(
            onConnected = { cookie ->
                SpotifyFreeAccount.connect(cookie)
                onDone()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// ── App › Icon ───────────────────────────────────────────────────────────

@Composable
internal fun AppIconSection() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(runCatching { AppIcon.current(context) }.getOrDefault(AppIcon.Square)) }
    Section(stringResource(R.string.fusion_app_icon)) {
        AppIcon.entries.forEach { icon ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching { AppIcon.apply(context, icon) }
                            .onSuccess { current = icon }
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val bitmap = remember(icon) {
                    runCatching {
                        context.getDrawable(icon.iconRes)?.toBitmap(96, 96)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                }
                Text(
                    icon.label + if (icon == AppIcon.Square) " · " + stringResource(R.string.fusion_default) else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current == icon) Ink else InkDim,
                    fontWeight = if (current == icon) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (current == icon) {
                    Icon(
                        PhosphorIcons.Regular.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Note(stringResource(R.string.fusion_app_icon_note))
    }
}

// ── App › Performance ────────────────────────────────────────────────────

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun PerformanceSection(backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop) {
    if (!Fusion.initialised) return
    val context = LocalContext.current
    val settings = Fusion.settings
    val choose by settings.chooseRefreshRate.collectAsStateWithLifecycle()
    val chosen by settings.refreshRateHz.collectAsStateWithLifecycle()
    @Suppress("DEPRECATION")
    val display = remember(context) {
        val activity = context.activity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity?.display else activity?.windowManager?.defaultDisplay
    }
    // Read back from the system after each change, never assumed.
    var applied by remember { mutableStateOf(RefreshRates.applied(display)) }
    LaunchedEffect(choose, chosen) {
        TouchRefreshRate.reapply()
        kotlinx.coroutines.delay(600)
        applied = RefreshRates.applied(display)
    }

    Section(stringResource(R.string.fusion_performance)) {
        DownloadSwitch(
            label = stringResource(R.string.fusion_choose_rate),
            checked = choose,
            backdrop = backdrop,
            icon = PhosphorIcons.Regular.BatteryCharging,
            onChange = settings::setChooseRefreshRate,
        )
        if (choose) {
            RefreshRates.OFFERED.forEach { hz ->
                val supported = RefreshRates.isSupported(display, hz)
                ChoiceRow(
                    label = "$hz Hz" + if (!supported) " — " + stringResource(R.string.fusion_rate_unsupported) else "",
                    selected = chosen == hz,
                ) { if (supported) settings.setRefreshRateHz(hz) }
            }
        }
        val supported = RefreshRates.supported(display)
        InfoRow(
            stringResource(R.string.fusion_rates_supported),
            supported.joinToString(" / ") { "$it Hz" }.ifBlank { stringResource(R.string.fusion_unknown) },
        )
        val confirmed = applied
        InfoRow(
            stringResource(R.string.fusion_rate_applied),
            confirmed?.let { "%.0f Hz".format(Locale.ROOT, it) } ?: stringResource(R.string.fusion_unknown),
        )
        if (choose && confirmed != null && kotlin.math.abs(confirmed - chosen) > 1f) {
            Note(stringResource(R.string.fusion_rate_not_confirmed, chosen))
        }
        Note(stringResource(R.string.fusion_performance_note))
    }
}
