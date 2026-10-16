package com.margelo.nitro.betterpdf

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentCacheTest {
  @get:Rule
  val temp = TemporaryFolder()

  private lateinit var server: TestServer
  private lateinit var executor: ExecutorService
  private lateinit var cache: DocumentCache
  private val clock = AtomicLong(1_000_000)

  private val root get() = File(temp.root, "cache/BetterPdf")
  private val sessionDirectory get() = File(root, "session")
  private val partialDirectory get() = File(root, "partial")
  private val persistentDirectory get() = File(temp.root, "files/BetterPdf/documents")

  @Before
  fun setUp() {
    server = TestServer()
    executor = Executors.newCachedThreadPool()
    cache = newCache()
  }

  @After
  fun tearDown() {
    server.stop()
    executor.shutdownNow()
  }

  /** A cache over the same directories, as after an app restart. */
  private fun newCache() = DocumentCache(createStore = ::newStore, executor = executor, clock = clock::get)

  private fun newStore() =
    DocumentCacheStore(
      sessionDirectory = sessionDirectory,
      persistentDirectory = persistentDirectory,
      partialDirectory = partialDirectory,
      legacyDirectory = root,
      link = { existing, link -> Files.createLink(link.toPath(), existing.toPath()) },
    )

  private fun source(
    uri: String = server.url(),
    headers: Map<String, String>? = null,
    cache: PdfCacheMode? = null,
    maxAgeMs: Double? = null,
    cacheKey: String? = null,
  ) = NativePdfSource(uri, headers = headers, cache = cache, maxAgeMs = maxAgeMs, cacheKey = cacheKey)

  private fun fetch(source: NativePdfSource, allowStale: Boolean = false) =
    Recorder().also { it.subscription = cache.fetch(source, allowStale, it) }

  // region Reuse and freshness

  @Test
  fun `downloads once, then serves the stored copy`() {
    val first = fetch(source()).ready()
    val second = fetch(source()).ready()
    assertEquals(first.copy.file, second.copy.file)
    assertArrayEquals(pdf("v1"), second.copy.file.readBytes())
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `persistent copies survive a restart, session copies do not`() {
    fetch(source(server.url("/session.pdf"))).ready()
    fetch(source(server.url("/persistent.pdf"), cache = PdfCacheMode.PERSISTENT)).ready()

    val restarted = newCache()
    assertNull(restarted.find(source(server.url("/session.pdf"))))
    val persistent = restarted.find(source(server.url("/persistent.pdf")))
    assertEquals(PdfCacheMode.PERSISTENT, persistent?.mode)
    assertEquals(persistentDirectory, persistent?.file?.parentFile)
  }

  @Test
  fun `a copy younger than maxAgeMs is not revalidated`() {
    fetch(source()).ready()
    clock.addAndGet(999)
    fetch(source(maxAgeMs = 1_000.0)).ready()
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `an older copy is revalidated with its ETag and kept on 304`() {
    val first = fetch(source()).ready()
    clock.addAndGet(5_000)
    val second = fetch(source(maxAgeMs = 0.0)).ready()

    assertEquals(2, server.requests.size)
    assertEquals("\"v1\"", server.requests[1].ifNoneMatch)
    assertEquals(first.copy.file, second.copy.file)
    assertEquals(clock.get(), second.copy.metadata.validatedAtMs)
    assertFalse(second.isStale)
  }

  @Test
  fun `a changed document replaces the copy`() {
    fetch(source()).ready()
    server.body = pdf("v2")
    server.etag = "\"v2\""
    val updated = fetch(source(maxAgeMs = 0.0)).ready()
    assertArrayEquals(pdf("v2"), updated.copy.file.readBytes())
    assertEquals("\"v2\"", updated.copy.metadata.etag)
  }

  // endregion

  // region Failures and offline

  @Test
  fun `an unreachable server falls back to the stored copy only when allowed`() {
    val stored = fetch(source()).ready()
    server.stop()

    val stale = fetch(source(maxAgeMs = 0.0), allowStale = true).ready()
    assertTrue(stale.isStale)
    assertEquals(stored.copy.file, stale.copy.file)

    val failure = fetch(source(maxAgeMs = 0.0)).failure()
    assertEquals(PdfErrorCode.NETWORK_ERROR, failure.code)
  }

  @Test
  fun `a 5xx falls back to the stored copy, a 4xx does not`() {
    fetch(source()).ready()

    server.status = 503
    assertTrue(fetch(source(maxAgeMs = 0.0), allowStale = true).ready().isStale)

    server.status = 404
    val failure = fetch(source(maxAgeMs = 0.0), allowStale = true).failure()
    assertEquals(PdfErrorCode.HTTP_ERROR, failure.code)
    assertEquals(404, failure.httpStatus)
    assertNotNull("the copy is kept", cache.find(source()))
  }

  @Test
  fun `without a stored copy an unreachable server is an error`() {
    server.stop()
    assertEquals(PdfErrorCode.NETWORK_ERROR, fetch(source(), allowStale = true).failure().code)
  }

  @Test
  fun `a response that is not a PDF is not stored`() {
    server.body = "<html>Sign in</html>".toByteArray()
    assertEquals(PdfErrorCode.INVALID_PDF, fetch(source()).failure().code)
    assertNull(cache.find(source()))
    assertEquals(0, partialDirectory.listFiles()?.size ?: 0)
  }

  @Test
  fun `non-http sources are rejected`() {
    assertEquals(PdfErrorCode.INVALID_SOURCE, fetch(source("file:///document.pdf")).failure().code)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `an invalid header is never sent and does not block the source`() {
    val invalid = source(headers = mapOf("Authorization" to "Bearer a\nb"), cacheKey = "document-1")
    assertEquals(PdfErrorCode.INVALID_SOURCE, fetch(invalid).failure().code)
    assertEquals(0, server.requests.size)

    fetch(source(headers = mapOf("Authorization" to "Bearer a"), cacheKey = "document-1")).ready()
    assertEquals(1, server.requests.size)

    // Checked before the stored copy is looked up, fresh or not: a source
    // that can never be revalidated is not served.
    assertEquals(PdfErrorCode.INVALID_SOURCE, fetch(invalid).failure().code)
    clock.addAndGet(5_000)
    val stale = fetch(source(headers = invalid.headers, maxAgeMs = 0.0, cacheKey = "document-1"), allowStale = true)
    assertEquals(PdfErrorCode.INVALID_SOURCE, stale.failure().code)
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `a request the platform rejects still ends the download`() {
    // DocumentCache checks headers first; this covers whatever it misses.
    var outcome: DownloadOutcome? = null
    SharedDownload(
      key = source().storeKey(),
      url = server.url(),
      // Rejected by HttpURLConnection.setRequestProperty, as on Android.
      headers = mapOf("X-Test" to "a\nb"),
      existing = null,
      mode = PdfCacheMode.SESSION,
      store = newStore(),
      clock = clock::get,
      onProgress = { _, _, _, _ -> },
      onFinished = { _, finished -> outcome = finished },
    ).run()

    val failed = outcome as DownloadOutcome.Failed
    assertEquals(PdfErrorCode.INVALID_SOURCE, failed.failure.code)
    assertFalse(failed.isServerUnreachable)
    assertNull("the platform's message quotes the value", failed.failure.toPdfError().nativeDescription)
    assertEquals(0, server.requests.size)
  }

  // endregion

  // region Sharing and cancellation

  @Test
  fun `concurrent requests for one copy share one download`() {
    val gate = server.hold()
    val first = fetch(source())
    val second = fetch(source(cache = PdfCacheMode.PERSISTENT))
    server.awaitRequests(1)
    gate.countDown()

    // Whichever starts first decides where the copy goes; a session request
    // may get the persistent copy, never the other way round.
    assertArrayEquals(pdf("v1"), first.ready().copy.file.readBytes())
    assertEquals(PdfCacheMode.PERSISTENT, second.ready().copy.mode)
    assertArrayEquals(pdf("v1"), second.ready().copy.file.readBytes())
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `cancelling the only subscriber stops the download and stores nothing`() {
    val gate = server.hold()
    val recorder = fetch(source())
    server.awaitRequests(1)
    recorder.subscription.cancel()
    gate.countDown()
    awaitIdle()

    assertFalse(recorder.result.isDone)
    assertNull(cache.find(source()))
    assertEquals(0, partialDirectory.listFiles()?.size ?: 0)
  }

  @Test
  fun `cancelling one subscriber keeps the download for the others`() {
    val gate = server.hold()
    val cancelled = fetch(source())
    val kept = fetch(source())
    server.awaitRequests(1)
    cancelled.subscription.cancel()
    gate.countDown()

    kept.ready()
    awaitIdle()
    assertFalse(cancelled.result.isDone)
    assertEquals(1, server.requests.size)
  }

  // endregion

  // region Keys and storage

  @Test
  fun `a cacheKey identifies the copy whatever the URL`() {
    fetch(source(server.url("/doc.pdf?token=a"), cacheKey = "document-1")).ready()
    fetch(source(server.url("/doc.pdf?token=b"), cacheKey = "document-1")).ready()
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `a persistent request keeps a session copy, whose path stays valid`() {
    val session = fetch(source()).ready().copy
    val persistent = fetch(source(cache = PdfCacheMode.PERSISTENT)).ready().copy

    assertEquals(1, server.requests.size)
    assertEquals(PdfCacheMode.PERSISTENT, persistent.mode)
    assertTrue(session.file.isFile)
    assertArrayEquals(session.file.readBytes(), persistent.file.readBytes())
    assertEquals(pdf("v1").size.toLong(), cache.byteSize())
  }

  @Test
  fun `remove, clear and byteSize`() {
    fetch(source(server.url("/a.pdf"))).ready()
    fetch(source(server.url("/b.pdf"), cache = PdfCacheMode.PERSISTENT)).ready()
    assertEquals(2L * pdf("v1").size, cache.byteSize())

    assertTrue(cache.remove(source(server.url("/a.pdf"))))
    assertFalse(cache.remove(source(server.url("/a.pdf"))))
    assertEquals(pdf("v1").size.toLong(), cache.byteSize())

    cache.clear()
    assertEquals(0L, cache.byteSize())
    assertNull(cache.find(source(server.url("/b.pdf"))))
  }

  @Test
  fun `contains files in the store's directories and below them, wherever the path leads`() {
    sessionDirectory.mkdirs()
    val exports = File(temp.root, "exports").also { it.mkdirs() }
    // As `/data/data` and `/data/user/0`: two paths to the same directory.
    val link = Files.createSymbolicLink(File(temp.root, "link").toPath(), sessionDirectory.toPath()).toFile()

    assertTrue(cache.contains(File(sessionDirectory, "a.pdf")))
    assertTrue(cache.contains(File(persistentDirectory, "a.pdf")))
    assertTrue(cache.contains(File(partialDirectory, "a.1.part")))
    assertTrue(cache.contains(File(sessionDirectory, "sub/a.pdf")))
    assertTrue(cache.contains(File(link, "a.pdf")))
    assertTrue(cache.contains(File(exports, "../cache/BetterPdf/session/a.pdf")))

    assertFalse(cache.contains(File(exports, "a.pdf")))
    assertFalse(cache.contains(File(sessionDirectory, "../a.pdf")))
    assertFalse(cache.contains(File(root, "session-old/a.pdf")))
    // The app's own files beside the store's directories.
    assertFalse(cache.contains(File(root, "renders/page.png")))
  }

  @Test
  fun `the first use deletes the previous session, unfinished downloads and the old layout`() {
    val hash = "a".repeat(64)
    val oldSession = File(sessionDirectory, "$hash.pdf").also { it.parentFile!!.mkdirs(); it.writeText("x") }
    val oldPartial = File(partialDirectory, "$hash.1.part").also { it.parentFile!!.mkdirs(); it.writeText("x") }
    val legacy = File(root, "$hash.pdf").also { it.writeText("x") }
    val legacyPartial = File(root, "$hash.123.part").also { it.writeText("x") }
    val render = File(root, "renders/page.png").also { it.parentFile!!.mkdirs(); it.writeText("x") }
    val rawCopy = File(root, "raw-sample-1.pdf").also { it.writeText("x") }
    val persistent = File(persistentDirectory, "$hash.pdf").also { it.parentFile!!.mkdirs(); it.writeText("x") }

    cache.byteSize()

    assertFalse(oldSession.exists())
    assertFalse(oldPartial.exists())
    assertFalse(legacy.exists())
    assertFalse(legacyPartial.exists())
    assertTrue(render.exists())
    assertTrue(rawCopy.exists())
    assertTrue(persistent.exists())
  }

  // endregion

  private fun awaitIdle() {
    executor.shutdown()
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
  }

  private class Ready(val copy: CachedCopy, val isStale: Boolean)

  private class Request(val ifNoneMatch: String?)

  private class Recorder : DocumentCache.Listener {
    val result = CompletableFuture<Any>()
    lateinit var subscription: DocumentCache.Subscription

    override fun onProgress(loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) = Unit

    override fun onReady(copy: CachedCopy, isStale: Boolean) {
      result.complete(Ready(copy, isStale))
    }

    override fun onFailed(failure: PdfFailure) {
      result.complete(failure)
    }

    fun ready(): Ready =
      when (val value = result.get(5, TimeUnit.SECONDS)) {
        is Ready -> value
        is PdfFailure -> throw AssertionError("Expected a copy, got ${value.code}: ${value.message}", value)
        else -> throw AssertionError("Unexpected $value")
      }

    fun failure(): PdfFailure =
      result.get(5, TimeUnit.SECONDS) as? PdfFailure ?: throw AssertionError("Expected a failure")
  }

  /**
   * A minimal HTTP/1.1 server (one request per connection) serving `body`
   * with `etag` at every path and answering `If-None-Match` with 304.
   */
  private class TestServer {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val workers = Executors.newCachedThreadPool()

    @Volatile var body: ByteArray = pdf("v1")

    @Volatile var etag: String? = "\"v1\""

    /** Answer every request with this status and no body. */
    @Volatile var status: Int? = null

    @Volatile private var gate: CountDownLatch? = null
    val requests = CopyOnWriteArrayList<Request>()

    init {
      workers.execute {
        while (!socket.isClosed) {
          val client = try {
            socket.accept()
          } catch (_: IOException) {
            break
          }
          workers.execute { serve(client) }
        }
      }
    }

    private fun serve(client: Socket) = client.use {
      try {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
          .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val ifNoneMatch = headers["if-none-match"]
        requests += Request(ifNoneMatch)
        gate?.await(5, TimeUnit.SECONDS)
        val forced = status
        val currentEtag = etag
        val output = client.getOutputStream()
        when {
          forced != null -> output.write(head(forced, null, 0))
          currentEtag != null && ifNoneMatch == currentEtag -> output.write(head(304, currentEtag, null))
          else -> {
            val bytes = body
            output.write(head(200, currentEtag, bytes.size))
            output.write(bytes)
          }
        }
        output.flush()
      } catch (_: IOException) {
        // The client went away (a cancelled download).
      }
    }

    private fun head(status: Int, etag: String?, contentLength: Int?): ByteArray =
      buildString {
        append("HTTP/1.1 $status Status\r\n")
        etag?.let { append("ETag: $it\r\n") }
        contentLength?.let { append("Content-Length: $it\r\n") }
        append("Connection: close\r\n\r\n")
      }.toByteArray(Charsets.ISO_8859_1)

    fun url(path: String = "/doc.pdf") = "http://127.0.0.1:${socket.localPort}$path"

    /** Holds responses until the returned latch opens. */
    fun hold(): CountDownLatch = CountDownLatch(1).also { gate = it }

    fun awaitRequests(count: Int) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (requests.size < count) {
        if (System.nanoTime() > deadline) throw AssertionError("Expected $count requests, got ${requests.size}")
        Thread.sleep(5)
      }
    }

    fun stop() {
      socket.close()
      workers.shutdownNow()
    }
  }

  private companion object {
    fun pdf(version: String) = "%PDF-1.4\n% $version\n%%EOF\n".toByteArray()
  }
}
