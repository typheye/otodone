package com.otodone.app.rdp

import android.view.KeyEvent

/** RDP 指针标志与 Android -> Windows 虚拟键映射。 */
object RdpKeys {

    // 与 FreeRDP PTR_FLAGS_* 保持一致（见 libfreerdp input.h / android_freerdp.c）
    const val PTR_FLAGS_MOVE = 0x0800
    const val PTR_FLAGS_DOWN = 0x8000
    const val PTR_FLAGS_BUTTON1 = 0x1000
    const val PTR_FLAGS_BUTTON2 = 0x2000
    const val PTR_FLAGS_BUTTON3 = 0x4000
    const val PTR_FLAGS_WHEEL = 0x0200
    const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100
    const val PTR_FLAGS_HWHEEL = 0x0400

    /** 滚轮一档的增量（与 FreeRDP android_freerdp.c 一致）。 */
    const val WHEEL_DELTA_DOWN = 0x0078
    const val WHEEL_DELTA_UP = 0x0088

    /**
     * ASCII 字符转 Windows 虚拟键码。
     *
     * 远端（尤其 gnome-remote-desktop）对 unicode 注入通道的支持不如虚拟键可靠，
     * 因此 ASCII 一律优先走虚拟键，只有非 ASCII（中文等）才退回 unicode。
     */
    fun asciiToVirtualKey(codePoint: Int): Int? = when (codePoint) {
        in 0x61..0x7A -> codePoint - 0x20        // a-z -> A-Z
        in 0x41..0x5A -> codePoint               // A-Z
        in 0x30..0x39 -> codePoint               // 0-9
        0x20 -> 0x20                             // 空格
        0x0A, 0x0D -> VK_RETURN
        0x09 -> VK_TAB
        0x3B -> 0xBA                             // ;
        0x3D -> 0xBB                             // =
        0x2C -> 0xBC                             // ,
        0x2D -> 0xBD                             // -
        0x2E -> 0xBE                             // .
        0x2F -> 0xBF                             // /
        0x60 -> 0xC0                             // `
        0x5B -> 0xDB                             // [
        0x5C -> 0xDC                             // backslash
        0x5D -> 0xDD                             // ]
        0x27 -> 0xDE                             // '
        else -> null
    }

    // Windows 虚拟键码（freerdp_send_key_event 期望的值）
    const val VK_BACK = 0x08
    const val VK_TAB = 0x09
    const val VK_RETURN = 0x0D
    const val VK_SHIFT = 0x10
    const val VK_CONTROL = 0x11
    const val VK_LCONTROL = 0xA2
    const val VK_LSHIFT = 0xA0
    const val VK_MENU = 0x12
    /**
     * 左 Alt。通用 [VK_MENU] 与 [VK_CONTROL] / [VK_SHIFT] 一样在 WinPR 的扫描码表里查不到，
     * 发出去会被静默丢弃，组合键必须用这个左右具体码（见 RdpSession.sendPasteShortcut）。
     */
    const val VK_LMENU = 0xA4
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_PRIOR = 0x21 // PageUp
    const val VK_NEXT = 0x22 // PageDown
    const val VK_END = 0x23
    const val VK_HOME = 0x24
    const val VK_LEFT = 0x25
    const val VK_UP = 0x26
    const val VK_RIGHT = 0x27
    const val VK_DOWN = 0x28
    const val VK_INSERT = 0x2D
    const val VK_DELETE = 0x2E
    const val VK_C = 0x43
    const val VK_V = 0x56

    // OEM 符号键（数值与 asciiToVirtualKey 里的字面量一致，这里补成具名常量给全键盘用）。
    const val VK_OEM_1 = 0xBA      // ; :
    const val VK_OEM_PLUS = 0xBB   // = +
    const val VK_OEM_COMMA = 0xBC  // , <
    const val VK_OEM_MINUS = 0xBD  // - _
    const val VK_OEM_PERIOD = 0xBE // . >
    const val VK_OEM_2 = 0xBF      // / ?
    const val VK_OEM_3 = 0xC0      // 反引号 ~
    const val VK_OEM_4 = 0xDB      // [ {
    const val VK_OEM_5 = 0xDC      // 反斜杠 |
    const val VK_OEM_6 = 0xDD      // ] }
    const val VK_OEM_7 = 0xDE      // ' "
    const val VK_LWIN = 0x5B
    const val VK_APPS = 0x5D

    /** 功能键起点：F1 = 0x70，F12 = 0x7B（连续 12 个，见 ShortcutBar.FUNCTION_KEYS）。 */
    const val VK_F1 = 0x70

    /**
     * WinPR 的 KBDEXT 标志位（= winpr/input.h 的 `#define KBDEXT 0x0100u`）。
     *
     * 方向键 / Home / End / PgUp / PgDn / Insert / Delete / Win 这些**扩展键**在 WinPR 的
     * 扫描码表里位于 KBD4X（E0 前缀那张表）。查表函数
     * `GetVirtualScanCodesFromVirtualKeyCode`（winpr/libwinpr/input/scancode.c:591）用
     * `vkcode & KBDEXT` 决定查哪张表：不带这一位就只查 KBD4T，而扩展键**不在** KBD4T 里，
     * 于是查表返回 0，JNI 发出 scancode=0 的按键 —— 远端表现就是"按了完全没反应"。
     *
     * JNI 侧（android_freerdp.c:1043）用 `keycode & 0xFF` 取键码、用
     * `scancode & KBDEXT` 决定 KBD_FLAGS_EXTENDED，所以只需在 Kotlin 这一层把标志位带上去，
     * **不用改 native**。
     */
    const val KBDEXT = 0x0100

    /** 必须带 [KBDEXT] 才能查到扫描码的扩展键（KBD4X 表里的那些）。 */
    private val EXTENDED_VIRTUAL_KEYS = setOf(
        VK_LEFT, VK_RIGHT, VK_UP, VK_DOWN,
        VK_HOME, VK_END, VK_PRIOR, VK_NEXT,
        VK_INSERT, VK_DELETE,
        VK_LWIN, VK_APPS,
    )

    /** 扩展键补上 [KBDEXT]，普通键原样返回。发送按键前必须过一遍。 */
    fun withExtendedFlag(virtualKey: Int): Int =
        if (virtualKey in EXTENDED_VIRTUAL_KEYS) virtualKey or KBDEXT else virtualKey

    /** 返回 null 表示该按键应作为 unicode 字符发送。 */
    fun androidToVirtualKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> VK_BACK
        KeyEvent.KEYCODE_TAB -> VK_TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VK_RETURN
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> VK_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> VK_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> VK_MENU
        KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
        KeyEvent.KEYCODE_SPACE -> VK_SPACE
        KeyEvent.KEYCODE_PAGE_UP -> VK_PRIOR
        KeyEvent.KEYCODE_PAGE_DOWN -> VK_NEXT
        KeyEvent.KEYCODE_MOVE_END -> VK_END
        KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
        KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
        KeyEvent.KEYCODE_DPAD_UP -> VK_UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
        KeyEvent.KEYCODE_INSERT -> VK_INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> VK_DELETE
        KeyEvent.KEYCODE_META_LEFT -> VK_LWIN
        KeyEvent.KEYCODE_META_RIGHT -> VK_APPS
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> VK_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }
}
