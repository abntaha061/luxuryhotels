package com.example.translation

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

/**
 * Interface allowing interchangeable translation backends (ML Kit on-device, Gemini API fallback).
 */
interface SubtitleTranslatorEngine {
    suspend fun translate(text: String): Result<String>
    fun close()
}

/**
 * Google ML Kit On-Device Translation Implementation (English -> Arabic).
 * Provides fast, offline, and zero-latency subtitle translations.
 */
class MlKitSubtitleTranslator : SubtitleTranslatorEngine {

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.ARABIC)
        .build()

    private val translator: Translator = Translation.getClient(options)
    private val modelManager = RemoteModelManager.getInstance()
    private val arabicModel = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()

    /**
     * Checks whether the Arabic offline translation model is already downloaded to the device.
     */
    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        try {
            val task = modelManager.isModelDownloaded(arabicModel)
            Tasks.await(task)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Downloads the Arabic language model.
     */
    suspend fun downloadModel(wifiOnly: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val conditionsBuilder = DownloadConditions.Builder()
            if (wifiOnly) {
                conditionsBuilder.requireWifi()
            }
            val task = translator.downloadModelIfNeeded(conditionsBuilder.build())
            Tasks.await(task)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes the offline model if the user wants to free up space.
     */
    suspend fun deleteModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val task = modelManager.deleteDownloadedModel(arabicModel)
            Tasks.await(task)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Translates the given English text to Arabic.
     */
    override suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success("")
        try {
            val task = translator.translate(text)
            val translated = Tasks.await(task)
            Result.success(translated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        translator.close()
    }
}
