package com.voicenote.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per gateway delivery this device has applied. The gateway's queue is
 * at-least-once (a delivery stays pending until acked, and an ack can be lost),
 * so the claim — inserted in the SAME transaction that applies the delivery —
 * is what makes applying it exactly-once, even with two workers draining at
 * the same moment.
 */
@Entity(tableName = "applied_deliveries")
data class AppliedDeliveryEntity(
    @PrimaryKey val id: Long,
    val appliedAt: Long = System.currentTimeMillis(),
)

@Dao
interface AppliedDeliveryDao {
    /** Returns the new rowid, or -1 when [row]'s id was already claimed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(row: AppliedDeliveryEntity): Long

    @Query("DELETE FROM applied_deliveries WHERE appliedAt < :before")
    suspend fun prune(before: Long): Int
}
