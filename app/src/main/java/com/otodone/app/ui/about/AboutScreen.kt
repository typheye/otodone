package com.otodone.app.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import com.otodone.app.ui.components.ConfirmSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.otodone.app.R
import com.otodone.app.ui.components.ActionPreferenceRow
import com.otodone.app.ui.components.PreferenceCard
import com.otodone.app.ui.components.PreferenceDivider
import com.otodone.app.ui.components.PreferenceRow
import com.otodone.app.ui.components.SectionLabel
import kotlinx.coroutines.launch

/**
 * 关于应用页：布局复刻 wgpro 的 fragment_about.xml。
 * 结构：应用图标/名称/版本 → 版本信息三联卡 → 开发与维护 → 法律信息 → 开源项目动态列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showContactDialog by remember { mutableStateOf(false) }

    val packageInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "—"
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    val targetSdk = context.applicationInfo.targetSdkVersion
    val channel = appLevel(versionName)
    val email = stringResource(R.string.about_contact_email)
    val copiedMessage = stringResource(R.string.about_contact_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { shareApp(context) }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.action_share),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 24.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item { AboutHeader(versionName = versionName) }
            item {
                Spacer(Modifier.height(24.dp))
                VersionInfoCard(versionCode = versionCode, targetSdk = targetSdk, channel = channel)
            }

            item { SectionLabel(stringResource(R.string.about_section_developer), topSpace = true) }
            item {
                PreferenceCard {
                    PreferenceRow(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.about_developer),
                        subtitle = stringResource(R.string.about_developer_name),
                        iconTint = MaterialTheme.colorScheme.primary,
                    )
                    PreferenceDivider()
                    ActionPreferenceRow(
                        icon = Icons.Filled.Email,
                        title = stringResource(R.string.about_feedback),
                        subtitle = stringResource(R.string.about_feedback_desc),
                        onClick = { showContactDialog = true },
                    )
                    PreferenceDivider()
                    ActionPreferenceRow(
                        icon = Icons.Filled.Code,
                        title = stringResource(R.string.about_homepage),
                        subtitle = stringResource(R.string.about_homepage_desc),
                        onClick = { openUrl(context, HOMEPAGE_URL) },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.about_section_legal), topSpace = true) }
            item {
                PreferenceCard {
                    ActionPreferenceRow(
                        icon = Icons.Filled.Policy,
                        title = stringResource(R.string.about_privacy),
                        subtitle = stringResource(R.string.about_privacy_desc),
                        onClick = { openUrl(context, POLICY_URL) },
                    )
                    PreferenceDivider()
                    ActionPreferenceRow(
                        icon = Icons.Filled.Description,
                        title = stringResource(R.string.about_license),
                        subtitle = stringResource(R.string.about_license_desc),
                        onClick = { openUrl(context, LICENSE_URL) },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.about_section_opensource), topSpace = true) }
            item {
                PreferenceCard {
                    OPEN_SOURCE.forEachIndexed { index, item ->
                        ActionPreferenceRow(
                            icon = Icons.Filled.Link,
                            title = item.name,
                            subtitle = stringResource(R.string.about_by, item.author),
                            onClick = { openUrl(context, item.url) },
                        )
                        if (index < OPEN_SOURCE.lastIndex) PreferenceDivider()
                    }
                }
            }
        }
    }

    if (showContactDialog) {
        ConfirmSheet(
            title = stringResource(R.string.about_feedback),
            message = stringResource(R.string.about_feedback_contact),
            confirmLabel = stringResource(R.string.action_copy),
            dismissLabel = stringResource(R.string.action_close),
            onConfirm = {
                copyToClipboard(context, email)
                showContactDialog = false
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            },
            onDismiss = { showContactDialog = false },
        )
    }
}

@Composable
private fun AboutHeader(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 版本信息三联卡：构建版本 / 目标 API / 应用级别。 */
@Composable
private fun VersionInfoCard(versionCode: Long, targetSdk: Int, channel: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VersionStat(stringResource(R.string.about_build_version), versionCode.toString(), Modifier.weight(1f))
            StatDivider()
            VersionStat(stringResource(R.string.about_target_api), targetSdk.toString(), Modifier.weight(1f))
            StatDivider()
            VersionStat(stringResource(R.string.about_app_level), channel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VersionStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(44.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 从版本名解析发布级别：Alpha / Beta / RC / Release（与 wgpro 相同的规则）。 */
private fun appLevel(versionName: String): String {
    val value = versionName.lowercase()
    return when {
        value.contains("alpha") -> "Alpha"
        value.contains("beta") -> "Beta"
        value.contains("rc") -> "RC"
        else -> "Release"
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareApp(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.about_share_text))
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.action_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("email", text))
}

private data class OpenSourceItem(val name: String, val author: String, val url: String)

private const val HOMEPAGE_URL = "https://github.com/typheye"
private const val POLICY_URL = "https://www.typheye.cn/policies/"
private const val LICENSE_URL = "https://github.com/FreeRDP/FreeRDP/blob/master/LICENSE"

/** 当前工程实际依赖的开源项目（与 wgpro 的 OPEN_SOURCE 列表同一形式）。 */
private val OPEN_SOURCE = listOf(
    OpenSourceItem("FreeRDP", "FreeRDP Project", "https://github.com/FreeRDP/FreeRDP"),
    OpenSourceItem("AndroidX（Jetpack）", "Google", "https://developer.android.com/jetpack/androidx"),
    OpenSourceItem("Jetpack Compose", "Google", "https://developer.android.com/jetpack/compose"),
    OpenSourceItem("Material Components", "Google", "https://github.com/material-components/material-components-android"),
    OpenSourceItem("Kotlin", "JetBrains", "https://kotlinlang.org"),
    OpenSourceItem("Kotlin Coroutines", "JetBrains", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceItem("DataStore", "AndroidX", "https://developer.android.com/topic/libraries/architecture/datastore"),
)
