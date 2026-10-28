// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.json

import kotlinx.serialization.json.Json

/** The app's one JSON configuration: newer files and servers may add fields; absent optionals stay absent. */
val SukraraJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
