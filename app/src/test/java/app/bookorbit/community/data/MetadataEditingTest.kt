package app.bookorbit.community.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MetadataEditingTest {
    private fun obj(value: String) = wireJson.parseToJsonElement(value).jsonObject

    @Test
    fun `patch preserves unrelated values and strips server-only series identifiers`() {
        val source =
            obj(
                """{"title":"Old","authors":[{"id":9,"name":"A"}],"audioMetadata":{"narrators":["N"],"durationSeconds":100},"seriesMemberships":[{"seriesId":2,"seriesName":"S","seriesIndex":"1","displayOrder":0}]}"""
            )
        val drafts =
            mapOf(
                "title" to "New",
                "authors" to "A",
                "audioMetadata.narrators" to "Other",
                "seriesMemberships" to
                    """[{"seriesId":2,"seriesName":"S","seriesIndex":"2","displayOrder":0}]""",
            )
        val patch = metadataPatch(source, metadataFields, drafts)
        assertEquals(setOf("title", "audioMetadata", "seriesMemberships"), patch.keys)
        assertEquals(obj("""{"narrators":["Other"]}"""), patch["audioMetadata"])
        assertEquals(
            setOf("seriesName", "seriesIndex", "expectedBookCount"),
            patch.getValue("seriesMemberships").jsonArray[0].jsonObject.keys,
        )
        assertThrows(IllegalArgumentException::class.java) {
            metadataPatch(source, metadataFields, mapOf("unknown" to "x"))
        }
    }

    @Test
    fun `custom field types and null clearing follow the metadata DTO`() {
        val source =
            obj(
                """{"customMetadata":[{"fieldId":2,"label":"Owned","type":"boolean","value":true}],"providerIds":{"google":"old"}}"""
            )
        val fields = metadataFields + customMetadataFields(source)
        val patch =
            metadataPatch(source, fields, mapOf("custom:2" to "false", "googleBooksId" to ""))
        assertEquals(JsonNull, patch["googleBooksId"])
        assertEquals(
            JsonPrimitive(false),
            patch.getValue("customMetadata").jsonArray[0].jsonObject["value"],
        )
        assertEquals("old", metadataText(metadataSource(source, "googleBooksId"), "text"))
    }

    @Test
    fun `numeric dates and compound rows are validated before sending`() {
        fun rejects(key: String, value: String) {
            assertThrows(Exception::class.java) {
                metadataValue(metadataFields.single { it.key == key }, value)
            }
        }
        rejects("publishedDate", "2026-02-30")
        rejects("rating", "4.5")
        rejects("publishedYear", "900")
        rejects("audioMetadata.durationSeconds", "-1")
        rejects("seriesMemberships", """[{"seriesName":"S","expectedBookCount":10001}]""")
        rejects("communityRatings", """[{"provider":"unknown","rating":4}]""")
        rejects("audioMetadata.chapters", """[{"title":"One","startMs":-1}]""")
        rejects("communityRatings", """[{"provider":"google","rating":4,"unexpected":1}]""")
    }
}
