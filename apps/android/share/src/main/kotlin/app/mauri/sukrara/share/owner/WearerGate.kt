// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Sharing runs only on the wearer's phone: started whenever the role becomes wearer (without an identity the engine
 * just shows *No identity* and runs nothing until one is created or restored), stopped for a follower or no role.
 */
suspend fun OwnerEngine.followRole(roles: Flow<Role?>, scope: CoroutineScope) {
    roles.distinctUntilChanged().collect { role -> if (role == Role.Wearer) start(scope) else stop() }
}
