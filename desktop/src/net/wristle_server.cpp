#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include "wristle_server.h"
#include "../crypto/chacha20poly1305.h"
#include "../crypto/sha256_hmac.h"
#include "../input/text_injector.h"

#include <iostream>
#include <fstream>
#include <sstream>
#include <cstring>
#include <thread>
#include <mutex>
#include <map>
#include <filesystem>
#include <random>

#pragma comment(lib, "ws2_32.lib")

namespace fs = std::filesystem;

// @keep(pacodelavega): Core Wristle network server coordinating ChaCha20-Poly1305 decryption and direct cursor text injection

namespace wristle {
namespace net {

namespace {

constexpr char F_HELLO   = 'H';
constexpr char F_AUTH    = 'U';
constexpr char F_START   = 'S';
constexpr char F_END     = 'E';
constexpr char F_CANCEL  = 'C';
constexpr char F_PING    = 'P';
constexpr char F_PONG    = 'K';
constexpr char F_STATUS  = 'O';
constexpr char F_QUIT    = 'Q';
constexpr char F_TEXT    = 'T';
constexpr char F_PARTIAL = 't';

fs::path GetConfigPath() {
    wchar_t* appData = nullptr;
    size_t len = 0;
    if (_wdupenv_s(&appData, &len, L"APPDATA") == 0 && appData != nullptr) {
        fs::path p = fs::path(appData) / L"wristle" / L"paired_watches.json";
        free(appData);
        return p;
    }
    return fs::path("paired_watches.json");
}

std::string GetHostNameString() {
    char hostname[256] = { 0 };
    if (gethostname(hostname, sizeof(hostname)) == 0) {
        return std::string(hostname);
    }
    return "WristlePC";
}

bool RecvExact(SOCKET s, char* buf, int len) {
    int total = 0;
    while (total < len) {
        int r = recv(s, buf + total, len - total, 0);
        if (r <= 0) return false;
        total += r;
    }
    return true;
}

std::vector<uint8_t> HexToBytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i + 1 < hex.length(); i += 2) {
        std::string byteStr = hex.substr(i, 2);
        uint8_t b = static_cast<uint8_t>(std::strtoul(byteStr.c_str(), nullptr, 16));
        bytes.push_back(b);
    }
    return bytes;
}

} // anonymous namespace

struct WatchEntry {
    std::string name;
    std::string key_hex;
    std::string paired_at;
};

class PairedStore {
public:
    PairedStore() { Load(); }

    std::string GetKey(const std::string& id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = entries_.find(id);
        if (it != entries_.end()) return it->second.key_hex;
        return "";
    }

    void Add(const std::string& id, const std::string& name, const std::string& key_hex) {
        std::lock_guard<std::mutex> lock(mutex_);
        entries_[id] = { name, key_hex, "now" };
        Save();
    }

private:
    std::mutex mutex_;
    std::map<std::string, WatchEntry> entries_;

    void Load() {
        fs::path p = GetConfigPath();
        if (!fs::exists(p)) return;
        std::ifstream in(p);
        if (!in.is_open()) return;

        std::string line;
        std::string currentId, currentKey, currentName;
        while (std::getline(in, line)) {
            if (line.find("\"key\":") != std::string::npos) {
                size_t q1 = line.find('"', line.find("\"key\":") + 6);
                size_t q2 = line.find('"', q1 + 1);
                if (q1 != std::string::npos && q2 != std::string::npos) {
                    currentKey = line.substr(q1 + 1, q2 - q1 - 1);
                }
            } else if (line.find("\"name\":") != std::string::npos) {
                size_t q1 = line.find('"', line.find("\"name\":") + 7);
                size_t q2 = line.find('"', q1 + 1);
                if (q1 != std::string::npos && q2 != std::string::npos) {
                    currentName = line.substr(q1 + 1, q2 - q1 - 1);
                }
            } else if (line.find("\": {") != std::string::npos) {
                size_t q1 = line.find('"');
                size_t q2 = line.find('"', q1 + 1);
                if (q1 != std::string::npos && q2 != std::string::npos) {
                    if (!currentId.empty() && !currentKey.empty()) {
                        entries_[currentId] = { currentName, currentKey, "" };
                    }
                    currentId = line.substr(q1 + 1, q2 - q1 - 1);
                    currentKey.clear();
                    currentName.clear();
                }
            }
        }
        if (!currentId.empty() && !currentKey.empty()) {
            entries_[currentId] = { currentName, currentKey, "" };
        }
    }

