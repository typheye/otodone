package com.otodone.app.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.otodone.app.R
import com.otodone.app.data.RdpHost
import com.otodone.app.ui.components.InWindowBottomSheet

/**
 * 添加/编辑电脑的表单抽屉。
 *
 * 承载方式见 [InWindowBottomSheet]：窗口内自绘，小窗（freeform）下不会飘到桌面上；
 * 底部安全区与键盘由承载层统一处理（表单本身只负责滚动，避免嵌套滚动）。
 */
@Composable
fun HostEditorSheet(
    initial: RdpHost?,
    onDismiss: () -> Unit,
    onSave: (RdpHost) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: RdpHost.DEFAULT_PORT).toString()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var addressError by remember { mutableStateOf(false) }

    InWindowBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹出 / 小窗很矮时表单可滚动；外层承载层已把面板抬到 IME 之上。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (initial == null) R.string.host_add else R.string.host_edit),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.host_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    addressError = false
                },
                label = { Text(stringResource(R.string.host_address)) },
                isError = addressError,
                supportingText = if (addressError) {
                    { Text("请输入电脑 IP 或主机名") }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.host_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.host_domain)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.host_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.host_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = {
                    if (address.isBlank()) {
                        addressError = true
                    } else {
                        onSave(
                            (initial ?: RdpHost()).copy(
                                name = name.trim(),
                                address = address.trim(),
                                port = port.toIntOrNull()?.coerceIn(1, 65535)
                                    ?: RdpHost.DEFAULT_PORT,
                                username = username.trim(),
                                domain = domain.trim(),
                                password = password,
                            )
                        )
                    }
                }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
