#include "chacha20poly1305.h"
#include <cstring>
#include <array>
#include <algorithm>

// @keep(pacodelavega): RFC 8439 ChaCha20 and Poly1305 verified implementation with RFC test vectors

namespace wristle {
namespace crypto {

namespace {

typedef struct poly1305_state_internal_64 {
    uint64_t r[3];
    uint64_t h[3];
    uint64_t pad[2];
    size_t leftover;
    unsigned char buffer[16];
    unsigned char final_block;
} poly1305_state_internal_64;

static inline uint64_t U8TO64(const unsigned char *p) {
    return (static_cast<uint64_t>(p[0])) |
           (static_cast<uint64_t>(p[1]) <<  8) |
           (static_cast<uint64_t>(p[2]) << 16) |
           (static_cast<uint64_t>(p[3]) << 24) |
           (static_cast<uint64_t>(p[4]) << 32) |
           (static_cast<uint64_t>(p[5]) << 40) |
           (static_cast<uint64_t>(p[6]) << 48) |
           (static_cast<uint64_t>(p[7]) << 56);
}

static inline void U64TO8(unsigned char *p, uint64_t v) {
    p[0] = static_cast<unsigned char>((v      ) & 0xff);
    p[1] = static_cast<unsigned char>((v >>  8) & 0xff);
    p[2] = static_cast<unsigned char>((v >> 16) & 0xff);
    p[3] = static_cast<unsigned char>((v >> 24) & 0xff);
    p[4] = static_cast<unsigned char>((v >> 32) & 0xff);
    p[5] = static_cast<unsigned char>((v >> 40) & 0xff);
    p[6] = static_cast<unsigned char>((v >> 48) & 0xff);
    p[7] = static_cast<unsigned char>((v >> 56) & 0xff);
}

void poly1305_init(poly1305_state_internal_64 *st, const unsigned char key[32]) {
    uint64_t t0 = U8TO64(&key[0]);
    uint64_t t1 = U8TO64(&key[8]);

    st->r[0] = ( t0                    ) & 0xffc0fffffffULL;
    st->r[1] = ((t0 >> 44) | (t1 << 20)) & 0xfffffc0ffffULL;
    st->r[2] = ((t1 >> 24)             ) & 0x00ffffffc0fULL;

    st->h[0] = 0;
    st->h[1] = 0;
    st->h[2] = 0;

    st->pad[0] = U8TO64(&key[16]);
    st->pad[1] = U8TO64(&key[24]);

    st->leftover = 0;
    st->final_block = 0;
}

static void poly1305_blocks(poly1305_state_internal_64 *st, const unsigned char *m, size_t bytes) {
    const uint64_t hibit = (st->final_block) ? 0 : (static_cast<uint64_t>(1) << 40);
    uint64_t r0 = st->r[0];
    uint64_t r1 = st->r[1];
    uint64_t r2 = st->r[2];

    uint64_t s1 = r1 * (5 << 2);
    uint64_t s2 = r2 * (5 << 2);

    uint64_t h0 = st->h[0];
    uint64_t h1 = st->h[1];
    uint64_t h2 = st->h[2];

    while (bytes >= 16) {
        uint64_t t0 = U8TO64(&m[0]);
        uint64_t t1 = U8TO64(&m[8]);

        h0 += (( t0                    ) & 0xfffffffffffULL);
        h1 += (((t0 >> 44) | (t1 << 20)) & 0xfffffffffffULL);
        h2 += (((t1 >> 24)             ) & 0x3ffffffffffULL) | hibit;

        unsigned __int128 d0 = (static_cast<unsigned __int128>(h0) * r0) + (static_cast<unsigned __int128>(h1) * s2) + (static_cast<unsigned __int128>(h2) * s1);
        unsigned __int128 d1 = (static_cast<unsigned __int128>(h0) * r1) + (static_cast<unsigned __int128>(h1) * r0) + (static_cast<unsigned __int128>(h2) * s2);
        unsigned __int128 d2 = (static_cast<unsigned __int128>(h0) * r2) + (static_cast<unsigned __int128>(h1) * r1) + (static_cast<unsigned __int128>(h2) * r0);

        uint64_t c = static_cast<uint64_t>(d0 >> 44); h0 = static_cast<uint64_t>(d0) & 0xfffffffffffULL;
        d1 += c;
        c = static_cast<uint64_t>(d1 >> 44); h1 = static_cast<uint64_t>(d1) & 0xfffffffffffULL;
        d2 += c;
        c = static_cast<uint64_t>(d2 >> 42); h2 = static_cast<uint64_t>(d2) & 0x3ffffffffffULL;
        h0 += c * 5;
        c = (h0 >> 44); h0 = h0 & 0xfffffffffffULL;
        h1 += c;

        m += 16;
        bytes -= 16;
    }

    st->h[0] = h0;
    st->h[1] = h1;
    st->h[2] = h2;
}

void poly1305_update(poly1305_state_internal_64 *st, const unsigned char *m, size_t bytes) {
    if (st->leftover) {
        size_t want = (16 - st->leftover);
        if (want > bytes) want = bytes;
        for (size_t i = 0; i < want; i++) st->buffer[st->leftover + i] = m[i];
        bytes -= want;
        m += want;
        st->leftover += want;
        if (st->leftover < 16) return;
        poly1305_blocks(st, st->buffer, 16);
        st->leftover = 0;
    }

    if (bytes >= 16) {
        size_t want = (bytes & ~static_cast<size_t>(15));
        poly1305_blocks(st, m, want);
        m += want;
        bytes -= want;
    }

    if (bytes) {
        for (size_t i = 0; i < bytes; i++) st->buffer[st->leftover + i] = m[i];
        st->leftover += bytes;
    }
}

void poly1305_finish(poly1305_state_internal_64 *st, unsigned char mac[16]) {
    if (st->leftover) {
        size_t i = st->leftover;
        st->buffer[i++] = 1;
        for (; i < 16; i++) st->buffer[i] = 0;
        st->final_block = 1;
        poly1305_blocks(st, st->buffer, 16);
    }

    uint64_t h0 = st->h[0];
    uint64_t h1 = st->h[1];
    uint64_t h2 = st->h[2];

    uint64_t c = h1 >> 44; h1 &= 0xfffffffffffULL;
    h2 += c;
    c = h2 >> 42; h2 &= 0x3ffffffffffULL;
    h0 += c * 5;
    c = h0 >> 44; h0 &= 0xfffffffffffULL;
    h1 += c;
    c = h1 >> 44; h1 &= 0xfffffffffffULL;
    h2 += c;
    c = h2 >> 42; h2 &= 0x3ffffffffffULL;
    h0 += c * 5;
    c = h0 >> 44; h0 &= 0xfffffffffffULL;
    h1 += c;

    uint64_t g0 = h0 + 5; c = g0 >> 44; g0 &= 0xfffffffffffULL;
    uint64_t g1 = h1 + c; c = g1 >> 44; g1 &= 0xfffffffffffULL;
    uint64_t g2 = h2 + c - (static_cast<uint64_t>(1) << 42);

    uint64_t mask = (g2 >> 63) - 1;
    g0 &= mask;
    g1 &= mask;
    g2 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0;
    h1 = (h1 & mask) | g1;
    h2 = (h2 & mask) | g2;

    h0 = ((h0      ) | (h1 << 44));
    h1 = ((h1 >> 20) | (h2 << 24));

    unsigned __int128 f0 = static_cast<unsigned __int128>(h0) + st->pad[0];
    unsigned __int128 f1 = static_cast<unsigned __int128>(h1) + st->pad[1] + (f0 >> 64);

    U64TO8(&mac[0], static_cast<uint64_t>(f0));
    U64TO8(&mac[8], static_cast<uint64_t>(f1));
}

inline uint32_t rotl32(uint32_t x, int n) {
    return (x << n) | (x >> (32 - n));
}

inline uint32_t load32_le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

inline void store32_le(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xff);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xff);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xff);
}

