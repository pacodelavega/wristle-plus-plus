#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "text_injector.h"
#include <vector>
#include <cstring>
#include <thread>
#include <chrono>
#include <algorithm>
#include <iostream>

#pragma comment(lib, "user32.lib")

// @keep(pacodelavega): Text injection implementation with desktop attachment and zero clipboard data loss

namespace wristle {
namespace input {

namespace {

bool AttachToInputDesktop() {
    HDESK hInput = OpenInputDesktop(0, FALSE, GENERIC_ALL);
    if (!hInput) {
        hInput = OpenInputDesktop(0, FALSE, DESKTOP_CREATEMENU | DESKTOP_CREATEWINDOW |
                                            DESKTOP_ENUMERATE | DESKTOP_HOOKCONTROL |
                                            DESKTOP_JOURNALPLAYBACK | DESKTOP_JOURNALRECORD |
                                            DESKTOP_READOBJECTS | DESKTOP_SWITCHDESKTOP |
                                            DESKTOP_WRITEOBJECTS);
    }
    if (hInput) {
        SetThreadDesktop(hInput);
        CloseDesktop(hInput);
        return true;
    }
    return false;
}

std::wstring Utf8ToWide(const std::string& utf8) {
    if (utf8.empty()) return L"";
    int sizeNeeded = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
    if (sizeNeeded <= 0) return L"";
    std::wstring result(sizeNeeded, 0);
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), &result[0], sizeNeeded);
    return result;
}

} // anonymous namespace

bool TextInjector::Inject(const std::string& utf8_text, InjectionMethod method) {
    return Inject(Utf8ToWide(utf8_text), method);
}

bool TextInjector::Inject(const std::wstring& wide_text, InjectionMethod method) {
    if (wide_text.empty()) return true;

    AttachToInputDesktop();

    HWND fg = GetForegroundWindow();
    char title[256] = {0};
    if (fg) {
        GetWindowTextA(fg, title, sizeof(title) - 1);
    }
    std::cout << "[TextInjector] Foreground window: " << fg << " ('" << title << "')" << std::endl;

    if (method == InjectionMethod::Auto) {
        // Clipboard + Ctrl+V is the gold standard across all Windows applications
        // (browsers, Electron apps, editors, terminals) with automatic clipboard restoration.
        if (InjectViaClipboard(wide_text)) {
            return true;
        }
        return InjectViaSendInput(wide_text);
    } else if (method == InjectionMethod::Clipboard) {
        return InjectViaClipboard(wide_text);
    } else {
        return InjectViaSendInput(wide_text);
    }
}

