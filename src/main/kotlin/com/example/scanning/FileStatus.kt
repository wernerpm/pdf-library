package com.example.scanning

import kotlinx.serialization.Serializable

@Serializable
enum class FileStatus {
    DISCOVERED,
    EXTRACTED,
    FAILED
}
