// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.testing.InMemoryStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A damaged role file (read as "no role" after quarantine) never stops monitoring: the other copy answers. */
class RoleStoreTest {
    private val primary = InMemoryStateStore(RoleRecord())
    private val backup = InMemoryStateStore(RoleRecord())
    private val store = RoleStore(primary, backup)

    @Test fun writesBothCopies() =
        runTest {
            store.update { it.copy(role = Role.Wearer) }
            assertEquals(Role.Wearer, primary.data.first().role)
            assertEquals(Role.Wearer, backup.data.first().role)
        }

    @Test fun damagedPrimaryKeepsTheRoleAndHealsIt() =
        runTest {
            store.update { it.copy(role = Role.Wearer) }
            primary.update { RoleRecord() } // what TypedStore gives after quarantining a damaged role.json
            assertEquals(Role.Wearer, store.data.first().role)
            store.heal()
            assertEquals(Role.Wearer, primary.data.first().role)
        }

    @Test fun damagedBackupIsRewrittenFromThePrimary() =
        runTest {
            store.update { it.copy(role = Role.Follower) }
            backup.update { RoleRecord() }
            assertEquals(Role.Follower, store.data.first().role)
            store.heal()
            assertEquals(Role.Follower, backup.data.first().role)
        }

    @Test fun anInstallFromBeforeTheBackupGetsOne() =
        runTest {
            primary.update { RoleRecord(Role.Wearer) }
            store.heal()
            assertEquals(Role.Wearer, backup.data.first().role)
        }

    @Test fun aClearCutBetweenTheTwoWritesKeepsMonitoring() =
        runTest {
            store.update { it.copy(role = Role.Wearer) }
            primary.update { RoleRecord() } // the clear reached role.json, not the backup
            store.heal()
            assertEquals(Role.Wearer, store.data.first().role)
        }

    @Test fun clearingTheRoleClearsBothCopies() =
        runTest {
            store.update { it.copy(role = Role.Wearer) }
            store.update { it.copy(role = null) }
            store.heal()
            assertNull(store.data.first().role)
        }
}
