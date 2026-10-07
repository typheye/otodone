package com.otodone.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otodone.app.ui.insets.rememberSystemBarInsets

/** 单选抽屉的一个选项。 */
data class SingleChoiceItem<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

/**
 * 单选用的底部抽屉（与 [ConfirmSheet] 同一个 ModalBottomSheet 观感）。
 * 点击整行或右侧单选圆点都会选中，并立即回调 [onSelect]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SingleChoiceSheet(
    title: String,
    items: List<SingleChoiceItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowInsets = rememberSystemBarInsets()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // 小窗里系统导航栏上报不可用，统一按 SystemBars 的 max 口径取底部安全区
        contentWindowInsets = { windowInsets.only(WindowInsetsSides.Bottom) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            items.forEach { item ->
                val isSelected = item.value == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.value) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!item.description.isNullOrEmpty()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RadioButton(selected = isSelected, onClick = null)
                }
            }
        }
    }
}
