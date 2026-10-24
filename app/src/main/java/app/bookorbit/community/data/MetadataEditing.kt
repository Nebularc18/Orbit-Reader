package app.bookorbit.community.data

import kotlinx.serialization.json.*

// Only known DTO fields can enter a metadata patch. Lists use one item per line in the editor.
data class MetadataField(
    val key: String,
    val label: String,
    val group: String = "Book",
    val kind: String = "text",
    val maxLength: Int = 1000,
    val min: Double = 0.0,
    val max: Double = Int.MAX_VALUE.toDouble(),
    val required: Boolean = false,
)

val metadataFields =
    listOf(
        MetadataField("title", "Title"),
        MetadataField("subtitle", "Subtitle"),
        MetadataField("description", "Description", maxLength = 100000),
        MetadataField("authors", "Authors", kind = "list"),
        MetadataField("publisher", "Publisher", maxLength = 500),
        MetadataField("publishedDate", "Publication date", kind = "date"),
        MetadataField(
            "publishedYear",
            "Publication year",
            kind = "integer",
            min = 1000.0,
            max = 2200.0,
        ),
        MetadataField("language", "Language", maxLength = 100),
        MetadataField("pageCount", "Page count", kind = "integer", min = 1.0),
        MetadataField("seriesName", "Series name", maxLength = 500),
        MetadataField("seriesIndex", "Series volume", kind = "volume", maxLength = 20),
        MetadataField("isbn10", "ISBN-10", maxLength = 10),
        MetadataField("isbn13", "ISBN-13", maxLength = 13),
        MetadataField("genres", "Genres", kind = "list"),
        MetadataField("tags", "Tags", kind = "list"),
        MetadataField("rating", "Personal rating", kind = "integer", min = 1.0, max = 5.0),
        MetadataField("audioMetadata.narrators", "Narrators", "Audio", "list"),
        MetadataField("audioMetadata.durationSeconds", "Duration in seconds", "Audio", "integer"),
        MetadataField("audioMetadata.abridged", "Abridged", "Audio", "boolean"),
        MetadataField("comicMetadata.issueNumber", "Issue number", "Comic", maxLength = 50),
        MetadataField("comicMetadata.volumeName", "Volume name", "Comic", maxLength = 500),
    ) +
        listOf(
                "pencillers",
                "inkers",
                "colorists",
                "letterers",
                "coverArtists",
                "characters",
                "teams",
                "locations",
                "storyArcs",
            )
            .map {
                MetadataField(
                    "comicMetadata.$it",
                    it.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase),
                    "Comic",
                    "list",
                )
            } +
        listOf(
                "googleBooksId" to 50,
                "goodreadsId" to 50,
                "amazonId" to 20,
                "hardcoverId" to 255,
                "hardcoverEditionId" to 50,
                "openLibraryId" to 50,
                "itunesId" to 50,
                "audibleId" to 20,
                "librofmId" to 50,
                "koboId" to 255,
                "comicvineId" to 50,
                "ranobedbId" to 50,
                "lubimyczytacId" to 512,
                "aladinId" to 20,
            )
            .map {
                MetadataField(
                    it.first,
                    it.first.removeSuffix("Id").replaceFirstChar(Char::uppercase) + " ID",
                    "Identifiers",
                    maxLength = it.second,
                )
            } +
        listOf(
            MetadataField("seriesMemberships", "Series memberships", "Series", "rows"),
            MetadataField("communityRatings", "Community ratings", "Ratings", "rows"),
            MetadataField("audioMetadata.chapters", "Chapters", "Audio", "rows"),
        )

fun metadataRowFields(key: String): List<MetadataField> =
    when (key) {
        "seriesMemberships" ->
            listOf(
                MetadataField("seriesName", "Series name", maxLength = 500, required = true),
                MetadataField("seriesIndex", "Volume", kind = "volume", maxLength = 20),
                MetadataField(
                    "expectedBookCount",
                    "Expected books in series",
                    kind = "integer",
                    min = 1.0,
                    max = 10000.0,
                ),
            )
        "communityRatings" ->
            listOf(
                MetadataField("provider", "Provider key", required = true),
                MetadataField("rating", "Rating", kind = "number", max = 5.0, required = true),
                MetadataField("ratingCount", "Rating count", kind = "integer"),
            )
        "audioMetadata.chapters" ->
            listOf(
                MetadataField("title", "Chapter title", required = true),
                MetadataField(
                    "startMs",
                    "Start in milliseconds",
                    kind = "integer",
                    required = true,
                ),
                MetadataField("durationMs", "Duration in milliseconds", kind = "integer"),
            )
        else -> error("Unknown metadata rows")
    }

fun metadataSource(source: JsonObject, key: String): JsonElement {
    if (key.startsWith("custom:"))
        return source["customMetadata"]
            ?.jsonArray
            ?.firstOrNull {
                it.jsonObject["fieldId"]?.jsonPrimitive?.content == key.removePrefix("custom:")
            }
            ?.jsonObject
            ?.get("value") ?: JsonNull
    if ('.' in key)
        return (source[key.substringBefore('.')] as? JsonObject)?.get(key.substringAfter('.'))
            ?: JsonNull
    if (key.endsWith("Id") && key !in setOf("isbn10", "isbn13")) {
        val provider = if (key == "googleBooksId") "google" else key.removeSuffix("Id")
        return source[key] ?: (source["providerIds"] as? JsonObject)?.get(provider) ?: JsonNull
    }
    return source[key] ?: JsonNull
}

