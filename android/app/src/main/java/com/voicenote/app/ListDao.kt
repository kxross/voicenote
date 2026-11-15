package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ListDao {

    // ---- reads --------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM lists ORDER BY createdAt DESC")
    fun allListsWithItems(): LiveData<List<ListWithItems>>

    @Transaction
    @Query("SELECT * FROM lists WHERE id = :id")
    fun listWithItems(id: Long): LiveData<ListWithItems?>

    @Transaction
    @Query("SELECT * FROM lists ORDER BY createdAt DESC")
    suspend fun snapshot(): List<ListWithItems>

    @Query("SELECT id FROM lists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findListIdByName(name: String): Long?

    /** The list row itself — used to scope a conversation to a list. */
    @Query("SELECT * FROM lists WHERE id = :id LIMIT 1")
    suspend fun findListById(id: Long): ListEntity?

    @Query("SELECT id FROM list_items WHERE listId = :listId AND text = :text COLLATE NOCASE LIMIT 1")
    suspend fun findItemId(listId: Long, text: String): Long?

    // ---- writes -------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertList(list: ListEntity): Long

    @Insert
    suspend fun insertItem(item: ListItemEntity): Long

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun deleteListById(id: Long)

    @Query("DELETE FROM lists WHERE name = :name COLLATE NOCASE")
    suspend fun deleteListByName(name: String)

    @Query("UPDATE lists SET name = :newName WHERE name = :oldName COLLATE NOCASE")
    suspend fun renameList(oldName: String, newName: String)

    @Query("DELETE FROM list_items WHERE id = :id")
    suspend fun deleteItemById(id: Long)

    @Query("UPDATE list_items SET done = :done WHERE id = :id")
    suspend fun setItemDoneById(id: Long, done: Boolean)
}
