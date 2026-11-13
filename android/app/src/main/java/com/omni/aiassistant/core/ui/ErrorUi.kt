package com.omni.aiassistant.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omni.aiassistant.R
import com.omni.aiassistant.core.error.AppError

@Composable
fun AppErrorDialog(error: Throwable?, onDismiss: () -> Unit) {
    if (error == null) return
    var details by remember(error) { mutableStateOf(false) }
    val message = when (error) {
        is AppError.Authentication -> stringResource(R.string.error_auth)
        is AppError.RateLimit -> stringResource(R.string.error_rate_limit)
        is AppError.InsufficientCredit -> stringResource(R.string.error_credit)
        is AppError.ModelUnavailable -> stringResource(R.string.error_model)
        is AppError.ContextLength -> stringResource(R.string.error_context)
        is AppError.Network -> stringResource(R.string.error_network)
        is AppError.Tls -> stringResource(R.string.error_tls)
        is AppError.Endpoint -> stringResource(R.string.error_endpoint)
        is AppError.Timeout -> stringResource(R.string.error_timeout)
        is AppError.Backup -> stringResource(R.string.error_backup)
        is AppError.Encryption -> stringResource(R.string.error_encryption)
        is AppError.Validation -> stringResource(R.string.error_validation)
        is AppError.UnsupportedFeature -> stringResource(R.string.error_unsupported)
        is AppError.Permission -> stringResource(R.string.error_permission)
        is AppError.Provider -> stringResource(R.string.error_provider)
        is AppError.Database -> stringResource(R.string.error_database)
        else -> stringResource(R.string.error_generic)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.error_generic)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.details))
                }
                if (details) {
                    Text(
                        text = "${error::class.simpleName}: ${error.message.orEmpty().take(1000)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
