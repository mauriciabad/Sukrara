// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.feature.sharing.RoleStore
import app.mauri.sukrara.feature.sharing.setup.AlertSettingsAccess
import app.mauri.sukrara.monitor.alerts.AlertStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Setup's [RoleStore] on the app's `role` store (the hub and the alert runtime read the same one). */
class AppRoles(private val store: StateStore<RoleRecord>) : RoleStore {
    override val role: Flow<Role?> = store.data.map { it.role }

    override suspend fun set(role: Role) = store.update { it.copy(role = role) }
}

/** Setup's alert step on the alert runtime's own settings store: confirmed values are what it checks. */
class AppAlertSettings(private val store: AlertStateStore) : AlertSettingsAccess {
    override val settings: Flow<AlertSettings> = store.settings

    override suspend fun save(settings: AlertSettings) = store.setSettings(settings)
}
