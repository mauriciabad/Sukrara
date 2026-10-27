// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Role
import kotlinx.serialization.Serializable

/** The typed state file `role.json`: the phone's role from first-run setup; null = setup not done yet. */
@Serializable
data class RoleRecord(
    val role: Role? = null,
)
