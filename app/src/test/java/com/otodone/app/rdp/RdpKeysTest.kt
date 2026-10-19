package com.otodone.app.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 快捷键发送链路的纯 JVM 回归测试（不需要 native 库）。
 *
 * 覆盖"按了没反应"那次事故的根因：方向键 / Home / End / PgUp / PgDn / Ins / Del / Win
 * 是 WinPR 扫描码表里的**扩展键**（KBD4X 表），发送前必须带上 [RdpKeys.KBDEXT]，
 * 否则 `GetVirtualScanCodeFromVirtualKeyCode` 返回 0，JNI 发出 scancode=0 的按键。
 */
class RdpKeysTest {

    @Test
    fun extendedKeysGetTheKbdextFlag() {
        val extended = listOf(
            RdpKeys.VK_LEFT, RdpKeys.VK_RIGHT, RdpKeys.VK_UP, RdpKeys.VK_DOWN,
            RdpKeys.VK_HOME, RdpKeys.VK_END, RdpKeys.VK_PRIOR, RdpKeys.VK_NEXT,
            RdpKeys.VK_INSERT, RdpKeys.VK_DELETE, RdpKeys.VK_LWIN, RdpKeys.VK_APPS,
        )
        for (key in extended) {
            assertEquals(
                "扩展键 0x" + Integer.toHexString(key) + " 必须带 KBDEXT",
                key or RdpKeys.KBDEXT,
                RdpKeys.withExtendedFlag(key),
            )
        }
    }

    @Test
    fun normalKeysKeepTheirVirtualKey() {
        // 修饰键在普通表里（KBD4T），带 KBDEXT 反而会查错表。
        val normal = listOf(
            RdpKeys.VK_LCONTROL, RdpKeys.VK_LSHIFT, RdpKeys.VK_LMENU,
            RdpKeys.VK_ESCAPE, RdpKeys.VK_TAB, RdpKeys.VK_BACK, RdpKeys.VK_RETURN,
            0x41, // A
            0x30, // 0
            RdpKeys.VK_F1, RdpKeys.VK_F1 + 11,
        )
        for (key in normal) {
            assertEquals(key, RdpKeys.withExtendedFlag(key))
        }
    }

    @Test
    fun functionKeysCoverF1ToF12() {
        assertEquals(0x70, RdpKeys.VK_F1)
        assertEquals(0x7B, RdpKeys.VK_F1 + 11)
        // 12 个功能键必须连续、且都不需要扩展位。
        for (index in 0 until 12) {
            val key = RdpKeys.VK_F1 + index
            assertEquals(key, RdpKeys.withExtendedFlag(key))
        }
    }

    @Test
    fun backspaceAndEnterUseTheStandardVirtualKeys() {
        assertEquals(0x08, RdpKeys.VK_BACK)
        assertEquals(0x0D, RdpKeys.VK_RETURN)
        assertNotEquals(RdpKeys.VK_BACK, RdpKeys.VK_RETURN)
    }

    @Test
    fun modifierKeysUseLeftRightSpecificCodes() {
        // 通用 VK_CONTROL(0x11)/VK_MENU(0x12) 不在 KBD4T 里，必须用左右具体码。
        assertNotEquals(RdpKeys.VK_CONTROL, RdpKeys.VK_LCONTROL)
        assertNotEquals(RdpKeys.VK_MENU, RdpKeys.VK_LMENU)
        assertEquals(0xA2, RdpKeys.VK_LCONTROL)
        assertEquals(0xA4, RdpKeys.VK_LMENU)
        assertEquals(0xA0, RdpKeys.VK_LSHIFT)
    }
}
