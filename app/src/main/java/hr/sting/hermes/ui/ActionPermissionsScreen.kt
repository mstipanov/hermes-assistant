package hr.sting.hermes.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hr.sting.hermes.R
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.device.ActionPolicy
import hr.sting.hermes.device.DeviceActionCatalog
import hr.sting.hermes.device.HermesNotificationListener

/**
 * The Allow / Confirm / Deny matrix for every device action Hermes can request,
 * plus the Android access grants those actions need.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionPermissionsScreen(
    state: HermesUiState,
    onBack: () -> Unit,
    onPolicy: (String, String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val settings = state.settings
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermissions = remember { runtimePermissionList() }
    val missingRuntime = runtimePermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    val notificationsGranted = HermesNotificationListener.isEnabled(context)
    val dndGranted = context.getSystemService(android.app.NotificationManager::class.java)
        ?.isNotificationPolicyAccessGranted == true
    val writeSettingsGranted = Settings.System.canWrite(context)
    // refresh is read so the Compose snapshot re-reads the grants on resume.
    @Suppress("UNUSED_EXPRESSION")
    refresh

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            item { SectionText(stringResource(R.string.action_permissions_matrix_desc)) }

            item {
                Text(
                    stringResource(R.string.access_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            item {
                AccessRow(
                    title = stringResource(R.string.access_runtime),
                    granted = missingRuntime.isEmpty(),
                    onGrant = { permissionLauncher.launch(missingRuntime.toTypedArray()) },
                )
            }
            item {
                AccessRow(
                    title = stringResource(R.string.access_notifications),
                    granted = notificationsGranted,
                    onGrant = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
            item {
                AccessRow(
                    title = stringResource(R.string.access_dnd),
                    granted = dndGranted,
                    onGrant = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
            item {
                AccessRow(
                    title = stringResource(R.string.access_write_settings),
                    granted = writeSettingsGranted,
                    onGrant = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.action_policy_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            items(DeviceActionCatalog.all, key = { it.id }) { spec ->
                val policy = DeviceActionCatalog.policy(settings, spec.id)
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        stringResource(spec.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(spec.descriptionRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        PolicyChip(
                            label = stringResource(R.string.policy_allow),
                            selected = policy == ActionPolicy.ALLOW,
                            onClick = { onPolicy(spec.id, ActionPolicy.ALLOW.wire) },
                        )
                        PolicyChip(
                            label = stringResource(R.string.policy_confirm),
                            selected = policy == ActionPolicy.CONFIRM,
                            onClick = { onPolicy(spec.id, ActionPolicy.CONFIRM.wire) },
                        )
                        PolicyChip(
                            label = stringResource(R.string.policy_deny),
                            selected = policy == ActionPolicy.DENY,
                            onClick = { onPolicy(spec.id, ActionPolicy.DENY.wire) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }

            item {
                Text(
                    stringResource(R.string.action_permissions_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccessRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (granted) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.access_granted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        } else {
            Button(onClick = onGrant) { Text(stringResource(R.string.access_grant)) }
        }
    }
}

@Composable
private fun PolicyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

private fun runtimePermissionList(): List<String> = listOf(
    android.Manifest.permission.RECORD_AUDIO,
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.SEND_SMS,
    android.Manifest.permission.READ_SMS,
    android.Manifest.permission.READ_CALENDAR,
    android.Manifest.permission.WRITE_CALENDAR,
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.ACCESS_COARSE_LOCATION,
)
