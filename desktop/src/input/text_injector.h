#pragma once

#include <string>

// @keep(pacodelavega): Windows text injector via SendInput and SetClipboardData + Ctrl+V with clipboard preservation

namespace wristle {
namespace input {

enum class InjectionMethod {
    Auto,      // Uses batch SendInput without disturbing clipboard (fallback to Clipboard if needed)
    SendInput, // Unicode key events via batch SendInput
    Clipboard  // SetClipboardData + Ctrl+V with automatic restore
};

class TextInjector {
public:
    /**
     * Injects UTF-8 string into current focused window at cursor.
     */
    static bool Inject(const std::string& utf8_text, InjectionMethod method = InjectionMethod::Auto);

    /**
     * Injects UTF-16 wide string into current focused window at cursor.
     */
    static bool Inject(const std::wstring& wide_text, InjectionMethod method = InjectionMethod::Auto);

private:
    static bool InjectViaSendInput(const std::wstring& text);
    static bool InjectViaClipboard(const std::wstring& text);
};

} // namespace input
} // namespace wristle
