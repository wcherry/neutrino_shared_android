package com.neutrino.shared.core.ooxml

/**
 * The one door in and out of the `.docx` a Neutrino document is stored as (issue #127): Drive
 * stores [MIME_TYPE], the file's *name* carries the extension so a download opens on a double-click,
 * and every office suite opens one directly. The Kotlin half of `DocxCodec.swift`.
 *
 * A picture in a document is a reference to another Drive file, and a `.docx` has to carry the
 * bytes; only the client holding the key can fetch those. So writing is two steps: [imageSources]
 * says what the document needs, the caller resolves what it can, and [encode] embeds what it was
 * given. An unresolved reference is written as a placeholder carrying the whole image node, so a
 * save made offline loses nothing.
 */
object DocxCodec {

    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val FILE_EXTENSION = "docx"

    /** The scheme an image `src` uses to point at another Drive file. */
    const val DRIVE_REFERENCE_SCHEME = "neutrino-drive:"

    fun decode(data: ByteArray): DocModel = DocxReader.read(data)

    fun isDocument(data: ByteArray): Boolean = DocxReader.isDocument(data)

    /** `title` is also what a `TITLE` field resolves to: the Drive file's name without `.docx`. */
    fun encode(model: DocModel, title: String, images: Map<String, ByteArray> = emptyMap()): ByteArray =
        DocxWriter.write(model, DocxWriteOptions(title, images))

    /** A valid, empty `.docx` — what a newly created document holds until its first save. */
    fun emptyDocument(title: String): ByteArray = encode(DocModel.empty, title)

    fun imageSources(model: DocModel): List<String> = DocxWriter.imageSources(model)

    /** `name` with `.docx` on the end, unless already there — renaming twice must not make `.docx.docx`. */
    fun withExtension(name: String): String = if (hasExtension(name)) name else "$name.$FILE_EXTENSION"

    /** `name` without a trailing `.docx`: the title to show. `Q3.report` and a legacy `.doc` keep theirs. */
    fun strippingExtension(name: String): String = if (hasExtension(name)) name.dropLast(FILE_EXTENSION.length + 1) else name

    private fun hasExtension(name: String) = name.lowercase().endsWith(".$FILE_EXTENSION")
}
