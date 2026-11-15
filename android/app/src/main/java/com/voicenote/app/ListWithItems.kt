package com.voicenote.app

import androidx.room.Embedded
import androidx.room.Relation

data class ListWithItems(
    @Embedded val list: ListEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "listId",
    )
    val items: List<ListItemEntity>,
)