fun metadataText(value: JsonElement, kind: String): String =
    when {
        value is JsonNull -> ""
        kind == "rows" -> value.toString()
        value is JsonArray ->
            value.joinToString("\n") {
                if (it is JsonObject) it["name"]?.jsonPrimitive?.content.orEmpty()
                else it.jsonPrimitive.content
            }
        else -> value.jsonPrimitive.content
    }

fun metadataValue(field: MetadataField, input: String): JsonElement {
    val value = input.trim()
    require(value.length <= if (field.kind == "rows") 2 * 1024 * 1024 else field.maxLength) {
        "${field.label} is too long."
    }
    if (value.isEmpty()) {
        require(!field.required) { "${field.label} is required." }
        return if (field.kind == "list") JsonArray(emptyList())
        else if (field.kind == "rows") JsonArray(emptyList()) else JsonNull
    }
    return when (field.kind) {
        "list" ->
            JsonArray(
                value
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .also { require(it.size <= 500 && it.all { item -> item.length <= 1000 }) }
                    .map(::JsonPrimitive)
            )
        "integer",
        "number" -> {
            val number = value.toDoubleOrNull()
            require(
                number != null &&
                    number.isFinite() &&
                    number in field.min..field.max &&
                    (field.kind != "integer" || number % 1.0 == 0.0)
            ) {
                "Check ${field.label.lowercase()}."
            }
            if (field.kind == "integer") JsonPrimitive(number.toLong()) else JsonPrimitive(number)
        }
        "boolean" -> JsonPrimitive(value.toBooleanStrict())
        "date" -> {
            java.time.LocalDate.parse(value)
            JsonPrimitive(value)
        }
        "volume" -> {
            require(value.matches(Regex("\\d+(?:\\.\\d+)?")))
            JsonPrimitive(value)
        }
        "rows" -> {
            val rows = wireJson.parseToJsonElement(value).jsonArray
            require(rows.size <= 5000)
            val children = metadataRowFields(field.key)
            JsonArray(
                rows.map { row ->
                    buildJsonObject {
                        require(
                            row.jsonObject.keys.all { key ->
                                children.any { it.key == key } ||
                                    key in setOf("seriesId", "displayOrder")
                            }
                        )
                        children.forEach { child ->
                            put(
                                child.key,
                                metadataValue(
                                    child,
                                    metadataText(row.jsonObject[child.key] ?: JsonNull, child.kind),
                                ),
                            )
                        }
                        if (field.key == "communityRatings")
                            require(
                                row.jsonObject["provider"]?.jsonPrimitive?.content in
                                    setOf(
                                        "google",
                                        "goodreads",
                                        "amazon",
                                        "hardcover",
                                        "openLibrary",
                                        "itunes",
                                        "audible",
                                        "audnexus",
                                        "librofm",
                                        "comicvine",
                                        "ranobedb",
                                        "kobo",
                                        "lubimyczytac",
                                        "aladin",
                                    )
                            )
                    }
                }
            )
        }
        else -> JsonPrimitive(value)
    }
}

fun customMetadataFields(source: JsonObject): List<MetadataField> =
    (source["customMetadata"] as? JsonArray).orEmpty().map { item ->
        val row = item.jsonObject
        MetadataField(
            "custom:${row.getValue("fieldId").jsonPrimitive.int}",
            row.getValue("label").jsonPrimitive.content,
            "Custom",
            when (row["type"]?.jsonPrimitive?.content) {
                "number" -> "number"
                "boolean" -> "boolean"
                "date" -> "date"
                else -> "text"
            },
            maxLength = 100000,
            min = -Double.MAX_VALUE,
            max = Double.MAX_VALUE,
        )
    }

fun metadataPatch(
    source: JsonObject,
    fields: List<MetadataField>,
    drafts: Map<String, String>,
): JsonObject {
    val result = mutableMapOf<String, JsonElement>()
    val nested = mutableMapOf<String, MutableMap<String, JsonElement>>()
    val custom = mutableListOf<JsonElement>()
    require(drafts.keys.all { key -> fields.any { it.key == key } })
    fields.forEach { field ->
        val text = drafts[field.key] ?: return@forEach
        if (text == metadataText(metadataSource(source, field.key), field.kind)) return@forEach
        val value = metadataValue(field, text)
        when {
            field.key.startsWith("custom:") ->
                custom.add(
                    buildJsonObject {
                        put("fieldId", field.key.removePrefix("custom:").toInt())
                        put("value", value)
                    }
                )
            '.' in field.key ->
                nested
                    .getOrPut(field.key.substringBefore('.')) { mutableMapOf() }[
                        field.key.substringAfter('.')] = value
            else -> result[field.key] = value
        }
    }
    nested.forEach { (key, value) -> result[key] = JsonObject(value) }
    if (custom.isNotEmpty()) result["customMetadata"] = JsonArray(custom)
    return JsonObject(result)
}
