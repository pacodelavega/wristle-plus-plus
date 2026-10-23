#pragma once

#include <string>
#include <vector>
#include <cstdint>

// @keep(pacodelavega): HMAC-SHA256 pairing proof verification for Wristle protocol

namespace wristle {
namespace crypto {

/**
 * Computes HMAC-SHA256(key, data) and returns a lowercase hex string.
 * key_hex: 64-char hex string (32 bytes).
 */
std::string ComputeHmacSha256Hex(const std::string& key_hex, const std::string& data);

/**
 * Verifies pairing proof: HMAC-SHA256(key, "wristle-auth|<nonce>|<device_id>").
 */
bool VerifyAuthProof(
    const std::string& key_hex,
    const std::string& nonce,
    const std::string& device_id,
    const std::string& proof_hex
);

/**
 * Generates a cryptographically secure random hex string of specified byte length.
 */
std::string GenerateRandomHex(size_t num_bytes);

} // namespace crypto
} // namespace wristle
