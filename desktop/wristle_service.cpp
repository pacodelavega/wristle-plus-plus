#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iostream>
#include <string>
#include <vector>
#include <chrono>
#include <thread>
#include <filesystem>
#include <memory>

#include "src/net/wristle_server.h"

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "user32.lib")

namespace fs = std::filesystem;

// @keep(pacodelavega): Unified Windows Service, Registry autostart, and native speech-recognition server manager

constexpr const wchar_t* SERVICE_NAME = L"WristlePlusPlusService";
constexpr const wchar_t* SERVICE_DISPLAY_NAME = L"Wristle++ Text Injection Service";
constexpr const wchar_t* SERVICE_DESC_TEXT = L"Secure text injection service for Wear OS watch dictation with ChaCha20-Poly1305 encryption.";
constexpr const wchar_t* REG_RUN_KEY = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr const wchar_t* REG_RUN_VALUE = L"WristlePlusPlus";
constexpr int DEFAULT_TCP_PORT = 41083;

SERVICE_STATUS g_serviceStatus{};
SERVICE_STATUS_HANDLE g_statusHandle = nullptr;
HANDLE g_serviceStopEvent = nullptr;
std::unique_ptr<wristle::net::WristleServer> g_serverInstance;

std::wstring GetExecutablePath() {
    wchar_t buffer[MAX_PATH];
    GetModuleFileNameW(nullptr, buffer, MAX_PATH);
    return std::wstring(buffer);
}

bool IsPortResponding(int port, int timeoutMs = 1000) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return false;

    u_long mode = 1;
    ioctlsocket(s, FIONBIO, &mode);

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<u_short>(port));
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));

    fd_set writeSet;
    FD_ZERO(&writeSet);
    FD_SET(s, &writeSet);

    timeval tv;
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;

    int res = select(0, nullptr, &writeSet, nullptr, &tv);
    closesocket(s);
    return res > 0;
}

bool SendTcpQuit(int port) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return false;

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<u_short>(port));
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    DWORD timeout = 2000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));

    if (connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        closesocket(s);
        return false;
    }

    unsigned char quitFrame[5] = { 'Q', 0, 0, 0, 0 };
    int sent = send(s, reinterpret_cast<const char*>(quitFrame), sizeof(quitFrame), 0);
    closesocket(s);
    return sent == sizeof(quitFrame);
}

// ---------------------------------------------------------------------------
// Windows Service Handler
// ---------------------------------------------------------------------------

VOID WINAPI ServiceCtrlHandler(DWORD request) {
    switch (request) {
        case SERVICE_CONTROL_STOP:
        case SERVICE_CONTROL_SHUTDOWN:
            g_serviceStatus.dwCurrentState = SERVICE_STOP_PENDING;
            SetServiceStatus(g_statusHandle, &g_serviceStatus);
            if (g_serverInstance) g_serverInstance->Stop();
            SetEvent(g_serviceStopEvent);
            break;
        default:
            break;
    }
}

VOID WINAPI ServiceMain(DWORD argc, LPWSTR* argv) {
    (void)argc; (void)argv;
    g_statusHandle = RegisterServiceCtrlHandlerW(SERVICE_NAME, ServiceCtrlHandler);
    if (!g_statusHandle) return;

    g_serviceStatus.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
    g_serviceStatus.dwCurrentState = SERVICE_START_PENDING;
    g_serviceStatus.dwControlsAccepted = SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN;
    SetServiceStatus(g_statusHandle, &g_serviceStatus);

    g_serviceStopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!g_serviceStopEvent) {
        g_serviceStatus.dwCurrentState = SERVICE_STOPPED;
        SetServiceStatus(g_statusHandle, &g_serviceStatus);
        return;
    }

    wristle::net::ServerConfig cfg;
    cfg.auto_pair = false;
    g_serverInstance = std::make_unique<wristle::net::WristleServer>(cfg);
    if (!g_serverInstance->Start()) {
        g_serviceStatus.dwCurrentState = SERVICE_STOPPED;
        SetServiceStatus(g_statusHandle, &g_serviceStatus);
        return;
    }

    g_serviceStatus.dwCurrentState = SERVICE_RUNNING;
    SetServiceStatus(g_statusHandle, &g_serviceStatus);

    WaitForSingleObject(g_serviceStopEvent, INFINITE);

    g_serviceStatus.dwCurrentState = SERVICE_STOPPED;
    SetServiceStatus(g_statusHandle, &g_serviceStatus);
    CloseHandle(g_serviceStopEvent);
}

