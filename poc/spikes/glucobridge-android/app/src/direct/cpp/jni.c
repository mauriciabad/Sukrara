// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * JNI glue for dev.spike.glucobridge.direct.Libre3Crypto.
 *
 * Wraps two things compiled from a local Juggluco checkout (GPLv3, see CMakeLists.txt):
 *   - the Libre 3 app-side security core (challenge handshake), and
 *   - tinycrypt AES-128-CCM for the data plane.
 * Nothing here logs or returns key material.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "sensor_security_context.h"
#include <tinycrypt/aes.h>
#include <tinycrypt/ccm_mode.h>

#define NONCE_LEN 13
#define TAG_LEN 4

static int entropy_cb(void *user, uint8_t *out, size_t len) {
    (void)user;
    arc4random_buf(out, len);
    return 1;
}

static l3_sensor_security_context *ctx_of(jlong handle) {
    return (l3_sensor_security_context *)(intptr_t)handle;
}

/* Copies a jbyteArray of exactly `expected` bytes into `out`; returns 0 on size mismatch. */
static int copy_exact(JNIEnv *env, jbyteArray src, jsize expected, uint8_t *out) {
    if (!src || (*env)->GetArrayLength(env, src) != expected) return 0;
    (*env)->GetByteArrayRegion(env, src, 0, expected, (jbyte *)out);
    return 1;
}

JNIEXPORT jlong JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_begin(JNIEnv *env, jobject thiz,
                                                     jbyteArray kauth, jint security_version) {
    (void)thiz;
    uint8_t saved[L3_LEN_SAVED_AUTHORIZATION];
    if (!copy_exact(env, kauth, (jsize)sizeof saved, saved)) return 0;

    l3_security_engine_config cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.entropy = entropy_cb;

    l3_sensor_security_context *ctx = l3_sensor_security_context_create(&cfg);
    if (!ctx) goto wipe;
    if (l3_sensor_security_begin_handshake(ctx) != 1 ||
        l3_sensor_security_select_app_key_and_saved_authorization(
            ctx, (unsigned)security_version, saved, sizeof saved) != 1) {
        l3_sensor_security_context_destroy(ctx);
        ctx = NULL;
    }
wipe:
    memset(saved, 0, sizeof saved);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_free(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (handle) l3_sensor_security_context_destroy(ctx_of(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_encryptChallengeReply(JNIEnv *env, jobject thiz, jlong handle,
                                                                    jbyteArray jnonce, jbyteArray jplain) {
    (void)thiz;
    uint8_t nonce[L3_LEN_CHALLENGE_NONCE], plain[L3_LEN_CHALLENGE_REPLY_PLAIN];
    uint8_t out[L3_LEN_CHALLENGE_REPLY_CRYPT];
    if (!handle || !copy_exact(env, jnonce, sizeof nonce, nonce) ||
        !copy_exact(env, jplain, sizeof plain, plain)) return NULL;

    jbyteArray result = NULL;
    if (l3_sensor_security_encrypt_challenge_reply_into(ctx_of(handle), nonce, sizeof nonce,
                                                        plain, sizeof plain, out) == 1) {
        result = (*env)->NewByteArray(env, (jsize)sizeof out);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof out, (const jbyte *)out);
    }
    memset(plain, 0, sizeof plain);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_decryptChallengeResponse(JNIEnv *env, jobject thiz, jlong handle,
                                                                       jbyteArray jnonce, jbyteArray jcipher) {
    (void)thiz;
    uint8_t nonce[L3_LEN_CHALLENGE_NONCE], cipher[L3_LEN_CHALLENGE_RESPONSE_CRYPT];
    uint8_t out[L3_LEN_CHALLENGE_RESPONSE_PLAIN];
    if (!handle || !copy_exact(env, jnonce, sizeof nonce, nonce) ||
        !copy_exact(env, jcipher, sizeof cipher, cipher)) return NULL;

    jbyteArray result = NULL;
    if (l3_sensor_security_decrypt_challenge_response_into(ctx_of(handle), nonce, sizeof nonce,
                                                           cipher, sizeof cipher, out) == 1) {
        result = (*env)->NewByteArray(env, (jsize)sizeof out);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof out, (const jbyte *)out);
    }
    memset(out, 0, sizeof out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_ccmEncrypt(JNIEnv *env, jobject thiz,
                                                          jbyteArray jkey, jbyteArray jnonce, jbyteArray jin) {
    (void)thiz;
    uint8_t key[16], nonce[NONCE_LEN];
    if (!copy_exact(env, jkey, sizeof key, key) || !copy_exact(env, jnonce, sizeof nonce, nonce)) return NULL;

    jsize in_len = jin ? (*env)->GetArrayLength(env, jin) : 0;
    if (in_len <= 0) return NULL;
    uint8_t *in = malloc((size_t)in_len);
    uint8_t *out = malloc((size_t)in_len + TAG_LEN);
    jbyteArray result = NULL;
    if (!in || !out) goto enc_done;
    (*env)->GetByteArrayRegion(env, jin, 0, in_len, (jbyte *)in);

    struct tc_aes_key_sched_struct sched;
    struct tc_ccm_mode_struct ccm;
    if (tc_aes128_set_encrypt_key(&sched, key) != 1) goto enc_done;
    if (tc_ccm_config(&ccm, &sched, nonce, NONCE_LEN, TAG_LEN) != 1) goto enc_done;
    if (tc_ccm_generation_encryption(out, (unsigned int)(in_len + TAG_LEN), NULL, 0,
                                     in, (unsigned int)in_len, &ccm) != 1) goto enc_done;

    result = (*env)->NewByteArray(env, in_len + TAG_LEN);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, in_len + TAG_LEN, (const jbyte *)out);
enc_done:
    memset(key, 0, sizeof key);
    free(in);
    free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_spike_glucobridge_direct_Libre3Crypto_ccmDecrypt(JNIEnv *env, jobject thiz,
                                                          jbyteArray jkey, jbyteArray jnonce, jbyteArray jin) {
    (void)thiz;
    uint8_t key[16], nonce[NONCE_LEN];
    if (!copy_exact(env, jkey, sizeof key, key) || !copy_exact(env, jnonce, sizeof nonce, nonce)) return NULL;

    jsize in_len = jin ? (*env)->GetArrayLength(env, jin) : 0;
    if (in_len <= TAG_LEN) return NULL;
    uint8_t *in = malloc((size_t)in_len);
    uint8_t *plain = malloc((size_t)in_len - TAG_LEN);
    jbyteArray result = NULL;
    if (!in || !plain) goto done;
    (*env)->GetByteArrayRegion(env, jin, 0, in_len, (jbyte *)in);

    struct tc_aes_key_sched_struct sched;
    struct tc_ccm_mode_struct ccm;
    if (tc_aes128_set_encrypt_key(&sched, key) != 1) goto done;
    if (tc_ccm_config(&ccm, &sched, nonce, NONCE_LEN, TAG_LEN) != 1) goto done;
    if (tc_ccm_decryption_verification(plain, (unsigned int)(in_len - TAG_LEN), NULL, 0,
                                       in, (unsigned int)in_len, &ccm) != 1) goto done;

    result = (*env)->NewByteArray(env, in_len - TAG_LEN);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, in_len - TAG_LEN, (const jbyte *)plain);
done:
    memset(key, 0, sizeof key);
    if (plain) { memset(plain, 0, (size_t)in_len - TAG_LEN); free(plain); }
    free(in);
    return result;
}
