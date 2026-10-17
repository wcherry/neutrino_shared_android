package com.neutrino.shared.core.api

import kotlinx.serialization.Serializable

// Response shapes from `neutrino/src/drive/filesystem/dto.rs` and `auth/dto.rs` that every app
// needs — camelCase on the wire. App-specific shapes live in each app.
// Every field a screen can do without is optional, so a server that adds or drops one cannot take
// down browsing. Dates stay strings here; `DriveDate.parse` turns them into instants.

@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val isStarred: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class FileDto(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val isStarred: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** `/api/v1/drive/files/{id}/thumbnail?v=…`, bearer-authenticated. Plaintext by design — see the upload. */
    val coverThumbnailUrl: String? = null,
    val contentVersion: Long? = null,
    val importSource: String? = null,
    val deletedAt: String? = null,
    /** The file's name and type, encrypted with its DEK; empty or absent for a plaintext file. */
    val encryptedMetadata: String? = null,
)

@Serializable
data class FolderContentsDto(
    val folder: FolderDto? = null,
    val folders: List<FolderDto> = emptyList(),
    val files: List<FileDto> = emptyList(),
)

@Serializable
data class FileKeyDto(
    val encryptedFileKey: String,
    /** Absent from servers that predate rotation; those DEKs are sealed to version 1. */
    val keyVersion: Int? = null,
)

@Serializable
data class PublicKeyDto(val userId: String? = null, val publicKey: String, val version: Int = 1)

@Serializable
data class UserDto(val id: String, val email: String, val name: String = "")

@Serializable
data class ProfileDto(
    val id: String,
    val email: String,
    val name: String = "",
    val totpEnabled: Boolean = false,
)
