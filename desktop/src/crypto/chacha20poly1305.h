#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

// @keep(pacodelavega): RFC 8439 ChaCha20-Poly1305 AEAD implementation for end-to-end encrypted audio streaming

namespace wristle {
namespace crypto {

constexpr size_t KEY_SIZE = 32;
constexpr size_t NONCE_SIZE = 12;
constexpr size_t TAG_SIZE = 16;

/**
 * Encrypts plaintext using ChaCha20-Poly1305 AEAD (RFC 8439).
 * Output buffer 'ciphertext' must be at least 'plaintext_len' bytes.
 * Output buffer 'tag' receives the 16-byte authentication tag.
 */
bool ChaCha20Poly1305_Encrypt(
    const uint8_t key[KEY_SIZE],
    const uint8_t nonce[NONCE_SIZE],
    const uint8_t* aad, size_t aad_len,
    const uint8_t* plaintext, size_t plaintext_len,
    uint8_t* ciphertext,
    uint8_t tag[TAG_SIZE]
);

/**
 * Decrypts and authenticates ciphertext using ChaCha20-Poly1305 AEAD (RFC 8439).
 * Returns true if the tag matches and decryption succeeded, false otherwise.
 * Output buffer 'plaintext' must be at least 'ciphertext_len' bytes.
 */
bool ChaCha20Poly1305_Decrypt(
    const uint8_t key[KEY_SIZE],
    const uint8_t nonce[NONCE_SIZE],
    const uint8_t* aad, size_t aad_len,
    const uint8_t* ciphertext, size_t ciphertext_len,
    const uint8_t tag[TAG_SIZE],
    uint8_t* plaintext
);

/**
 * Self-test against RFC 8439 test vectors.
 */
bool ChaCha20Poly1305_SelfTest();

} // namespace crypto
} // namespace wristle
