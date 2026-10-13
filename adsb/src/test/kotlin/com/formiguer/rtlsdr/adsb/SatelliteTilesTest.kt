package com.formiguer.rtlsdr.adsb

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatelliteTilesTest {
    @Test
    fun planCoversThePointAtEveryZoomAndIsNotHuge() {
        val plan = SatelliteTiles.plan(39.5517, 2.7388)
        val z13 = plan.filter { it.z == 13 }
        for (z in 3..13) assertTrue(plan.any { it.z == z }, "zoom $z")
        assertTrue(plan.size in 500..4000, "tamaño del plan: ${plan.size}")
        val x = Math.floor((2.7388 + 180) / 360 * (1 shl 13)).toInt()
        assertTrue(z13.any { it.x == x }, "columna de Palma en z13")
        // fila de Palma en z13 según la fórmula estándar de OSM
        assertTrue(z13.any { it.y == 3114 }, "fila ${z13.map { it.y }.distinct()}")
    }

    @Test
    fun downloadsMissingTilesOnlyAndResumes() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            hits.incrementAndGet()
            if (ex.requestURI.path.endsWith("/bad.jpg")) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val body = ByteArray(500) { 7 }
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.start()
        try {
            val dir = File.createTempFile("sat", "").apply { delete(); mkdirs() }
            val t = SatelliteTiles(dir, "http://127.0.0.1:${srv.address.port}/{z}/{x}/{y}.jpg")
            val plan = listOf(SatelliteTiles.Tile(5, 1, 2), SatelliteTiles.Tile(5, 1, 3))
            t.download(plan)
            assertEquals(2, t.done); assertEquals(0, t.failed)
            assertEquals(2 to 1000L, t.stored())
            assertTrue(t.file(5, 1, 2).isFile)
            t.download(plan + SatelliteTiles.Tile(6, 2, 4))
            assertEquals(3, t.done)
            assertEquals(3, hits.get(), "no vuelve a pedir las que ya están")
            t.clear()
            assertEquals(0, t.stored().first)
        } finally { srv.stop(0) }
    }
}
