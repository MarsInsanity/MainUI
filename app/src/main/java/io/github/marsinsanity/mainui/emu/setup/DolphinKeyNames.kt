package io.github.marsinsanity.mainui.emu.setup

/**
 * Dolphin's names for Android key codes (Source/Core/InputCommon/ControllerInterface/Android/
 * Android.cpp, KEYCODE_NAMES): index = key code. Its INI files name buttons this way ("Button A").
 */
internal object DolphinKeyNames {
    private val NAMES = arrayOf(
        "Unknown", "Soft Left", "Soft Right", "Home", "Back", "Call", "End Call", "0",
        "1", "2", "3", "4", "5", "6", "7", "8",
        "9", "Star", "Pound", "Up", "Down", "Left", "Right", "Center",
        "Volume Up", "Volume Down", "Power", "Camera", "Clear", "A", "B", "C",
        "D", "E", "F", "G", "H", "I", "J", "K",
        "L", "M", "N", "O", "P", "Q", "R", "S",
        "T", "U", "V", "W", "X", "Y", "Z", "Comma",
        "Period", "Left Alt", "Right Alt", "Left Shift", "Right Shift", "Tab", "Space", "Sym",
        "Explorer", "Envelope", "Enter", "Backspace", "Grave", "Minus", "Equals", "Left Bracket",
        "Right Bracket", "Backslash", "Semicolon", "Apostrophe", "Slash", "At", "Num", "Headset Hook",
        "Focus", "Plus", "Menu", "Notification", "Search", "Play Pause", "Stop", "Next",
        "Previous", "Rewind", "Fast Forward", "Mute", "Page Up", "Page Down", "Emoji", "Switch Charset",
        "Button A", "Button B", "Button C", "Button X", "Button Y", "Button Z", "Button L1", "Button R1",
        "Button L2", "Button R2", "Button L3", "Button R3", "Start", "Select", "Mode", "Escape",
        "Delete", "Left Ctrl", "Right Ctrl", "Caps Lock", "Scroll Lock", "Left Meta", "Right Meta", "Fn",
        "PrtSc SysRq", "Pause Break", "Move Home", "Move End", "Insert", "Forward", "Play", "Pause",
        "Close", "Eject", "Record", "F1", "F2", "F3", "F4", "F5",
        "F6", "F7", "F8", "F9", "F10", "F11", "F12", "Num Lock",
        "Numpad 0", "Numpad 1", "Numpad 2", "Numpad 3", "Numpad 4", "Numpad 5", "Numpad 6", "Numpad 7",
        "Numpad 8", "Numpad 9", "Numpad Divide", "Numpad Multiply", "Numpad Subtract", "Numpad Add", "Numpad Dot", "Numpad Comma",
        "Numpad Enter", "Numpad Equals", "Numpad Left Paren", "Numpad Right Paren", "Volume Mute", "Info", "Channel Up", "Channel Down",
        "Zoom In", "Zoom Out", "TV", "Window", "Guide", "DVR", "Bookmark", "Captions",
        "Settings", "TV Power", "TV Input", "STB Power", "STB Input", "AVR Power", "AVR Input", "Prog Red",
        "Prog Green", "Prog Yellow", "Prog Blue", "App Switch", "Button 1", "Button 2", "Button 3", "Button 4",
        "Button 5", "Button 6", "Button 7", "Button 8", "Button 9", "Button 10", "Button 11", "Button 12",
        "Button 13", "Button 14", "Button 15", "Button 16", "Language Switch", "Manner Mode", "3D Mode", "Contacts",
        "Calendar", "Music", "Calculator", "Zenkaku Hankaku", "Eisu", "Henkan", "Muhenkan", "Katakana Hiragana",
        "Yen", "Ro", "Kana", "Assist", "Brightness Down", "Brightness Up", "Audio Track", "Sleep",
        "Wakeup", "Pairing", "Top Menu", "11", "12", "Last Channel", "Data Service", "Voice Assist",
        "Radio Service", "Teletext", "Number Entry", "Terrestrial Analog", "Terrestrial Digital", "Satellite", "Satellite BS", "Satellite CS",
        "Satellite Service", "Network", "Antenna Cable", "Input HDMI 1", "Input HDMI 2", "Input HDMI 3", "Input HDMI 4", "Input Composite 1",
        "Input Composite 2", "Input Component 1", "Input Component 2", "Input VGA 1", "Audio Description", "Audio Description Mix Up", "Audio Description Mix Down", "Zoom Mode",
        "Contents Menu", "Media Context Menu", "Timer Programming", "Help", "Navigate Previous", "Navigate Next", "Navigate In", "Navigate Out",
        "Stem Primary", "Stem 1", "Stem 2", "Stem 3", "Up Left", "Down Left", "Up Right", "Down Right",
        "Skip Forward", "Skip Backward", "Step Forward", "Step Backward", "Soft Sleep", "Cut", "Copy", "Paste",
        "System Navigation Up", "System Navigation Down", "System Navigation Left", "System Navigation Right", "All Apps", "Refresh", "Thumbs Up", "Thumbs Down",
        "Profile Switch",
    )

    /** Dolphin's name for [keyCode]; null for one it has no name for. */
    fun of(keyCode: Int): String? = NAMES.getOrNull(keyCode)?.takeIf { it.isNotEmpty() }
}