inline void store64_le(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) {
        p[i] = static_cast<uint8_t>((v >> (i * 8)) & 0xff);
    }
}

inline void quarter_round(uint32_t& a, uint32_t& b, uint32_t& c, uint32_t& d) {
    a += b; d ^= a; d = rotl32(d, 16);
    c += d; b ^= c; b = rotl32(b, 12);
    a += b; d ^= a; d = rotl32(d, 8);
    c += d; b ^= c; b = rotl32(b, 7);
}

void chacha20_block(const uint32_t state[16], uint8_t output[64]) {
    uint32_t x[16];
    std::memcpy(x, state, sizeof(x));

    for (int i = 0; i < 10; ++i) {
        quarter_round(x[0], x[4], x[8],  x[12]);
        quarter_round(x[1], x[5], x[9],  x[13]);
        quarter_round(x[2], x[6], x[10], x[14]);
        quarter_round(x[3], x[7], x[11], x[15]);
        quarter_round(x[0], x[5], x[10], x[15]);
        quarter_round(x[1], x[6], x[11], x[12]);
        quarter_round(x[2], x[7], x[8],  x[13]);
        quarter_round(x[3], x[4], x[9],  x[14]);
    }

    for (int i = 0; i < 16; ++i) {
        store32_le(output + i * 4, x[i] + state[i]);
    }
}

void chacha20_init_state(uint32_t state[16], const uint8_t key[32], const uint8_t nonce[12], uint32_t counter) {
    state[0] = 0x61707865;
    state[1] = 0x3320646e;
    state[2] = 0x79622d32;
    state[3] = 0x6b206574;

    for (int i = 0; i < 8; ++i) {
        state[4 + i] = load32_le(key + i * 4);
    }

    state[12] = counter;
    state[13] = load32_le(nonce + 0);
    state[14] = load32_le(nonce + 4);
    state[15] = load32_le(nonce + 8);
}

