package com.otodone.app.ui.hosts

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otodone.app.R
import com.otodone.app.data.RdpHost
import com.otodone.app.ui.components.ConfirmSheet
import com.otodone.app.ui.insets.rememberSystemBarInsets
import com.otodone.app.ui.insets.rememberThreeButtonNav


@Composable
fun HostListRoute(
    onConnect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HostListViewModel = viewModel(factory = HostListViewModel.Factory),
) {
    val hosts by viewModel.hosts.collectAsState()
    HostListScreen(
        hosts = hosts,
        onConnect = onConnect,
        onOpenSettings = onOpenSettings,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<RdpHost>,
    onConnect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSave: (RdpHost) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<RdpHost?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    // 待删除的主机：非空 = 底部确认弹窗打开。点「删除」只进入这里，不直接删数据。
    var pendingDelete by remember { mutableStateOf<RdpHost?>(null) }
    val context = LocalContext.current

    // 统一的系统栏/小窗安全高度：小窗里对固定配额取 max，不叠加
    val windowInsets = rememberSystemBarInsets()
    // 三键导航要把内容真正抬到导航栏之上；手势导航保持 edge-to-edge（见 rememberThreeButtonNav）。
    val threeButtonNav = rememberThreeButtonNav()

    // 弹层必须和页面在同一个 Box 里、排在 Scaffold 之后，才会盖在页面顶层（同一窗口）。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    windowInsets = windowInsets.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                    // 首页标题显示应用名（otoDone / 无触亦达），不再是"设备"
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        // 右上角齿轮：直接进入设置页（图标取自 wgpro 的 ic_settings_vector）
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.settings_title),
                                // 固定 onSurface：浅色下纯黑、深色下纯白，不跟莫奈主色
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    editing = null
                    editorVisible = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.host_add))
                }
            },
            // 统一的 SystemBars inset：内容/ FAB 都只补这一份，小窗取 max 而不是叠加
            contentWindowInsets = windowInsets,
            // 注意：这里【不要】引用任何 RdpNative/FreeRDP 的东西。
            // 触碰 RdpNative 会触发 System.loadLibrary，冷启动要动态链接 ~40MB 原生库，
            // 表现为启动后长时间灰屏。原生库只在真正进入会话时加载。
        ) { padding ->
            if (hosts.isEmpty()) {
                EmptyState(Modifier.fillMaxSize().padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // 三键导航：视口整体裁到导航栏之上，卡片不会画到三颗键背后。
                        // 手势导航：不裁，内容可以滚到小横条下面，靠 contentPadding 保证末项能滚出来。
                        .padding(
                            bottom = if (threeButtonNav) padding.calculateBottomPadding() else 0.dp,
                        ),
                    // contentPadding 负责首尾留白：背景仍铺到系统栏下方，只是内容不钻进去。
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = padding.calculateTopPadding() + 12.dp,
                        end = 16.dp,
                        bottom = if (threeButtonNav) 12.dp else padding.calculateBottomPadding() + 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostCard(
                            host = host,
                            onConnect = { onConnect(host.id) },
                            onEdit = {
                                editing = host
                                editorVisible = true
                            },
                            // 只记录待删除项；真正的删除在底部确认弹窗里执行。
                            onDelete = { pendingDelete = host },
                        )
                    }
                }
            }
        }

        if (editorVisible) {
            HostEditorSheet(
                initial = editing,
                onDismiss = { editorVisible = false },
                onSave = { host ->
                    onSave(host)
                    editorVisible = false
                },
            )
        }

        // 删除二次确认：与「添加电脑」「清除缓存」同一个窗口内自绘弹层组件（ConfirmSheet），
        // 标题带主机名，确认按钮用危险色；取消/点遮罩/返回键都只是关闭弹窗，不会删任何东西。
        pendingDelete?.let { host ->
            ConfirmSheet(
                title = stringResource(R.string.host_delete_title),
                message = stringResource(R.string.host_delete_message, host.displayName),
                confirmLabel = stringResource(R.string.action_delete),
                dismissLabel = stringResource(R.string.action_cancel),
                destructive = true,
                onConfirm = {
                    pendingDelete = null
                    onDelete(host.id)
                    Toast.makeText(
                        context,
                        context.getString(R.string.host_deleted, host.displayName),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Computer,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.hosts_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.hosts_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostCard(
    host: RdpHost,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Computer,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            ) {
                Text(
                    host.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val subtitle = buildString {
                    append(host.endpoint)
                    if (host.username.isNotBlank()) {
                        append(" · ")
                        if (host.domain.isNotBlank()) append(host.domain).append("\\")
                        append(host.username)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