bool TextInjector::InjectViaSendInput(const std::wstring& text) {
    if (text.empty()) return true;

    AttachToInputDesktop();

    // Send in batches of up to 256 characters (512 INPUT events)
    constexpr size_t BATCH_CHAR_COUNT = 256;

    for (size_t offset = 0; offset < text.length(); ) {
        size_t count = std::min(BATCH_CHAR_COUNT, text.length() - offset);

        // Do not split a UTF-16 surrogate pair across batches
        if (offset + count < text.length()) {
            wchar_t lastChar = text[offset + count - 1];
            if (lastChar >= 0xD800 && lastChar <= 0xDBFF) {
                if (count > 1) {
                    count--;
                } else {
                    count = 2;
                }
            }
        }

        std::vector<INPUT> inputs;
        inputs.reserve(count * 2);

        for (size_t i = 0; i < count; ++i) {
            wchar_t ch = text[offset + i];

            // Normalize CRLF to avoid duplicate newlines in text fields
            if (ch == L'\r') {
                if (offset + i + 1 < text.length() && text[offset + i + 1] == L'\n') {
                    continue; // Skip \r, handle on \n
                }
                ch = L'\n';
            }

            if (ch == L'\n') {
                INPUT inDown{};
                inDown.type = INPUT_KEYBOARD;
                inDown.ki.wVk = VK_RETURN;
                inDown.ki.dwFlags = 0;
                inputs.push_back(inDown);

                INPUT inUp{};
                inUp.type = INPUT_KEYBOARD;
                inUp.ki.wVk = VK_RETURN;
                inUp.ki.dwFlags = KEYEVENTF_KEYUP;
                inputs.push_back(inUp);
            } else {
                INPUT inDown{};
                inDown.type = INPUT_KEYBOARD;
                inDown.ki.wScan = ch;
                inDown.ki.dwFlags = KEYEVENTF_UNICODE;
                inputs.push_back(inDown);

                INPUT inUp{};
                inUp.type = INPUT_KEYBOARD;
                inUp.ki.wScan = ch;
                inUp.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
                inputs.push_back(inUp);
            }
        }

        if (!inputs.empty()) {
            UINT sent = SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
            if (sent != inputs.size()) {
                std::cerr << "[TextInjector] SendInput failed, sent=" << sent << " of " << inputs.size()
                          << ", error=" << GetLastError() << std::endl;
                return false;
            }
        }

        offset += count;

        if (offset < text.length()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }

    std::cout << "[TextInjector] Injected via SendInput successfully." << std::endl;
    return true;
}

bool TextInjector::InjectViaClipboard(const std::wstring& text) {
    AttachToInputDesktop();

    // 1. Backup existing clipboard content
    std::wstring previousClipboard;
    bool hadPreviousText = false;

    if (OpenClipboard(nullptr)) {
        HANDLE hData = GetClipboardData(CF_UNICODETEXT);
        if (hData != nullptr) {
            const wchar_t* pText = static_cast<const wchar_t*>(GlobalLock(hData));
            if (pText != nullptr) {
                previousClipboard = pText;
                hadPreviousText = true;
                GlobalUnlock(hData);
            }
        }
        CloseClipboard();
    }

    // 2. Set new text to clipboard
    bool setSuccess = false;
    for (int retry = 0; retry < 5; ++retry) {
        if (OpenClipboard(nullptr)) {
            EmptyClipboard();
            size_t bytes = (text.length() + 1) * sizeof(wchar_t);
            HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, bytes);
            if (hMem != nullptr) {
                void* pDest = GlobalLock(hMem);
                if (pDest != nullptr) {
                    std::memcpy(pDest, text.c_str(), bytes);
                    GlobalUnlock(hMem);
                    if (SetClipboardData(CF_UNICODETEXT, hMem) != nullptr) {
                        setSuccess = true;
                    }
                }
            }
            CloseClipboard();
            if (setSuccess) break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    if (!setSuccess) {
        std::cerr << "[TextInjector] Failed to set clipboard, falling back to SendInput" << std::endl;
        return InjectViaSendInput(text);
    }

    // 3. Simulate Ctrl+V to paste with distinct key intervals
    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    INPUT ctrlDown{};
    ctrlDown.type = INPUT_KEYBOARD;
    ctrlDown.ki.wVk = VK_CONTROL;
    SendInput(1, &ctrlDown, sizeof(INPUT));

    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    INPUT vDown{};
    vDown.type = INPUT_KEYBOARD;
    vDown.ki.wVk = 'V';
    SendInput(1, &vDown, sizeof(INPUT));

    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    INPUT vUp{};
    vUp.type = INPUT_KEYBOARD;
    vUp.ki.wVk = 'V';
    vUp.ki.dwFlags = KEYEVENTF_KEYUP;
    SendInput(1, &vUp, sizeof(INPUT));

    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    INPUT ctrlUp{};
    ctrlUp.type = INPUT_KEYBOARD;
    ctrlUp.ki.wVk = VK_CONTROL;
    ctrlUp.ki.dwFlags = KEYEVENTF_KEYUP;
    SendInput(1, &ctrlUp, sizeof(INPUT));

    std::cout << "[TextInjector] Injected via Clipboard Ctrl+V successfully." << std::endl;

    // 4. Restore original clipboard asynchronously after 800ms
    if (hadPreviousText) {
        std::thread([previousClipboard]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(800));
            AttachToInputDesktop();
            for (int retry = 0; retry < 5; ++retry) {
                if (OpenClipboard(nullptr)) {
                    EmptyClipboard();
                    size_t bytes = (previousClipboard.length() + 1) * sizeof(wchar_t);
                    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, bytes);
                    if (hMem != nullptr) {
                        void* pDest = GlobalLock(hMem);
                        if (pDest != nullptr) {
                            std::memcpy(pDest, previousClipboard.c_str(), bytes);
                            GlobalUnlock(hMem);
                            SetClipboardData(CF_UNICODETEXT, hMem);
                        }
                    }
                    CloseClipboard();
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
        }).detach();
    }

    return true;
}

} // namespace input
} // namespace wristle