// ---------------------------------------------------------------------------
// CLI Commands
// ---------------------------------------------------------------------------

int CmdRun() {
    std::wcout << L"Starting Wristle++ Text Injection Server (ChaCha20-Poly1305)..." << std::endl;
    wristle::net::ServerConfig cfg;
    cfg.auto_pair = false;

    wristle::net::WristleServer server(cfg);
    if (!server.Start()) {
        std::wcerr << L"Failed to start server on port " << cfg.tcp_port << std::endl;
        return 1;
    }

    std::wcout << L"Wristle++ server is running. Press Ctrl+C to stop." << std::endl;
    while (server.IsRunning()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    return 0;
}

int CmdStart() {
    if (IsPortResponding(DEFAULT_TCP_PORT)) {
        std::wcout << L"Wristle++ is already running on port " << DEFAULT_TCP_PORT << L"." << std::endl;
        return 0;
    }

    std::wstring exe = GetExecutablePath();
    std::wstring cmd = L"\"" + exe + L"\" run";

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    si.dwFlags |= STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;

    std::vector<wchar_t> cmdBuf(cmd.begin(), cmd.end());
    cmdBuf.push_back(L'\0');

    if (CreateProcessW(nullptr, cmdBuf.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | DETACHED_PROCESS, nullptr, nullptr, &si, &pi)) {
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
        std::wcout << L"Wristle started in background." << std::endl;
        return 0;
    }
    std::wcerr << L"Failed to launch background process." << std::endl;
    return 1;
}

int CmdStop() {
    if (!IsPortResponding(DEFAULT_TCP_PORT)) {
        std::wcout << L"Wristle is not running." << std::endl;
        return 0;
    }

    std::wcout << L"Sending quit command to Wristle listener..." << std::endl;
    if (SendTcpQuit(DEFAULT_TCP_PORT)) {
        for (int i = 0; i < 20; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            if (!IsPortResponding(DEFAULT_TCP_PORT)) {
                std::wcout << L"Wristle stopped successfully." << std::endl;
                return 0;
            }
        }
    }
    std::wcout << L"Service stopped." << std::endl;
    return 0;
}

int CmdStatus() {
    bool running = IsPortResponding(DEFAULT_TCP_PORT);
    std::wcout << L"Wristle Port " << DEFAULT_TCP_PORT << L": "
               << (running ? L"ACTIVE (Listening for Wear OS dictation)" : L"NOT RUNNING") << std::endl;
    return running ? 0 : 1;
}

int CmdInstallRegistry() {
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, REG_RUN_KEY, 0, KEY_SET_VALUE, &hKey) == ERROR_SUCCESS) {
        std::wstring exePath = L"\"" + GetExecutablePath() + L"\" start";
        RegSetValueExW(hKey, REG_RUN_VALUE, 0, REG_SZ,
                       reinterpret_cast<const BYTE*>(exePath.c_str()),
                       static_cast<DWORD>((exePath.length() + 1) * sizeof(wchar_t)));
        RegCloseKey(hKey);
        std::wcout << L"Wristle registered for login autostart in Windows Registry." << std::endl;
        CmdStart();
        return 0;
    }
    std::wcerr << L"Failed to open registry key." << std::endl;
    return 1;
}

int CmdUninstallRegistry() {
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, REG_RUN_KEY, 0, KEY_SET_VALUE, &hKey) == ERROR_SUCCESS) {
        RegDeleteValueW(hKey, REG_RUN_VALUE);
        RegCloseKey(hKey);
        std::wcout << L"Wristle login autostart removed from Windows Registry." << std::endl;
        CmdStop();
        return 0;
    }
    return 0;
}

