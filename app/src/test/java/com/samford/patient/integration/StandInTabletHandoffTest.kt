package com.samford.patient.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes a cross-app handoff file using the API-driven stand-in tablet (not the real doctor app).
 * Used to self-check CrossAppPatientVerifyTest and as a fallback in CI when the doctor app's own
 * handoff writer is not available. Run with -Psamford.itBase=<origin> -Psamford.e2eOut=<file>.
 */
@RunWith(AndroidJUnit4::class)
class StandInTabletHandoffTest {
    @Test
    fun writeHandoff() {
        val origin = System.getProperty("samford.itBase").orEmpty().trimEnd('/')
        val out = System.getProperty("samford.e2eOut").orEmpty()
        assumeTrue(origin.isNotEmpty() && out.isNotEmpty())
        val tablet = DoctorTablet(origin)
        val mobile = "9" + (100_000_000 + Random.nextInt(800_000_000)).toString()
        val (visitId, patientId) = tablet.checkIn("E2E stand-in ${mobile.takeLast(4)}", mobile)
        tablet.startVisit(visitId)
        val bundle = tablet.completeVisit(visitId, patientId)
        val handoff = CrossAppPatientVerifyTest.DoctorHandoff(
            itBase = origin,
            mobile = mobile,
            visitId = visitId,
            patientId = patientId,
            documents = bundle.documents.map { (type, id) ->
                CrossAppPatientVerifyTest.SignedDocument(id, type, 1, bundle.pages.getValue(type).map { it.sha256 })
            },
        )
        File(out).apply { parentFile?.mkdirs() }.writeText(Json.encodeToString(CrossAppPatientVerifyTest.DoctorHandoff.serializer(), handoff))
    }
}