    void Save() {
        fs::path p = GetConfigPath();
        fs::create_directories(p.parent_path());
        std::ofstream out(p);
        if (!out.is_open()) return;

        out << "{\n";
        bool first = true;
        for (const auto& [id, entry] : entries_) {
            if (!first) out << ",\n";
            first = false;
            out << "  \"" << id << "\": {\n";
            out << "    \"name\": \"" << entry.name << "\",\n";
            out << "    \"key\": \"" << entry.key_hex << "\"\n";
            out << "  }";
        }
        out << "\n}\n";
    }
};

struct WristleServer::Impl {
    ServerConfig config;
    std::atomic<bool> running{ false };
    std::string pc_name;

    SOCKET tcp_sock = INVALID_SOCKET;
    SOCKET udp_sock = INVALID_SOCKET;

    std::thread tcp_thread;
    std::thread udp_thread;

    PairedStore paired_store;
    std::function<void(const std::string&, const std::string&, std::function<void(bool)>)> pairing_cb;

    Impl(const ServerConfig& cfg) : config(cfg) {
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa);
        pc_name = GetHostNameString();
    }

    ~Impl() {
        Stop();
        WSACleanup();
    }

    bool Start() {
        if (running) return true;

        // Setup TCP listener
        tcp_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (tcp_sock == INVALID_SOCKET) return false;

        BOOL opt = TRUE;
        setsockopt(tcp_sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&opt), sizeof(opt));

        sockaddr_in tcp_addr{};
        tcp_addr.sin_family = AF_INET;
        tcp_addr.sin_port = htons(static_cast<u_short>(config.tcp_port));
        tcp_addr.sin_addr.s_addr = INADDR_ANY;

        if (bind(tcp_sock, reinterpret_cast<sockaddr*>(&tcp_addr), sizeof(tcp_addr)) != 0) {
            closesocket(tcp_sock);
            return false;
        }

        if (listen(tcp_sock, 8) != 0) {
            closesocket(tcp_sock);
            return false;
        }

        // Setup UDP broadcast responder
        udp_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (udp_sock != INVALID_SOCKET) {
            setsockopt(udp_sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&opt), sizeof(opt));

            sockaddr_in udp_addr{};
            udp_addr.sin_family = AF_INET;
            udp_addr.sin_port = htons(static_cast<u_short>(config.udp_port));
            udp_addr.sin_addr.s_addr = INADDR_ANY;

            bind(udp_sock, reinterpret_cast<sockaddr*>(&udp_addr), sizeof(udp_addr));
        }

        running = true;
        std::cout << "[Server] Wristle++ text server listening on TCP " << config.tcp_port
                  << " / UDP " << config.udp_port << std::endl;

        tcp_thread = std::thread([this]() { AcceptLoop(); });
        udp_thread = std::thread([this]() { UdpLoop(); });

        return true;
    }

    void Stop() {
        if (!running) return;
        running = false;

        if (tcp_sock != INVALID_SOCKET) {
            closesocket(tcp_sock);
            tcp_sock = INVALID_SOCKET;
        }
        if (udp_sock != INVALID_SOCKET) {
            closesocket(udp_sock);
            udp_sock = INVALID_SOCKET;
        }

        if (tcp_thread.joinable()) tcp_thread.join();
        if (udp_thread.joinable()) udp_thread.join();
    }

    void UdpLoop() {
        char buf[512];
        while (running && udp_sock != INVALID_SOCKET) {
            sockaddr_in clientAddr{};
            int clientLen = sizeof(clientAddr);
            int n = recvfrom(udp_sock, buf, sizeof(buf) - 1, 0, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);
            if (n <= 0) break;

            buf[n] = '\0';
            std::string msg(buf);
            if (msg.find("WRISTLE?") != std::string::npos) {
                std::string reply = "WRISTLE! " + std::to_string(config.tcp_port) + " " + pc_name;
                sendto(udp_sock, reply.c_str(), static_cast<int>(reply.length()), 0,
                       reinterpret_cast<sockaddr*>(&clientAddr), clientLen);
            }
        }
    }

    void AcceptLoop() {
        while (running && tcp_sock != INVALID_SOCKET) {
            sockaddr_in clientAddr{};
            int clientLen = sizeof(clientAddr);
            SOCKET clientSock = accept(tcp_sock, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);
            if (clientSock == INVALID_SOCKET) break;

            char ipStr[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &clientAddr.sin_addr, ipStr, sizeof(ipStr));

            std::thread([this, clientSock, ip = std::string(ipStr)]() {
                HandleClient(clientSock, ip);
            }).detach();
        }
    }

    void SendFrame(SOCKET s, char type, const std::string& payload) {
        std::string packet;
        packet.reserve(5 + payload.size());
        packet.push_back(type);
        uint32_t len = htonl(static_cast<uint32_t>(payload.size()));
        packet.append(reinterpret_cast<const char*>(&len), 4);
        if (!payload.empty()) {
            packet.append(payload);
        }
        send(s, packet.data(), static_cast<int>(packet.size()), 0);
    }

    void SendStatus(SOCKET s, const std::string& state, const std::string& extra_json = "") {
        std::string payload = "{\"state\":\"" + state + "\",\"host\":\"" + pc_name + "\"";
        if (!extra_json.empty()) {
            payload += "," + extra_json;
        }
        payload += "}";
        SendFrame(s, F_STATUS, payload);
    }

    void HandleClient(SOCKET s, const std::string& clientIp) {
        BOOL nodelay = TRUE;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));

        std::string deviceId;
        std::string deviceName = "watch";
        std::string sharedKeyHex;
        std::vector<uint8_t> keyBytes;
        std::string currentNonce;

        bool approved = false;

        while (running) {
            char header[5];
            if (!RecvExact(s, header, 5)) break;

            char type = header[0];
            uint32_t netLen = 0;
            std::memcpy(&netLen, header + 1, 4);
            uint32_t length = ntohl(netLen);

            if (length > (approved ? 65536 : 4096)) break;

            std::vector<uint8_t> payload(length);
            if (length > 0 && !RecvExact(s, reinterpret_cast<char*>(payload.data()), length)) {
                break;
            }

            if (type == F_QUIT && clientIp == "127.0.0.1") {
                std::cout << "[Server] Stop requested by localhost." << std::endl;
                Stop();
                break;
            }

            if (type == F_HELLO) {
                std::string helloStr(payload.begin(), payload.end());
                size_t idPos = helloStr.find("\"id\":\"");
                if (idPos != std::string::npos) {
                    size_t endPos = helloStr.find('"', idPos + 6);
                    deviceId = helloStr.substr(idPos + 6, endPos - (idPos + 6));
                }
                size_t namePos = helloStr.find("\"name\":\"");
                if (namePos != std::string::npos) {
                    size_t endPos = helloStr.find('"', namePos + 8);
                    deviceName = helloStr.substr(namePos + 8, endPos - (namePos + 8));
                }

                sharedKeyHex = paired_store.GetKey(deviceId);
                if (!sharedKeyHex.empty()) {
                    keyBytes = HexToBytes(sharedKeyHex);
                    currentNonce = crypto::GenerateRandomHex(16);
                    SendStatus(s, "auth", "\"nonce\":\"" + currentNonce + "\"");
                } else {
                    // New watch wants to pair
                    std::random_device rd;
                    int codeNum = 1000 + (rd() % 9000);
                    std::string code = std::to_string(codeNum);
                    SendStatus(s, "pending", "\"code\":\"" + code + "\"");

                    if (config.auto_pair) {
                        std::string newKey = crypto::GenerateRandomHex(32);
                        paired_store.Add(deviceId, deviceName, newKey);
                        keyBytes = HexToBytes(newKey);
                        approved = true;
                        SendStatus(s, "ok", "\"key\":\"" + newKey + "\"");
                        std::cout << "[Server] Automatically paired: " << deviceName << std::endl;
                    } else if (pairing_cb) {
                        pairing_cb(deviceName, code, [this, s, deviceId, deviceName, &keyBytes, &approved](bool allow) {
                            if (allow) {
                                std::string newKey = crypto::GenerateRandomHex(32);
                                paired_store.Add(deviceId, deviceName, newKey);
                                keyBytes = HexToBytes(newKey);
                                approved = true;
                                SendStatus(s, "ok", "\"key\":\"" + newKey + "\"");
                                std::cout << "[Server] Paired: " << deviceName << std::endl;
                            } else {
                                SendStatus(s, "denied");
                            }
                        });
                    } else {
                        std::cout << "\n  Pairing request from " << deviceName << " (" << clientIp << ")\n"
                                  << "  Code: " << code << " - Approve? (y/n): " << std::flush;
                        char ans = 'n';
                        std::cin >> ans;
                        if (ans == 'y' || ans == 'Y') {
                            std::string newKey = crypto::GenerateRandomHex(32);
                            paired_store.Add(deviceId, deviceName, newKey);
                            keyBytes = HexToBytes(newKey);
                            approved = true;
                            SendStatus(s, "ok", "\"key\":\"" + newKey + "\"");
                            std::cout << "[Server] Paired successfully: " << deviceName << std::endl;
                        } else {
                            SendStatus(s, "denied");
                        }
                    }
                }
            } else if (type == F_AUTH) {
                std::string proof(payload.begin(), payload.end());
                if (!currentNonce.empty() && !sharedKeyHex.empty() &&
                    crypto::VerifyAuthProof(sharedKeyHex, currentNonce, deviceId, proof)) {
                    approved = true;
                    SendStatus(s, "ok");
                    std::cout << "[Server] Watch authenticated: " << deviceName << " (" << clientIp << ")" << std::endl;
                } else {
                    std::cout << "[Server] Watch authentication failed: " << deviceName << std::endl;
                    SendStatus(s, "denied");
                }
            } else if (type == F_PING) {
                SendFrame(s, F_PONG, "");
            } else if (!approved) {
                continue;
            } else if (type == F_START) {
                std::cout << "[Server] Dictation started on watch..." << std::endl;
            } else if (type == F_END) {
                SendStatus(s, "done");
            } else if (type == F_CANCEL) {
                std::cout << "[Server] Dictation cancelled on watch." << std::endl;
            } else if (type == F_TEXT || type == F_PARTIAL) {
                // Instant On-Device Recognized Text from Watch!
                std::string receivedText;
                if (keyBytes.size() == 32 && payload.size() >= crypto::NONCE_SIZE + crypto::TAG_SIZE) {
                    const uint8_t* nonce = payload.data();
                    size_t ct_len = payload.size() - crypto::NONCE_SIZE - crypto::TAG_SIZE;
                    const uint8_t* ct = payload.data() + crypto::NONCE_SIZE;
                    const uint8_t* tag = payload.data() + crypto::NONCE_SIZE + ct_len;

                    std::vector<uint8_t> decrypted(ct_len);
                    if (crypto::ChaCha20Poly1305_Decrypt(keyBytes.data(), nonce, nullptr, 0,
                                                         ct, ct_len, tag, decrypted.data())) {
                        receivedText = std::string(decrypted.begin(), decrypted.end());
                    } else {
                        receivedText = std::string(reinterpret_cast<const char*>(payload.data()), payload.size());
                    }
                } else {
                    receivedText = std::string(reinterpret_cast<const char*>(payload.data()), payload.size());
                }

                if (!receivedText.empty()) {
                    std::cout << "[Server] Instant " << (type == F_TEXT ? "Final" : "Live")
                              << " Text from Watch: \"" << receivedText << "\"" << std::endl;
                    input::TextInjector::Inject(receivedText);
                }
                if (type == F_TEXT) {
                    SendStatus(s, "done");
                }
            }
        }

        closesocket(s);
    }
};

WristleServer::WristleServer(const ServerConfig& config)
    : pimpl_(std::make_unique<Impl>(config)) {}

WristleServer::~WristleServer() = default;

bool WristleServer::Start() {
    return pimpl_->Start();
}

void WristleServer::Stop() {
    pimpl_->Stop();
}

bool WristleServer::IsRunning() const {
    return pimpl_->running;
}

void WristleServer::SetPairingApprovalCallback(std::function<void(const std::string&, const std::string&, std::function<void(bool)>)> cb) {
    pimpl_->pairing_cb = cb;
}

} // namespace net
} // namespace wristle
