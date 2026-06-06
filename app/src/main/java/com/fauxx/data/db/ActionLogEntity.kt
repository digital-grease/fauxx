package com.fauxx.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool

/**
 * Room entity representing a single logged action in the audit trail.
 * Written before execution (write-ahead logging) per safety requirements.
 *
 * @property id Auto-generated primary key.
 * @property timestamp Epoch millis when the action was dispatched.
 * @property actionType The type of synthetic action performed.
 * @property category The content category this action targeted.
 * @property detail Human-readable description (e.g., search query, URL visited).
 * @property metadata Optional structured extra detail as a JSON object of scalar facts
 *   (page title, cookie count, resolved-IP count, etc.) built via [LogMetadata]; null for
 *   entries that carry no richer detail. Added in DB v4 (issue #73).
 * @property success Whether the action completed successfully.
 */
@Entity(
    tableName = "action_log",
    indices = [Index(value = ["timestamp", "success"])]
)
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: ActionType,
    val category: CategoryPool,
    val detail: String,
    val success: Boolean = true,
    val metadata: String? = null
)