int CmdInstallService() {
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_ALL_ACCESS);
    if (!scm) {
        std::wcerr << L"Failed to open Service Control Manager (Administrator rights required)." << std::endl;
        return 1;
    }

    std::wstring binPath = L"\"" + GetExecutablePath() + L"\"";
    SC_HANDLE svc = CreateServiceW(
        scm,
        SERVICE_NAME,
        SERVICE_DISPLAY_NAME,
        SERVICE_ALL_ACCESS,
        SERVICE_WIN32_OWN_PROCESS,
        SERVICE_AUTO_START,
        SERVICE_ERROR_NORMAL,
        binPath.c_str(),
        nullptr, nullptr, nullptr, nullptr, nullptr
    );

    if (!svc) {
        DWORD err = GetLastError();
        if (err == ERROR_SERVICE_EXISTS) {
            std::wcout << L"Service already exists." << std::endl;
        } else {
            std::wcerr << L"Failed to create service. Error: " << err << std::endl;
            CloseServiceHandle(scm);
            return 1;
        }
    } else {
        SERVICE_DESCRIPTIONW desc;
        desc.lpDescription = const_cast<LPWSTR>(SERVICE_DESC_TEXT);
        ChangeServiceConfig2W(svc, SERVICE_CONFIG_DESCRIPTION, &desc);
        CloseServiceHandle(svc);
        std::wcout << L"Windows Service installed successfully." << std::endl;
    }

    CloseServiceHandle(scm);
    return 0;
}

int CmdUninstallService() {
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_ALL_ACCESS);
    if (!scm) return 1;

    SC_HANDLE svc = OpenServiceW(scm, SERVICE_NAME, SERVICE_STOP | DELETE);
    if (svc) {
        SERVICE_STATUS st;
        ControlService(svc, SERVICE_CONTROL_STOP, &st);
        DeleteService(svc);
        CloseServiceHandle(svc);
        std::wcout << L"Windows Service uninstalled." << std::endl;
    }
    CloseServiceHandle(scm);
    return 0;
}

void PrintUsage() {
    std::wcout << L"Wristle++ - Native Windows Text Injection Service\n\n"
               << L"Usage: wristle_service.exe <command>\n\n"
               << L"Commands:\n"
               << L"  run               Run interactively in foreground console\n"
               << L"  start             Start background daemon\n"
               << L"  stop              Stop running service or daemon\n"
               << L"  restart           Restart service or daemon\n"
               << L"  status            Show status of the listener\n"
               << L"  install           Install user login autostart (Registry)\n"
               << L"  uninstall         Remove user login autostart\n"
               << L"  install-service   Install as system Windows Service (Admin)\n"
               << L"  uninstall-service Remove system Windows Service (Admin)\n";
}

int wmain(int argc, wchar_t* argv[]) {
    if (argc < 2) {
        // If started with no arguments by SCM
        SERVICE_TABLE_ENTRYW serviceTable[] = {
            { const_cast<LPWSTR>(SERVICE_NAME), ServiceMain },
            { nullptr, nullptr }
        };
        if (StartServiceCtrlDispatcherW(serviceTable)) {
            return 0;
        }
        PrintUsage();
        return 1;
    }

    std::wstring cmd = argv[1];
    if (cmd == L"run") return CmdRun();
    if (cmd == L"start") return CmdStart();
    if (cmd == L"stop") return CmdStop();
    if (cmd == L"restart") { CmdStop(); std::this_thread::sleep_for(std::chrono::milliseconds(500)); return CmdStart(); }
    if (cmd == L"status") return CmdStatus();
    if (cmd == L"install") return CmdInstallRegistry();
    if (cmd == L"uninstall") return CmdUninstallRegistry();
    if (cmd == L"install-service") return CmdInstallService();
    if (cmd == L"uninstall-service") return CmdUninstallService();

    PrintUsage();
    return 1;
}
