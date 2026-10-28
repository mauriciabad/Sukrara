// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.search

import androidx.annotation.StringRes
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsRoute

/** Settings pages that belong to other features: the overview opens them ([SettingsSearchBar]'s `onOpenOther`). */
enum class OtherPage { Sensor, Sharing, Apps }

/** Where a search result leads: a settings page, or another feature's page. */
sealed interface SearchTarget {
    data class Page(val route: SettingsRoute) : SearchTarget

    data class Other(val page: OtherPage) : SearchTarget
}

/**
 * One row the search finds: its [title] and [summary] as the page shows them (string resources, no format
 * arguments), the [page] it is on (the breadcrumb) and where tapping it leads. [pensOnly] rows exist only on a phone
 * that reads insulin pens.
 */
data class SearchEntry(
    @StringRes val title: Int,
    @StringRes val page: Int,
    val target: SearchTarget,
    @StringRes val summary: Int? = null,
    val pensOnly: Boolean = false,
)

/**
 * THE search index: one entry per row of every settings page, in page order. A row added to a page is added here
 * (the same string resources), so search and pages can't drift apart; [pageTitle] is exhaustive, so a new page
 * doesn't compile until it has a title here, and `SettingsIndexTest` checks every page has entries.
 */
object SettingsIndex {
    /** The breadcrumb of each page; null for the overview (its rows are the pages) and per-pen pages (no fixed rows). */
    @StringRes
    fun pageTitle(route: SettingsRoute): Int? =
        when (route) {
            SettingsRoute.Overview, is SettingsRoute.Pen, is SettingsRoute.PenDoses -> null
            SettingsRoute.Alerts -> R.string.st_alerts_title
            is SettingsRoute.AlertKindPage -> R.string.st_alerts_title
            SettingsRoute.Ranges -> R.string.st_ranges_title
            SettingsRoute.Graph -> R.string.st_graph_title
            SettingsRoute.GraphMarks -> R.string.st_marks_title
            SettingsRoute.LockScreen -> R.string.st_lock_title
            SettingsRoute.Pens -> R.string.st_pens_title
            SettingsRoute.Reliability -> R.string.st_reliability_title
            SettingsRoute.StatusBar -> R.string.st_statusbar_title
            SettingsRoute.SensorEnd -> R.string.st_sensorend_title
            SettingsRoute.About -> R.string.st_about_title
            SettingsRoute.ThresholdReview -> R.string.st_review_title
        }

    private fun page(
        route: SettingsRoute,
        vararg rows: Pair<Int, Int?>,
        pensOnly: Boolean = false,
    ): List<SearchEntry> {
        val title = checkNotNull(pageTitle(route)) { "$route has no page title" }
        val target = SearchTarget.Page(route)
        return rows.map { (t, s) -> SearchEntry(t, title, target, s, pensOnly) }
    }

    private fun overview(
        title: Int,
        summary: Int?,
        target: SearchTarget,
        pensOnly: Boolean = false,
    ) = SearchEntry(title, R.string.st_settings_title, target, summary, pensOnly)

    private val kindNames =
        mapOf(
            AlertKind.VERY_LOW to R.string.st_kind_very_low,
            AlertKind.LOW to R.string.st_kind_low,
            AlertKind.HIGH to R.string.st_kind_high,
            AlertKind.VERY_HIGH to R.string.st_kind_very_high,
            AlertKind.EXTREMELY_HIGH to R.string.st_kind_extremely_high,
            AlertKind.FALLING_FAST to R.string.st_kind_falling_fast,
            AlertKind.RISING_FAST to R.string.st_kind_rising_fast,
            AlertKind.NO_DATA to R.string.st_kind_no_data,
        )

