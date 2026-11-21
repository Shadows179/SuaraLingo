package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceLangCode: String,
    val targetLangCode: String,
    val sourceText: String,
    val translatedText: String,
    val isOfflineTranslation: Boolean,
    val isFavorite: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
