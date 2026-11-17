package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.SavedScript
import com.example.data.model.TerminalProfile
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ProfileRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProfiles(userId: String): Flow<List<TerminalProfile>> =
        db.collection("users")
            .document(userId)
            .collection("profiles")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    TerminalProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedWith(
                    compareByDescending<TerminalProfile> { it.activeProfile }
                        .thenBy { it.name.lowercase() }
                )
            }

    fun observeScripts(userId: String): Flow<List<SavedScript>> =
        db.collection("users")
            .document(userId)
            .collection("scripts")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    SavedScript::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
            }

    suspend fun ensureDefaultProfile(userId: String): Result<Unit> {
        val colPath = "users/$userId/profiles"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val existing = db.collection("users").document(userId).collection("profiles")
                .limit(1)
                .get()
                .await()
            if (existing.isEmpty) {
                val docRef = db.collection("users").document(userId)
                    .collection("profiles").document("prof_alpaca_core")
                val payload = mapOf(
                    "userId" to uid,
                    "name" to "Alpaca Core",
                    "preferredShell" to "/bin/ash",
                    "promptSymbol" to "alpaca:~$",
                    "aiModelName" to "gemini-3.8-flash",
                    "aiTemperature" to 0.4,
                    "proactiveMonitoring" to true,
                    "autoSuggestEnabled" to true,
                    "cpuAlertThreshold" to 75L,
                    "memAlertThreshold" to 80L,
                    "activeProfile" to true,
                    "commandHistory" to listOf("cat /etc/os-release", "apk info", "free -m"),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, colPath)
            Result.failure(e)
        }
    }

    suspend fun createProfile(
        userId: String,
        name: String,
        preferredShell: String,
        promptSymbol: String,
        aiModelName: String,
        aiTemperature: Double,
        proactiveMonitoring: Boolean,
        autoSuggestEnabled: Boolean,
        cpuAlertThreshold: Long,
        memAlertThreshold: Long,
        activateImmediately: Boolean,
        existingProfileIds: List<String>,
    ): Result<String> {
        val colPath = "users/$userId/profiles"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }

            val cleanName = name.trim().take(40)
            require(cleanName.isNotEmpty()) { "Profile name cannot be empty." }
            val cleanPrompt = promptSymbol.trim().take(24).ifEmpty { "alpaca:~$" }
            val validShell = if (preferredShell in ALLOWED_SHELLS) preferredShell else "/bin/ash"
            val validModel = if (aiModelName in ALLOWED_MODELS) aiModelName else "gemini-3.8-flash"
            val clampedTemp = aiTemperature.coerceIn(0.0, 1.0)
            val clampedCpu = cpuAlertThreshold.coerceIn(10L, 99L)
            val clampedMem = memAlertThreshold.coerceIn(10L, 99L)

            val docRef = db.collection("users").document(userId).collection("profiles").document()
            val batch = db.batch()
            if (activateImmediately) {
                for (otherId in existingProfileIds) {
                    if (otherId.isNotBlank() && otherId != docRef.id) {
                        val otherRef = db.collection("users").document(userId)
                            .collection("profiles").document(otherId)
                        batch.update(
                            otherRef,
                            mapOf(
                                "activeProfile" to false,
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                        )
                    }
                }
            }
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "preferredShell" to validShell,
                "promptSymbol" to cleanPrompt,
                "aiModelName" to validModel,
                "aiTemperature" to clampedTemp,
                "proactiveMonitoring" to proactiveMonitoring,
                "autoSuggestEnabled" to autoSuggestEnabled,
                "cpuAlertThreshold" to clampedCpu,
                "memAlertThreshold" to clampedMem,
                "activeProfile" to activateImmediately,
                "commandHistory" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            batch.set(docRef, payload)
            batch.commit().await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, colPath)
            Result.failure(e)
        }
    }

    suspend fun updateProfileSettings(
        userId: String,
        profileId: String,
        name: String,
        preferredShell: String,
        promptSymbol: String,
        aiModelName: String,
        aiTemperature: Double,
        proactiveMonitoring: Boolean,
        autoSuggestEnabled: Boolean,
        cpuAlertThreshold: Long,
        memAlertThreshold: Long,
        activeProfile: Boolean,
    ): Result<Unit> {
        val docPath = "users/$userId/profiles/$profileId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(profileId.isNotBlank()) { "Profile ID cannot be blank." }

            val cleanName = name.trim().take(40)
            require(cleanName.isNotEmpty()) { "Profile name cannot be empty." }
            val cleanPrompt = promptSymbol.trim().take(24).ifEmpty { "alpaca:~$" }
            val validShell = if (preferredShell in ALLOWED_SHELLS) preferredShell else "/bin/ash"
            val validModel = if (aiModelName in ALLOWED_MODELS) aiModelName else "gemini-3.8-flash"

            db.document(docPath).update(
                mapOf(
                    "name" to cleanName,
                    "preferredShell" to validShell,
                    "promptSymbol" to cleanPrompt,
                    "aiModelName" to validModel,
                    "aiTemperature" to aiTemperature.coerceIn(0.0, 1.0),
                    "proactiveMonitoring" to proactiveMonitoring,
                    "autoSuggestEnabled" to autoSuggestEnabled,
                    "cpuAlertThreshold" to cpuAlertThreshold.coerceIn(10L, 99L),
                    "memAlertThreshold" to memAlertThreshold.coerceIn(10L, 99L),
                    "activeProfile" to activeProfile,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun activateProfile(
        userId: String,
        targetProfileId: String,
        allProfileIds: List<String>,
    ): Result<Unit> {
        val docPath = "users/$userId/profiles/$targetProfileId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(targetProfileId.isNotBlank()) { "Profile ID cannot be blank." }

            val batch = db.batch()
            for (id in allProfileIds) {
                if (id.isNotBlank()) {
                    val ref = db.collection("users").document(userId).collection("profiles").document(id)
                    batch.update(
                        ref,
                        mapOf(
                            "activeProfile" to (id == targetProfileId),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateCommandHistory(
        userId: String,
        profileId: String,
        updatedHistory: List<String>,
    ): Result<Unit> {
        val docPath = "users/$userId/profiles/$profileId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(profileId.isNotBlank()) { "Profile ID cannot be blank." }

            val bounded = updatedHistory
                .map { it.trim().take(500) }
                .filter { it.isNotEmpty() }
                .takeLast(50)

            db.document(docPath).update(
                mapOf(
                    "commandHistory" to bounded,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteProfile(userId: String, profileId: String): Result<Unit> {
        val docPath = "users/$userId/profiles/$profileId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(profileId.isNotBlank()) { "Profile ID cannot be blank." }
            db.document(docPath).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveScript(
        userId: String,
        profileId: String,
        title: String,
        language: String,
        code: String,
        description: String,
        sourceType: String,
    ): Result<String> {
        val colPath = "users/$userId/scripts"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }

            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "Script title cannot be empty." }
            val cleanCode = code.trim().take(12000)
            require(cleanCode.isNotEmpty()) { "Script code cannot be empty." }
            val validLang = if (language in ALLOWED_LANGUAGES) language else "sh"
            val validSource = if (sourceType in ALLOWED_SOURCE_TYPES) sourceType else "generated"

            val docRef = db.collection("users").document(userId).collection("scripts").document()
            val payload = mapOf(
                "userId" to uid,
                "profileId" to profileId.ifBlank { "prof_alpaca_core" }.take(128),
                "title" to cleanTitle,
                "language" to validLang,
                "code" to cleanCode,
                "description" to description.trim().take(500),
                "sourceType" to validSource,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, colPath)
            Result.failure(e)
        }
    }

    suspend fun deleteScript(userId: String, scriptId: String): Result<Unit> {
        val docPath = "users/$userId/scripts/$scriptId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            require(scriptId.isNotBlank()) { "Script ID cannot be blank." }
            db.document(docPath).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    companion object {
        private val ALLOWED_SHELLS = setOf("/bin/ash", "/bin/sh", "/bin/bash", "/bin/zsh")
        private val ALLOWED_MODELS = setOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon"
        )
        private val ALLOWED_LANGUAGES = setOf(
            "sh", "bash", "python", "c", "awk", "sed", "dockerfile", "other"
        )
        private val ALLOWED_SOURCE_TYPES = setOf("generated", "debugged", "manual")
    }
}