void chacha20_xor(const uint8_t key[32], const uint8_t nonce[12], uint32_t counter,
                  const uint8_t* in, uint8_t* out, size_t len) {
    uint32_t state[16];
    chacha20_init_state(state, key, nonce, counter);

    uint8_t block[64];
    while (len > 0) {
        chacha20_block(state, block);
        state[12]++;

        size_t chunk = std::min(len, static_cast<size_t>(64));
        for (size_t i = 0; i < chunk; ++i) {
            out[i] = in[i] ^ block[i];
        }

        in += chunk;
        out += chunk;
        len -= chunk;
    }
}

} // anonymous namespace

bool ChaCha20Poly1305_Encrypt(
    const uint8_t key[KEY_SIZE],
    const uint8_t nonce[NONCE_SIZE],
    const uint8_t* aad, size_t aad_len,
    const uint8_t* plaintext, size_t plaintext_len,
    uint8_t* ciphertext,
    uint8_t tag[TAG_SIZE]
) {
    uint8_t poly_key[64];
    uint32_t state[16];
    chacha20_init_state(state, key, nonce, 0);
    chacha20_block(state, poly_key);

    chacha20_xor(key, nonce, 1, plaintext, ciphertext, plaintext_len);

    poly1305_state_internal_64 pst;
    poly1305_init(&pst, poly_key);

    if (aad && aad_len > 0) {
        poly1305_update(&pst, aad, aad_len);
        if (aad_len % 16 != 0) {
            uint8_t zeros[16] = {0};
            poly1305_update(&pst, zeros, 16 - (aad_len % 16));
        }
    }

    if (ciphertext && plaintext_len > 0) {
        poly1305_update(&pst, ciphertext, plaintext_len);
        if (plaintext_len % 16 != 0) {
            uint8_t zeros[16] = {0};
            poly1305_update(&pst, zeros, 16 - (plaintext_len % 16));
        }
    }

    uint8_t lens[16];
    store64_le(lens + 0, static_cast<uint64_t>(aad_len));
    store64_le(lens + 8, static_cast<uint64_t>(plaintext_len));
    poly1305_update(&pst, lens, 16);

    poly1305_finish(&pst, tag);
    return true;
}

bool ChaCha20Poly1305_Decrypt(
    const uint8_t key[KEY_SIZE],
    const uint8_t nonce[NONCE_SIZE],
    const uint8_t* aad, size_t aad_len,
    const uint8_t* ciphertext, size_t ciphertext_len,
    const uint8_t tag[TAG_SIZE],
    uint8_t* plaintext
) {
    uint8_t poly_key[64];
    uint32_t state[16];
    chacha20_init_state(state, key, nonce, 0);
    chacha20_block(state, poly_key);

    poly1305_state_internal_64 pst;
    poly1305_init(&pst, poly_key);

    if (aad && aad_len > 0) {
        poly1305_update(&pst, aad, aad_len);
        if (aad_len % 16 != 0) {
            uint8_t zeros[16] = {0};
            poly1305_update(&pst, zeros, 16 - (aad_len % 16));
        }
    }

    if (ciphertext && ciphertext_len > 0) {
        poly1305_update(&pst, ciphertext, ciphertext_len);
        if (ciphertext_len % 16 != 0) {
            uint8_t zeros[16] = {0};
            poly1305_update(&pst, zeros, 16 - (ciphertext_len % 16));
        }
    }

    uint8_t lens[16];
    store64_le(lens + 0, static_cast<uint64_t>(aad_len));
    store64_le(lens + 8, static_cast<uint64_t>(ciphertext_len));
    poly1305_update(&pst, lens, 16);

    uint8_t expected_tag[16];
    poly1305_finish(&pst, expected_tag);

    uint32_t diff = 0;
    for (int i = 0; i < 16; ++i) {
        diff |= (expected_tag[i] ^ tag[i]);
    }
    if (diff != 0) {
        return false;
    }

    chacha20_xor(key, nonce, 1, ciphertext, plaintext, ciphertext_len);
    return true;
}

bool ChaCha20Poly1305_SelfTest() {
    const uint8_t key[32] = {
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
        0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f
    };
    const uint8_t nonce[12] = {
        0x07, 0x00, 0x00, 0x00,
        0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47
    };
    const char* pt_str = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    size_t pt_len = std::strlen(pt_str);
    const uint8_t aad[12] = {
        0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7
    };
    const uint8_t expected_tag[16] = {
        0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a,
        0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60, 0x06, 0x91
    };

    std::vector<uint8_t> ct(pt_len);
    uint8_t tag[16] = {0};
    ChaCha20Poly1305_Encrypt(key, nonce, aad, sizeof(aad),
                             reinterpret_cast<const uint8_t*>(pt_str), pt_len,
                             ct.data(), tag);

    if (std::memcmp(tag, expected_tag, 16) != 0) {
        return false;
    }

    std::vector<uint8_t> decrypted(pt_len);
    if (!ChaCha20Poly1305_Decrypt(key, nonce, aad, sizeof(aad),
                                  ct.data(), pt_len, tag, decrypted.data())) {
        return false;
    }

    return std::memcmp(decrypted.data(), pt_str, pt_len) == 0;
}

} // namespace crypto
} // namespace wristle
