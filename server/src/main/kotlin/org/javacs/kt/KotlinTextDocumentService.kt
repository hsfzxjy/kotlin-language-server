package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.completion.*
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.externalsources.JarClassContentProvider
import org.javacs.kt.formatting.formatKotlinCode
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.noResult
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.commands.JAVA_TO_KOTLIN_COMMAND
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val uriContentProvider: URIContentProvider
) : TextDocumentService, Closeable {
    private lateinit var client: LanguageClient
    private val async = AsyncExecutor()
    private var linting = false

    var debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    val lintTodo = mutableSetOf<Path>()
    var lintCount = 0

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private val TextDocumentItem.filePath: Path?
        get() = URI.create(uri).takeIf { it.scheme == "file" }?.let(Paths::get)

    private val TextDocumentIdentifier.filePath: Path?
        get() = URI.create(uri).takeIf { it.scheme == "file" }?.let(Paths::get)

    private val TextDocumentIdentifier.isKotlinScript: Boolean
        get() = uri.endsWith(".kts")

    private val TextDocumentIdentifier.content: String
        get() = uriContentProvider.contentOfEncoded(uri)

    private enum class Recompile {
        ALWAYS, AFTER_DOT, WAIT_FOR_LINT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int> {
        val file = position.textDocument.filePath!! // TODO
        val content = sp.content(file)
        val offset = offset(content, position.position.line, position.position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.WAIT_FOR_LINT -> {
                debounceLint.waitForPendingTask()
                false
            }
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) sp.currentVersion(file) else sp.latestCompiledVersion(file)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val start = params.range.start
        val end = params.range.end
        val hasSelection = (end.character - start.character) != 0 || (end.line - start.line) != 0
        if (hasSelection) {
            listOf(
                Either.forLeft<Command, CodeAction>(
                    Command("Convert Java to Kotlin", JAVA_TO_KOTLIN_COMMAND, listOf(
                        params.textDocument.uri,
                        params.range
                    ))
                )
            )
        } else {
            emptyList()
        }
    }

    override fun hover(position: TextDocumentPositionParams): CompletableFuture<Hover?> = async.compute {
        reportTime {
            LOG.info("Hovering at {} {}:{}", position.textDocument.uri, position.position.line, position.position.character)

            val (file, cursor) = recover(position, Recompile.NEVER)
            hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
        }
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<List<DocumentHighlight>> {
        TODO("not implemented")
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun definition(position: TextDocumentPositionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        reportTime {
            LOG.info("Go-to-definition at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER)
            goToDefinition(file, cursor, uriContentProvider.jarClassContentProvider, config.externalSources)
                ?.let(::listOf)
                ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
                ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = extractRange(params.textDocument.content, params.range)
        listOf(TextEdit(
            params.range,
            formatKotlinCode(
                code,
                isScript = params.textDocument.isKotlinScript,
                options = params.options
            )
        ))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        TODO("not implemented")
    }

    override fun completion(position: CompletionParams) = async.compute {
        reportTime {
            LOG.info("Completing at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER) // TODO: Investigate when to recompile
            val completions = completions(file, cursor, config.completion)
            LOG.info("Found {} items", completions.items.size)

            Either.forRight<List<CompletionItem>, CompletionList>(completions)
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", params.textDocument)

        reportTime {
            params.textDocument.filePath
                ?.let(sp::parsedFile)
                ?.let(::documentSymbols)
                ?: noResult("Could not find document symbols", emptyList())
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        params.textDocument.filePath?.let { file ->
            sf.open(file, params.textDocument.text, params.textDocument.version)
            lintNow(file)
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        params.textDocument.filePath?.let { file ->
            lintNow(file)
        }
    }

    override fun signatureHelp(position: TextDocumentPositionParams): CompletableFuture<SignatureHelp?> = async.compute {
        reportTime {
            LOG.info("Signature help at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER)
            fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        params.textDocument.filePath?.let { file ->
            sf.close(file)
            clearDiagnostics(file)
        }
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = params.textDocument.content
        LOG.info("{}", params.textDocument.uri)
        listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formatKotlinCode(
                code,
                isScript = params.textDocument.isKotlinScript,
                options = params.options
            )
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        params.textDocument.filePath?.let { file ->
            sf.edit(file, params.textDocument.version, params.contentChanges)
            lintLater(file)
        }
    }

    override fun references(position: ReferenceParams) = async.compute {
        position.textDocument.filePath
            ?.let { file ->
                val content = sp.content(file)
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sp)
            }
            ?: noResult("Could not find references at $position", emptyList())
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        val path = position.textDocument.filePath
        return "${path?.fileName} ${position.position.line + 1}:${position.position.character + 1}"
    }

    public fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.linting.debounceTime))
    }

    private fun clearLint(): List<Path> {
        val result = lintTodo.toList()
        lintTodo.clear()
        return result
    }

    private fun lintLater(file: Path) {
        lintTodo.add(file)
        if (!linting) {
            debounceLint.submit(::doLint)
        }
    }

    private fun lintNow(file: Path) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint() {
        linting = true
        try {
            LOG.info("Linting {}", describeFiles(lintTodo))
            val files = clearLint()
            val context = sp.compileFiles(files)
            reportDiagnostics(files, context.diagnostics)
            lintCount++
        } finally {
            linting = false
        }
    }

    private fun reportDiagnostics(compiled: Collection<Path>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics.flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((file, diagnostics) in byFile) {
            if (sf.isOpen(file)) {
                client.publishDiagnostics(PublishDiagnosticsParams(file.toUri().toString(), diagnostics))

                LOG.info("Reported {} diagnostics in {}", diagnostics.size, file.fileName)
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, file.fileName)
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            LOG.info("No diagnostics in {}", file.fileName)
        }

        lintCount++
    }

    private fun clearDiagnostics(file: Path) {
        client.publishDiagnostics(PublishDiagnosticsParams(file.toUri().toString(), listOf()))
    }

    private fun shutdownExecutors(awaitTermination: Boolean) {
        async.shutdown(awaitTermination)
        debounceLint.shutdown(awaitTermination)
    }

    override fun close() {
        shutdownExecutors(awaitTermination = true)
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}
