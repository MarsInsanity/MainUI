package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.store.ThemeStore
import io.github.marsinsanity.mainui.theme.OnionColor
import io.github.marsinsanity.mainui.theme.themeFolders
import io.github.marsinsanity.mainui.theme.themeRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThemePlumbingTest {
    private fun rgb(v: Long) = (0xFF000000L or v).toInt()

    @Test fun stockColourReadsPairsAndIgnoresTrailingJunk() {
        assertEquals(rgb(0xFFFFFF), OnionColor.stock("#FFFFFF"))
        assertEquals(rgb(0xDDDDDD), OnionColor.stock("#DDDDDD;"))
        assertEquals(rgb(0x304B07), OnionColor.stock("#304b07;"))
        assertEquals(rgb(0xFFFFFF), OnionColor.stock("#fffffff"))
        // (each pair on its own: "#e" reads as 0)
        assertEquals(rgb(0x0089B0), OnionColor.stock("##e89b0c"))
        assertEquals(rgb(0x0A0000), OnionColor.stock("#a;0000x"))
    }

    @Test fun stockColourKeepsTheDefaultForWhatItCantRead() {
        for (s in listOf("#000", "#14646", "6E6E6E", "white", "rgb(10, 20, 30)", "rgba(0,0,0,0)", "")) {
            assertNull(s, OnionColor.stock(s))
        }
    }

    @Test fun hex2sdlIsStrtoul() {
        assertEquals(rgb(0x000000), OnionColor.hex2sdl("#000"))
        assertEquals(rgb(0x014646), OnionColor.hex2sdl("#14646"))
        assertEquals(rgb(0x6E6E6E), OnionColor.hex2sdl("6E6E6E"))
        assertEquals(rgb(0xDDDDDD), OnionColor.hex2sdl("#DDDDDD;"))
        assertEquals(rgb(0xFFFFFF), OnionColor.hex2sdl("#fffffff"))
        assertEquals(rgb(0x345678), OnionColor.hex2sdl("#12345678"))
        // (overflow: ULONG_MAX)
        assertEquals(rgb(0xFFFFFF), OnionColor.hex2sdl("#123456789"))
        for (s in listOf("white", "rgba(255,255,255,1)", "##e89b0c", "")) assertEquals(s, rgb(0), OnionColor.hex2sdl(s))
    }

    @Test fun zipThemeRoots() {
        assertEquals(listOf(""), themeRoots(listOf("config.json", "skin/background.png", "icons/gb.png")))
        assertEquals(listOf("Blue"), themeRoots(listOf("Blue/", "Blue/config.json", "Blue/skin/bg-list-s.png")))
        assertEquals(listOf("Skinny"), themeRoots(listOf("Skinny/skin/", "Skinny/skin/background.png")))
        assertEquals(listOf("A", "B"), themeRoots(listOf("A/config.json", "B/config.json", "B/skin/x.png")))
        // A pack zipped with its own folder on top.
        assertEquals(listOf("Pack (2-pack)/Dark", "Pack (2-pack)/Light"),
            themeRoots(listOf("Pack (2-pack)/", "Pack (2-pack)/Dark/config.json", "Pack (2-pack)/Light/config.json")))
        // A theme's own variant folders are part of it.
        assertEquals(listOf("Milk"), themeRoots(listOf("Milk/config.json", "Milk/v1/config.json", "Milk/v2/config.json", "Milk/skin/a.png")))
        assertEquals(emptyList<String>(), themeRoots(listOf("readme.txt", "Stuff/notes.txt")))
    }

    @Test fun packFoldersListTheirThemes() {
        val root = Files.createTempDirectory("themes").toFile()
        try {
            fun mk(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("{}") }
            mk("Plain/config.json")
            File(root, "SkinOnly/skin").mkdirs()
            mk("Pack (2-pack)/Light/config.json"); mk("Pack (2-pack)/Dark/config.json")
            File(root, "Pack (2-pack)/notes").mkdirs()
            mk("Milk/config.json"); mk("Milk/v1/config.json")
            File(root, "Empty").mkdirs()
            assertEquals(listOf(File(root, "Plain")), themeFolders(File(root, "Plain")))
            assertEquals(listOf(File(root, "SkinOnly")), themeFolders(File(root, "SkinOnly")))
            assertEquals(listOf(File(root, "Pack (2-pack)/Dark"), File(root, "Pack (2-pack)/Light")), themeFolders(File(root, "Pack (2-pack)")))
            assertEquals(listOf(File(root, "Milk")), themeFolders(File(root, "Milk")))
            assertTrue(themeFolders(File(root, "Empty")).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun featuredOnlyCatalogEntriesAreListed() {
        val text = listOf("C|Blueprint by Aemiii91", "C|#comment", "C|", "R|Remix by X", "F|Silky by DiMo", "F|blueprint by aemiii91").joinToString("\n")
        val e = ThemeStore.parseCatalog(text)
        assertEquals(listOf("Blueprint by Aemiii91", "Silky by DiMo", "Remix by X"), e.map { it.name })
        assertEquals(listOf(true, true, false), e.map { it.featured })
        assertEquals(listOf(false, false, true), e.map { it.remixed })
    }
}
