package com.ineedmypills.vibemis

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.profiles.SettingsProfile
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.components.rememberVibemisAdaptiveLayout
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import java.util.UUID

class ProfilesActivity : AppCompatActivity(), ProfilesManager.ProfileChangeListener {
    private val profilesState = mutableStateListOf<SettingsProfile>()
    private var activeProfileUuid by mutableStateOf<UUID?>(null)
    private var profilePendingDelete by mutableStateOf<SettingsProfile?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        ProfilesManager.getInstance().addListener(this)
        refreshProfiles()

        setContent {
            VibemisExpressiveTheme {
                ProfilesScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ProfilesManager.getInstance().removeListener(this)
    }

    override fun onProfilesChanged() {
        runOnUiThread { refreshProfiles() }
    }

    private fun refreshProfiles() {
        val manager = ProfilesManager.getInstance()
        profilesState.clear()
        profilesState.addAll(manager.profiles)
        activeProfileUuid = manager.active?.uuid
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun ProfilesScreen() {
        val activeName = profilesState.firstOrNull { it.uuid == activeProfileUuid }?.name
        val adaptive = rememberVibemisAdaptiveLayout()
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (adaptive.isCompactHeight) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.profiles_title),
                                    fontWeight = FontWeight.Bold
                                )
                                if (!activeName.isNullOrBlank()) {
                                    Text(
                                        text = "${stringResource(R.string.profiles_active_badge)}: $activeName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                            }
                        }
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.profiles_title),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        subtitle = {
                            if (!activeName.isNullOrBlank()) {
                                Text("${stringResource(R.string.profiles_active_badge)}: $activeName")
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        startActivity(Intent(this@ProfilesActivity, EditProfileActivity::class.java))
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.profile_manager_new_profile)) }
                )
            }
        ) { innerPadding ->
            if (profilesState.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VibemisShapeBadge(
                            polygon = MaterialShapes.Gem,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            size = 64.dp,
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.profile_manager_no_profiles_yet),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.profile_manager_tap_create_profile),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp),
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 92.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(profilesState, key = { it.uuid.toString() }) { profile ->
                            val isActive = activeProfileUuid == profile.uuid
                            val cardInteraction = remember { MutableInteractionSource() }
                            ElevatedCard(
                                onClick = {
                                    val intent = Intent(this@ProfilesActivity, EditProfileActivity::class.java)
                                    intent.putExtra("profileUuid", profile.uuid.toString())
                                    startActivity(intent)
                                },
                                interactionSource = cardInteraction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .expressiveFocusAndPress(
                                        interactionSource = cardInteraction,
                                        shape = MaterialTheme.shapes.large,
                                    ),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (isActive) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    VibemisShapeBadge(
                                        polygon = if (isActive) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
                                        containerColor = if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        contentColor = if (isActive) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        size = 42.dp,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    RadioButton(
                                        selected = isActive,
                                        onClick = {
                                            val manager = ProfilesManager.getInstance()
                                            if (isActive) {
                                                manager.setActive(null)
                                                VibemisLog.info(getString(R.string.profile_manager_deactivated_profile))
                                            } else {
                                                manager.setActive(profile.uuid)
                                                VibemisLog.info(getString(R.string.profile_manager_activated_profile, profile.name))
                                            }
                                            manager.save(this@ProfilesActivity)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = profile.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            AnimatedVisibility(visible = isActive) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.profiles_active_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = DateUtils.getRelativeTimeSpanString(
                                                profile.modifiedUtc,
                                                System.currentTimeMillis(),
                                                DateUtils.MINUTE_IN_MILLIS
                                            ).toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    IconButton(onClick = {
                                        val intent = Intent(this@ProfilesActivity, EditProfileActivity::class.java)
                                        intent.putExtra("profileUuid", profile.uuid.toString())
                                        startActivity(intent)
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_manager_edit_profile))
                                    }
                                    IconButton(onClick = { profilePendingDelete = profile }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.profile_manager_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        profilePendingDelete?.let { profile ->
            AlertDialog(
                onDismissRequest = { profilePendingDelete = null },
                title = { Text(stringResource(R.string.profile_manager_delete_profile)) },
                text = {
                    Text(stringResource(R.string.profile_manager_confirm_profile_deleteion, profile.name))
                },
                confirmButton = {
                    TextButton(onClick = {
                        val manager = ProfilesManager.getInstance()
                        manager.delete(profile.uuid)
                        manager.save(this@ProfilesActivity)
                        VibemisLog.info(getString(R.string.profile_manager_profile_deleted, profile.name))
                        profilePendingDelete = null
                    }) {
                        Text(stringResource(R.string.profile_manager_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { profilePendingDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}
