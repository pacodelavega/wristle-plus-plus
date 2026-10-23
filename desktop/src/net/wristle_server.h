#pragma once

#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <functional>

// @keep(pacodelavega): Lightweight WinSock2 network server with ChaCha20-Poly1305 decryption and direct cursor text injection

namespace wristle {
namespace net {

struct ServerConfig {
    int tcp_port = 41083;
    int udp_port = 47821;
    bool auto_pair = false;
};

class WristleServer {
public:
    WristleServer(const ServerConfig& config = ServerConfig());
    ~WristleServer();

    bool Start();
    void Stop();
    bool IsRunning() const;

    void SetPairingApprovalCallback(std::function<void(const std::string& name, const std::string& code, std::function<void(bool)> respond)> cb);

private:
    struct Impl;
    std::unique_ptr<Impl> pimpl_;
};

} // namespace net
} // namespace wristle
