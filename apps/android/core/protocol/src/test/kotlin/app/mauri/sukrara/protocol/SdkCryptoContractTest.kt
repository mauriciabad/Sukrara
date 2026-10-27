// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.testing.NostrCryptoContractTest

class SdkCryptoContractTest : NostrCryptoContractTest() {
    override val crypto: NostrCrypto = SdkTestCrypto()
}
