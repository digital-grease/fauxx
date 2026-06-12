package com.fauxx.targeting.layer2

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a [ProfileSnapshot] came from. Only [IMPORT] is captured in M2; [PHANTOM]
 * (parsing the phantom context's own unauthenticated ad-preference page) is reserved
 * for an M3 follow-up. Authenticated scraping of the user's real platform account is
 * not possible from an embedded WebView (issues #51 / #52), so it is intentionally absent.
 */
enum class SnapshotSource { IMPORT, PHANTOM }

/**
 * Which experimental arm a [ProfileSnapshot] belongs to (E3 #172). [POISONED] is the user's
 * real, Fauxx-poisoned account — the default, and what every pre-#172 row is. [CONTROL] is a
 * separate clean account the user imports but never poisons, so the dashboard can show how far
 * the poisoned profile has diverged from an untouched baseline.
 *
 * The CONTROL series is measurement-only: a control import never updates [PlatformProfileCache]
 * and is filtered out of every Layer 2 / profile-drift consumer, so it can never influence
 * targeting — it exists purely to be compared against the poisoned series.
 */
enum class SnapshotSeries { POISONED, CONTROL }

/**
 * An append-only, point-in-time snapshot of the ad-interest categories attributed to the
 * user on a platform, captured so Layer 2 can compute drift over time (issue #170 E1) and
 * the dashboard can show profile-drift (issue #171 E2).
 *
 * Distinct from [PlatformProfileCache], which holds one mutable row per platform (the latest
 * state its weight calculation reads); this table is the history those two rows lose.
 */
@Entity(
    tableName = "profile_snapshot",
    indices = [Index(value = ["platformName", "capturedAt"])],
)
data class ProfileSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platformName: String,
    val source: SnapshotSource,
    val scrapedCategoriesJson: String,
    val capturedAt: Long,
    // E3 #172: experimental arm. defaultValue keeps Room's expected schema in sync with the
    // v6->v7 ALTER TABLE that backfills existing rows to POISONED.
    @ColumnInfo(defaultValue = "POISONED") val series: SnapshotSeries = SnapshotSeries.POISONED,
)
