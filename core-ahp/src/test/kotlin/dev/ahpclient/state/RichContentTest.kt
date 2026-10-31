package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.ContentRef
import com.microsoft.agenthostprotocol.generated.FileEditSide
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import com.microsoft.agenthostprotocol.generated.ToolCallState
import dev.ahpclient.ahp.AhpException
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentTest {

    @Test
    fun `parses markdown table with alignments escaped pipes and formats back to markdown and tsv`() {
        val raw = """
            | Feature | Status | Notes |
            | :--- | :---: | ---: |
            | Rich Tables | Done | Supports `a \| b` inline and \| escaped |
            | Diagrams | In Progress |
        """.trimIndent()

        val table = TableData.parseMarkdown(raw)
        assertNotNull(table)
        table!!
        assertEquals(3, table.columnCount)
        assertEquals(2, table.rowCount)
        assertEquals(listOf("Feature", "Status", "Notes"), table.headers)
        assertEquals(
            listOf(TableAlignment.START, TableAlignment.CENTER, TableAlignment.END),
            table.alignments,
        )
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Rich Tables", "Done", "Supports `a \\| b` inline and | escaped"), table.rows[0])
        assertEquals(listOf("Diagrams", "In Progress", ""), table.rows[1])

        val hints = table.columnCharHints()
        assertEquals(3, hints.size)
        assertTrue(hints[2] >= 20)

        val md = table.toMarkdown()
        assertTrue(md.contains(":"))
        assertTrue(md.contains("Rich Tables"))

        val tsv = table.toTsv()
        assertTrue(tsv.startsWith("Feature\tStatus\tNotes\n"))
        assertTrue(tsv.contains("Rich Tables\tDone\t"))
    }

    @Test
    fun `rejects non-tables and malformed delimiter rows`() {
        assertNull(TableData.parseMarkdown("just a single line"))
        assertNull(TableData.parseMarkdown("line 1\nline 2 without dashes"))
        assertNull(TableData.parseMarkdown("| A | B |\n| not-a-delimiter | --- |"))
    }

    @Test
    fun `parses mermaid flowcharts and state diagrams`() {
        val flowchartCode = """
            flowchart LR
                %% comment
                subgraph Backend
                A[Client App] --> B(Auth Service)
                B -->|valid| C{Allowed?}
                C -.->|no| D((Denied))
                C ==>|yes| E[[Session Store]]
                E --> F([Done])
                end
        """.trimIndent()

        assertTrue(DiagramModel.isDiagramFence("mermaid", flowchartCode))
        val diagram = DiagramModel.parse("mermaid", flowchartCode) as? DiagramModel.Flowchart
        assertNotNull(diagram)
        diagram!!
        assertEquals(DiagramModel.Direction.LR, diagram.direction)
        assertEquals(6, diagram.nodes.size)
        assertEquals(5, diagram.edges.size)
        assertEquals(DiagramModel.NodeShape.RECT, diagram.nodes.first { it.id == "A" }.shape)
        assertEquals(DiagramModel.NodeShape.ROUNDED, diagram.nodes.first { it.id == "B" }.shape)
        assertEquals(DiagramModel.NodeShape.DIAMOND, diagram.nodes.first { it.id == "C" }.shape)
        assertEquals(DiagramModel.NodeShape.CIRCLE, diagram.nodes.first { it.id == "D" }.shape)
        assertEquals(DiagramModel.NodeShape.SUBROUTINE, diagram.nodes.first { it.id == "E" }.shape)
        assertEquals(DiagramModel.NodeShape.STADIUM, diagram.nodes.first { it.id == "F" }.shape)
        assertTrue(diagram.edges.any { it.from == "C" && it.to == "D" && it.dotted && it.label == "no" })
        assertTrue(diagram.edges.any { it.from == "C" && it.to == "E" && it.label == "yes" })

        val stateCode = """
            stateDiagram-v2
                [*] --> Idle
                state "Working Hard" as Working
                Idle --> Working : start
                Working --> [*] : finish
        """.trimIndent()
        val stateDiagram = DiagramModel.parse("mermaid", stateCode) as? DiagramModel.Flowchart
        assertNotNull(stateDiagram)
        stateDiagram!!
        assertEquals(DiagramModel.Direction.TD, stateDiagram.direction)
        assertTrue(stateDiagram.nodes.any { it.id == "Working" && it.label == "Working Hard" })
        assertEquals(3, stateDiagram.edges.size)
    }

    @Test
    fun `parses mermaid sequence diagrams`() {
        val seqCode = """
            sequenceDiagram
                autonumber
                participant C as Android Client
                actor H as Host Agent
                C->>H: initialize
                H-->>C: snapshots
                C->H: ping
        """.trimIndent()

        val seq = DiagramModel.parse("mermaid", seqCode) as? DiagramModel.Sequence
        assertNotNull(seq)
        seq!!
        assertEquals(2, seq.participants.size)
        assertEquals("Android Client", seq.participants[0].label)
        assertEquals("Host Agent", seq.participants[1].label)
        assertEquals(3, seq.messages.size)
        assertFalse(seq.messages[0].dashed)
        assertTrue(seq.messages[1].dashed)
        assertEquals("snapshots", seq.messages[1].text)
    }

    @Test
    fun `parses svg shapes and text elements`() {
        val svgCode = """
            <svg viewBox="0 0 240 120" xmlns="http://www.w3.org/2000/svg">
                <rect x="10" y="10" width="80" height="40" rx="4" fill="#3b82f6" stroke="#1d4ed8" stroke-width="2"/>
                <circle cx="150" cy="30" r="20" fill="red" stroke="black"/>
                <line x1="10" y1="70" x2="90" y2="100" stroke="green" stroke-width="3"/>
                <path d="M 10 10 L 50 50 H 70 V 80 Z" fill="none" stroke="teal"/>
                <text x="50" y="35" fill="white">Hello SVG</text>
            </svg>
        """.trimIndent()

        assertTrue(DiagramModel.isDiagramFence("svg", svgCode))
        assertTrue(DiagramModel.isDiagramFence("xml", svgCode))
        val svg = DiagramModel.parse("svg", svgCode) as? DiagramModel.Svg
        assertNotNull(svg)
        svg!!
        assertEquals(240f, svg.viewBoxWidth, 0.01f)
        assertEquals(120f, svg.viewBoxHeight, 0.01f)
        assertEquals(5, svg.elements.size)
        val textEl = svg.elements.filterIsInstance<DiagramModel.SvgElement.TextLabel>().single()
        assertEquals("Hello SVG", textEl.text)
    }

    @Test
    fun `decodes embedded and remote resources safely with magic byte checks`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        val pngBase64 = Base64.getEncoder().encodeToString(pngBytes)
        val decodedImage = RichDecoder.decodeEmbedded(
            ResourceRef(uri = null, contentType = "image/png", embeddedData = pngBase64),
        )
        assertTrue(decodedImage is RichPayload.ImageBytes)
        assertArrayEquals(pngBytes, (decodedImage as RichPayload.ImageBytes).bytes)

        // JPEG, GIF, WebP, BMP magic bytes
        assertTrue(RichDecoder.hasImageMagicBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(RichDecoder.hasImageMagicBytes("GIF89a1234".toByteArray()))
        assertTrue(RichDecoder.hasImageMagicBytes("RIFF1234WEBP".toByteArray()))
        assertTrue(RichDecoder.hasImageMagicBytes(ByteArray(14).apply { this[0] = 0x42; this[1] = 0x4D }))
        assertFalse(RichDecoder.hasImageMagicBytes(byteArrayOf(1, 2)))

        // Dummy base64 without image magic bytes falls back to BinaryContent instead of crashing bitmap decoder
        val fakeImage = RichDecoder.decodeEmbedded(
            ResourceRef(uri = null, contentType = "image/png", embeddedData = "AAAA"),
        )
        assertTrue(fakeImage is RichPayload.BinaryContent)

        // SVG decoded as SvgContent
        val svgText = """<svg width="100" height="50"><rect x="0" y="0" width="100" height="50" fill="blue"/></svg>"""
        val decodedSvg = RichDecoder.decodeEmbedded(
            ResourceRef(uri = null, contentType = "image/svg+xml", embeddedData = svgText),
        )
        assertTrue(decodedSvg is RichPayload.SvgContent)
        assertNotNull((decodedSvg as RichPayload.SvgContent).model)

        // Plain text via ResourceReadResult
        val decodedText = RichDecoder.decodeResourceResult(
            ResourceReadResult(encoding = ContentEncoding.UTF8, data = "fun main() = println(42)"),
            mimeHint = "text/plain",
            pathHint = "Main.kt",
        )
        assertEquals(RichPayload.TextContent("text/plain", Clipped("fun main() = println(42)", 24), isMarkdown = false), decodedText)

        // Infer mime from path
        assertEquals("image/png", RichDecoder.inferMimeFromPath("foo/bar.png"))
        assertEquals("image/svg+xml", RichDecoder.inferMimeFromPath("foo/bar.svg"))
        assertEquals("text/markdown", RichDecoder.inferMimeFromPath("README.md"))
        assertEquals("application/json", RichDecoder.inferMimeFromPath("config.json"))
    }

    @Test
    fun `recognizes host file targets and rewrites markdown links only when allowHostFiles is enabled`() {
        val posix = HostFileTarget.of("file:///home/me/repo/src/Main.kt#L10-L20")
        assertNotNull(posix)
        assertEquals("/home/me/repo/src/Main.kt", posix!!.rawPath)
        assertEquals(10, posix.startLine)
        assertEquals(20, posix.endLine)
        assertEquals("file:///home/me/repo/src/Main.kt", posix.resolveUri())

        val win = HostFileTarget.of("C:\\work\\repo\\App.kt:42")
        assertNotNull(win)
        assertEquals("C:/work/repo/App.kt", win!!.rawPath)
        assertEquals(42, win.startLine)
        assertEquals("file:///C:/work/repo/App.kt", win.resolveUri())

        val rel = HostFileTarget.of("src/ui/ChatScreen.kt#L15")
        assertNotNull(rel)
        assertEquals("file:///workspace/project/src/ui/ChatScreen.kt", rel!!.resolveUri(listOf("/workspace/project")))

        val roundTrip = HostFileTarget.fromInternalUri(rel.toInternalUri())
        assertEquals(rel, roundTrip)

        // Non-file schemes are never HostFileTargets
        assertNull(HostFileTarget.of("https://example.com/a.kt"))
        assertNull(HostFileTarget.of("vscode://file/work/a.kt"))
        assertNull(HostFileTarget.of("javascript:alert(1)"))

        val md = "See [Main.kt](file:///home/me/repo/src/Main.kt#L10-L20) and [Docs](https://example.com/docs)."
        assertEquals(
            "See Main.kt and [Docs](https://example.com/docs).",
            MarkdownLinks.keepOpenable(md, allowHostFiles = false),
        )
        val rewritten = MarkdownLinks.keepOpenable(md, allowHostFiles = true)
        assertTrue(rewritten.contains("[Main.kt](ahp-file://open?path="))
        assertTrue(rewritten.contains("[Docs](https://example.com/docs)"))
    }

    @Test
    fun `loads inline file edit diffs and host file reader payloads`() = runTest {
        val store = mapOf(
            "ahp:before" to ResourceReadResult(data = "line 1\nline 2\n", encoding = ContentEncoding.UTF8),
            "ahp:after" to ResourceReadResult(data = "line 1\nline 2 changed\nline 3\n", encoding = ContentEncoding.UTF8),
            "file:///repo/src/Main.kt" to ResourceReadResult(data = "package demo\nfun hi() {}\n", encoding = ContentEncoding.UTF8),
        )
        val diffs = ChangesetDiffs(files = { emptyList() }, read = { uri -> store[uri] ?: throw IOException("missing $uri") })
        val result = diffs.loadSides(
            before = FileEditSide("file:///repo/a.kt", ContentRef("ahp:before", 14L)),
            after = FileEditSide("file:///repo/a.kt", ContentRef("ahp:after", 29L)),
        )
        assertTrue(result is DiffResult.Ready)

        val reader = HostFileReader(
            read = { uri -> store[uri] ?: throw AhpException.Rpc(-32000, "File not found", null) },
            workingDirectories = { listOf("/repo") },
        )
        val target = HostFileTarget.of("src/Main.kt#L1-L2")!!
        val readRes = reader.readTarget(target)
        assertTrue(readRes is RichReadResult.Ready)
        val ready = readRes as RichReadResult.Ready
        assertEquals(1, ready.startLine)
        assertEquals(2, ready.endLine)
        assertEquals(RichPayload.TextContent("text/plain", Clipped("package demo\nfun hi() {}\n", 25), isMarkdown = false), ready.payload)

        val embeddedRes = reader.readResourceRef(
            ResourceRef(uri = null, contentType = "text/plain", embeddedData = Base64.getEncoder().encodeToString("hello".toByteArray())),
            title = "note.txt",
        )
        assertTrue(embeddedRes is RichReadResult.Ready)

        val remoteRes = reader.readResourceRef(
            ResourceRef(uri = "file:///repo/src/Main.kt", contentType = "text/plain", sizeHint = 25L),
            title = "Main.kt",
        )
        assertTrue(remoteRes is RichReadResult.Ready)

        val missingRes = reader.readTarget(HostFileTarget.of("src/Missing.kt")!!)
        assertTrue(missingRes is RichReadResult.Failed)
    }

    @Test
    fun `populates metadata on ToolResultView and TranscriptItem without changing equals or toString`() {
        val callJson = """
            {
              "status": "completed",
              "toolCallId": "tc1",
              "toolName": "edit",
              "displayName": "Edit",
              "invocationMessage": "Edit file",
              "toolInput": "{}",
              "confirmed": "not-needed",
              "success": true,
              "pastTenseMessage": "Edited file",
              "content": [
                {
                  "type": "fileEdit",
                  "before": {"uri": "file:///work/a.txt", "content": {"uri": "ahp:b", "sizeHint": 10}},
                  "after": {"uri": "file:///work/a.txt", "content": {"uri": "ahp:a", "sizeHint": 20}},
                  "diff": {"added": 2, "removed": 1}
                },
                {
                  "type": "embeddedResource",
                  "contentType": "image/png",
                  "data": "AAAA"
                },
                {
                  "type": "resource",
                  "uri": "ahp:res/1",
                  "contentType": "text/plain",
                  "sizeHint": 42
                }
              ]
            }
        """.trimIndent()
        val call = Ahp.json.decodeFromString(ToolCallState.serializer(), callJson)
        val detail = ToolDetail.from(call)
        val editView = detail.results[0] as ToolResultView.FileEdit
        assertEquals(ToolResultView.FileEdit("/work/a.txt", 2L, 1L), editView)
        assertTrue(editView.canOpenDiff)
        assertNotNull(editView.before)
        assertNotNull(editView.after)

        val embeddedAtt = detail.results[1] as ToolResultView.Attachment
        assertEquals(ToolResultView.Attachment("Attachment: image/png"), embeddedAtt)
        assertNotNull(embeddedAtt.resourceRef)
        assertTrue(embeddedAtt.resourceRef!!.isImage)

        val remoteAtt = detail.results[2] as ToolResultView.Attachment
        assertEquals(ToolResultView.Attachment("Attachment: text/plain"), remoteAtt)
        assertEquals(ResourceRef(uri = "ahp:res/1", contentType = "text/plain", sizeHint = 42L), remoteAtt.resourceRef)
        assertTrue(remoteAtt.resourceRef!!.isTextLike)

        val chatJson = """
            {
              "resource": "ahp-chat:/c",
              "title": "Test",
              "status": 8,
              "modifiedAt": "2026-10-22T10:00:00.000Z",
              "turns": [
                {
                  "id": "t1",
                  "message": {"text": "hi", "origin": {"kind": "user"}},
                  "responseParts": [
                    {"kind": "contentRef", "uri": "ahp:ref/9", "sizeHint": 100, "contentType": "image/svg+xml"}
                  ],
                  "startedAt": "2026-10-22T10:00:00.000Z",
                  "state": "complete"
                }
              ]
            }
        """.trimIndent()
        val chat = Ahp.json.decodeFromString(ChatState.serializer(), chatJson)
        val transcript = Transcript.from(chat)
        val other = transcript.items.filterIsInstance<TranscriptItem.Other>().single()
        assertEquals(TranscriptItem.Other("t1/#0", "Attached content (image/svg+xml)"), other)
        assertEquals(ResourceRef(uri = "ahp:ref/9", contentType = "image/svg+xml", sizeHint = 100L), other.resourceRef)
        assertTrue(other.resourceRef!!.isSvg)
    }
}
