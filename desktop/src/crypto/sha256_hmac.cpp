#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>
#include "sha256_hmac.h"
#include <sstream>
#include <iomanip>
#include <random>

#pragma comment(lib, "bcrypt.lib")

// @keep(pacodelavega): Windows CNG BCrypt based HMAC-SHA256 and secure RNG

namespace wristle {
namespace crypto {

namespace {

std::vector<uint8_t> HexToBytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i + 1 < hex.length(); i += 2) {
        std::string byteStr = hex.substr(i, 2);
        uint8_t b = static_cast<uint8_t>(std::strtoul(byteStr.c_str(), nullptr, 16));
        bytes.push_back(b);
    }
    return bytes;
}

std::string BytesToHex(const uint8_t* data, size_t len) {
    std::ostringstream ss;
    for (size_t i = 0; i < len; ++i) {
        ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(data[i]);
    }
    return ss.str();
}

} // anonymous namespace

std::string ComputeHmacSha256Hex(const std::string& key_hex, const std::string& data) {
    std::vector<uint8_t> key = HexToBytes(key_hex);
    if (key.empty()) return "";

    BCRYPT_ALG_HANDLE hAlg = nullptr;
    NTSTATUS status = BCryptOpenAlgorithmProvider(
        &hAlg,
        BCRYPT_SHA256_ALGORITHM,
        nullptr,
        BCRYPT_ALG_HANDLE_HMAC_FLAG
    );
    if (!BCRYPT_SUCCESS(status)) return "";

    DWORD hashObjSize = 0, dataLen = 0;
    BCryptGetProperty(hAlg, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PBYTE>(&hashObjSize), sizeof(DWORD), &dataLen, 0);
    std::vector<uint8_t> hashObject(hashObjSize);

    BCRYPT_HASH_HANDLE hHash = nullptr;
    status = BCryptCreateHash(
        hAlg,
        &hHash,
        hashObject.data(),
        hashObjSize,
        key.data(),
        static_cast<ULONG>(key.size()),
        0
    );
    if (!BCRYPT_SUCCESS(status)) {
        BCryptCloseAlgorithmProvider(hAlg, 0);
        return "";
    }

    BCryptHashData(hHash, reinterpret_cast<PBYTE>(const_cast<char*>(data.data())), static_cast<ULONG>(data.size()), 0);

    uint8_t digest[32] = {0};
    BCryptFinishHash(hHash, digest, sizeof(digest), 0);

    BCryptDestroyHash(hHash);
    BCryptCloseAlgorithmProvider(hAlg, 0);

    return BytesToHex(digest, sizeof(digest));
}

bool VerifyAuthProof(
    const std::string& key_hex,
    const std::string& nonce,
    const std::string& device_id,
    const std::string& proof_hex
) {
    std::string expectedMsg = "wristle-auth|" + nonce + "|" + device_id;
    std::string expectedProof = ComputeHmacSha256Hex(key_hex, expectedMsg);

    if (expectedProof.empty() || proof_hex.length() != expectedProof.length()) {
        return false;
    }

    // Constant-time comparison
    int diff = 0;
    for (size_t i = 0; i < expectedProof.length(); ++i) {
        diff |= (expectedProof[i] ^ proof_hex[i]);
    }
    return diff == 0;
}

std::string GenerateRandomHex(size_t num_bytes) {
    std::vector<uint8_t> bytes(num_bytes);
    BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    return BytesToHex(bytes.data(), bytes.size());
}

} // namespace crypto
} // namespace wristle
