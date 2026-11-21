package com.samford.patient.ui

import kotlinx.serialization.Serializable

@Serializable
object WelcomeRoute

@Serializable
object SignInRoute

@Serializable
object ServerRoute

@Serializable
object HomeRoute

@Serializable
object RecordsRoute

@Serializable
object SettingsRoute

@Serializable
data class VisitRoute(val visitId: String)

/** [version] 0 means "the current version". */
@Serializable
data class DocumentRoute(val documentId: String, val version: Int = 0)

@Serializable
data class LabResultRoute(val resultId: String)
