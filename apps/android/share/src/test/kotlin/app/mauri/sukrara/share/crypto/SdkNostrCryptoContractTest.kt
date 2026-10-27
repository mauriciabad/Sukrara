// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.crypto

import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.testing.NostrCryptoContractTest

/** The app's crypto adapter against the OGS vectors (on the host through the JVM build of the same bindings). */
class SdkNostrCryptoContractTest : NostrCryptoContractTest() {
    override val crypto: NostrCrypto = SdkNostrCrypto()
}