    val entries: List<SearchEntry> =
        buildList {
            // the overview's rows: the pages themselves
            add(overview(R.string.st_alerts_title, R.string.st_overview_alerts_on, SearchTarget.Page(SettingsRoute.Alerts)))
            add(overview(R.string.st_reliability_title, R.string.st_reliability_ready, SearchTarget.Page(SettingsRoute.Reliability)))
            add(overview(R.string.st_ranges_title, R.string.st_overview_ranges, SearchTarget.Page(SettingsRoute.Ranges)))
            add(overview(R.string.st_graph_title, R.string.st_overview_graph, SearchTarget.Page(SettingsRoute.Graph)))
            add(overview(R.string.st_statusbar_title, R.string.st_overview_statusbar, SearchTarget.Page(SettingsRoute.StatusBar)))
            add(overview(R.string.st_lock_title, R.string.st_overview_lock, SearchTarget.Page(SettingsRoute.LockScreen)))
            add(overview(R.string.st_sensor, R.string.st_sensor_summary, SearchTarget.Other(OtherPage.Sensor)))
            add(overview(R.string.st_sensorend_title, R.string.st_overview_sensorend, SearchTarget.Page(SettingsRoute.SensorEnd)))
            add(overview(R.string.st_sharing, R.string.st_sharing_summary, SearchTarget.Other(OtherPage.Sharing)))
            add(overview(R.string.st_apps, R.string.st_apps_summary, SearchTarget.Other(OtherPage.Apps)))
            add(overview(R.string.st_pens_title, R.string.st_overview_pens, SearchTarget.Page(SettingsRoute.Pens), pensOnly = true))
            add(overview(R.string.st_about_title, null, SearchTarget.Page(SettingsRoute.About)))

            addAll(
                page(
                    SettingsRoute.Alerts,
                    R.string.st_alerts_main to null,
                    R.string.st_mute_all to null,
                    R.string.st_review_title to null,
                    R.string.st_quiet_insulin to null,
                    R.string.st_quiet_carbs to null,
                    R.string.st_quiet_falling to null,
                    R.string.st_action_mute to null,
                    R.string.st_action_relax to null,
                    R.string.st_action_back_mgdl to null,
                    R.string.st_action_back_min to null,
                    R.string.st_sound_pack to null,
                    R.string.st_shuffle to null,
                    R.string.st_notification_settings to R.string.st_notification_settings_summary,
                    // each alert's page has these rows; search leads to the list of alerts
                    R.string.st_threshold to null,
                    R.string.st_repeat to null,
                    R.string.st_alarm to null,
                    R.string.st_sound to null,
                    R.string.st_play_sound to null,
                    R.string.st_play_vibrate to null,
                    R.string.st_send_test to R.string.st_send_test_summary,
                    R.string.st_channel_settings to R.string.st_channel_settings_summary,
                ),
            )
            for ((kind, name) in kindNames) addAll(page(SettingsRoute.AlertKindPage(kind), name to null))
            addAll(
                page(
                    SettingsRoute.Ranges,
                    R.string.st_band_very_low to null,
                    R.string.st_band_low to null,
                    R.string.st_band_tight to null,
                    R.string.st_band_high to null,
                    R.string.st_band_very_high to null,
                    R.string.st_band_extremely_high to null,
                    R.string.st_ranges_reset to R.string.st_ranges_reset_summary,
                ),
            )
            addAll(
                page(
                    SettingsRoute.Graph,
                    R.string.st_graph_lines to null,
                    R.string.st_graph_line_add to null,
                    R.string.st_graph_grid to null,
                    R.string.st_graph_hours to null,
                    R.string.st_graph_rings to null,
                    R.string.st_last to null,
                    R.string.st_last_else to null,
                    R.string.st_ring_counts to null,
                    R.string.st_graph_marks to R.string.st_graph_marks_summary,
                    R.string.st_graph_side to R.string.st_graph_side_summary,
                    R.string.st_graph_crown to null,
                    R.string.st_marks_title to null,
                    R.string.st_graph_sensor to null,
                    R.string.st_graph_reset_all to null,
                ),
            )
            addAll(
                page(
                    SettingsRoute.GraphMarks,
                    R.string.st_marks_point to null,
                    R.string.st_marks_line to R.string.st_marks_line_summary,
                    R.string.st_marks_amount to null,
                    R.string.st_marks_time to null,
                    R.string.st_marks_reset to null,
                ),
            )
            addAll(page(SettingsRoute.LockScreen, R.string.st_lock_time to null, R.string.st_lock_treatment to null))
            addAll(
                page(
                    SettingsRoute.Pens,
                    R.string.st_pens_reading to null,
                    R.string.st_pens_notify to R.string.st_pens_notify_summary,
                    R.string.st_pens_yours to null,
                    R.string.st_pens_prime_below to null,
                    R.string.st_pens_prime_before to null,
                    R.string.st_pens_cluster to null,
                    R.string.st_pens_pending to null,
                    R.string.st_pens_offer to null,
                    R.string.st_pens_match to null,
                    R.string.st_pens_match_units to null,
                    R.string.st_pens_match_share to null,
                    R.string.st_pens_reset to R.string.st_pens_reset_summary,
                    pensOnly = true,
                ),
            )
            addAll(page(SettingsRoute.Reliability, R.string.st_reliability_title to R.string.st_reliability_ready))
            addAll(
                page(
                    SettingsRoute.StatusBar,
                    R.string.st_statusbar_chip to R.string.st_statusbar_chip_summary,
                    R.string.st_statusbar_number_icon to R.string.st_statusbar_number_icon_summary,
                    R.string.st_root_value to null,
                    R.string.st_root_colour to null,
                    R.string.st_root_placement to null,
                    R.string.st_root_hide_icon to R.string.st_root_hide_icon_summary,
                ),
            )
            addAll(
                page(
                    SettingsRoute.SensorEnd,
                    R.string.st_sensorend_current to null,
                    R.string.st_sensorend_reminders to null,
                    R.string.st_sensorend_add to null,
                    R.string.st_sensorend_unlock to R.string.st_sensorend_unlock_summary,
                    R.string.st_sensorend_evening to null,
                    R.string.st_sensorend_bedtime to null,
                    R.string.st_sensorend_wakeup to null,
                    R.string.st_sensorend_countdown to R.string.st_sensorend_countdown_summary,
                    R.string.st_sensorend_countdown_hours to null,
                    R.string.st_sensorend_warmup to R.string.st_sensorend_warmup_summary,
                ),
            )
            addAll(
                page(
                    SettingsRoute.About,
                    R.string.st_about_version to null,
                    R.string.st_update_check_again to null,
                    R.string.st_report_problem to R.string.st_report_problem_summary,
                    R.string.st_source_code to R.string.st_license,
                ),
            )
            addAll(page(SettingsRoute.ThresholdReview, R.string.st_review_confirm to null))
        }
}
