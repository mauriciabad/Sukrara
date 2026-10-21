// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.libreview.LibreViewUploader
import app.mauri.sukrara.tidepool.Tidepool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "share with your clinic" banner (LibreView or Tidepool): shown only while neither is set up and not dismissed. */
class UploadBannerTest {
    private val f = FeatureState()
    private val noLv = LibreViewUploader.Settings(email = "", hasPassword = false, glucose = true, treatments = true, takeover = false)
    private val lv = noLv.copy(email = "a@b.c", hasPassword = true)
    private val noTp = Tidepool.Settings()
    private val tp = Tidepool.Settings(on = true, email = "a@b.c", signedIn = true)

    @Test fun showsWhileNeitherIsSetUp() {
        assertTrue(UploadBanner.shows(f, noLv, noTp))
        // half set up counts as not set up
        assertTrue(UploadBanner.shows(f.copy(libreview = true), noLv, noTp))
        assertTrue(UploadBanner.shows(f, lv, noTp))   // account saved, upload switched off
        assertTrue(UploadBanner.shows(f, noLv, tp.copy(signedIn = false)))
        assertTrue(UploadBanner.shows(f, noLv, tp.copy(on = false)))
    }

    @Test fun hiddenOnceEitherIsSetUp() {
        assertFalse(UploadBanner.shows(f.copy(libreview = true), lv, noTp))
        assertFalse(UploadBanner.shows(f, noLv, tp))
        assertFalse(UploadBanner.shows(f.copy(libreview = true), lv, tp))
    }

    @Test fun hiddenOnceDismissed() {
        assertFalse(UploadBanner.shows(f.copy(uploadBannerDismissed = true), noLv, noTp))
    }
}
