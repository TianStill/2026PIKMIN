package com.pikmin.fakegps.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pikmin.fakegps.utils.GeoUtils

@Composable
fun InputCoordinatesDialog(
    initialLat: Double,
    initialLng: Double,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var latText by remember { mutableStateOf(String.format("%.6f", initialLat)) }
    var lngText by remember { mutableStateOf(String.format("%.6f", initialLng)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("手動輸入座標")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 快捷貼上按鈕
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = clipboard.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString() ?: ""
                            val extracted = GeoUtils.extractCoordinatesFromText(text)
                            if (extracted != null) {
                                latText = extracted.latitude.toString()
                                lngText = extracted.longitude.toString()
                                errorMessage = null
                                val noteToast = if (extracted.note.isNotBlank()) "（備註：${extracted.note}）" else ""
                                Toast.makeText(context, "✅ 已成功貼上座標！$noteToast", Toast.LENGTH_SHORT).show()
                            } else {
                                errorMessage = "無法從文字中解析出有效座標"
                            }
                        } else {
                            Toast.makeText(context, "剪貼簿為空", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("從剪貼簿貼上文字/座標", style = MaterialTheme.typography.labelMedium)
                }

                // 緯度輸入框
                OutlinedTextField(
                    value = latText,
                    onValueChange = {
                        latText = it
                        errorMessage = null
                    },
                    label = { Text("緯度 Latitude (-90.0 ~ 90.0)") },
                    placeholder = { Text("例如：25.033964") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // 經度輸入框
                OutlinedTextField(
                    value = lngText,
                    onValueChange = {
                        lngText = it
                        errorMessage = null
                    },
                    label = { Text("經度 Longitude (-180.0 ~ 180.0)") },
                    placeholder = { Text("例如：121.564468") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latText.trim().toDoubleOrNull()
                    val lng = lngText.trim().toDoubleOrNull()

                    if (lat == null || lat !in -90.0..90.0) {
                        errorMessage = "請輸入有效的緯度 (-90.0 到 90.0)"
                        return@Button
                    }
                    if (lng == null || lng !in -180.0..180.0) {
                        errorMessage = "請輸入有效的經度 (-180.0 到 180.0)"
                        return@Button
                    }

                    onConfirm(lat, lng)
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("前往此座標")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
