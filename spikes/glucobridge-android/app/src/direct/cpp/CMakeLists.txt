# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(glucobridge C)

# The Libre 3 security core and AES-CCM come from a local Juggluco checkout (GPLv3).
# Juggluco is NOT vendored in this repo — clone it first:
#   git clone https://github.com/j-kaltes/Juggluco third_party/Juggluco
set(JUGGLUCO "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../third_party/Juggluco/Common/src/main/cpp"
    CACHE PATH "Path to Juggluco's Common/src/main/cpp")
set(L3 "${JUGGLUCO}/libre3/process")
set(TC "${JUGGLUCO}/bcrypt")

if(NOT EXISTS "${L3}/libre3_app_core.c")
    message(FATAL_ERROR "Juggluco sources not found at ${JUGGLUCO}. "
                        "Clone https://github.com/j-kaltes/Juggluco into third_party/Juggluco.")
endif()

set(CORE
    whitebox_lookup_table hmac_sha1_portable libre3_app_core saved_authorization_record
    sensor_security_context challenge_cipher_context p256_private_scalar_codec fixed_app_private_keys
    p256_scalar_multiply authorization_round_feedback authorization_round_mixer p256_coordinate_reduction
    authorization_round_primary authorization_round_secondary authorization_vector_reduction
    authorization_vector_feedback authorization_round_driver authorization_shared_point_core
    authorization_shared_point_frames authorization_shared_point_tail authorization_frame_transform
    authorization_digest authorization_finalizer)
foreach(f ${CORE})
    list(APPEND CORE_SRC "${L3}/${f}.c")
endforeach()

add_library(glucobridge SHARED
    jni.c
    ${CORE_SRC}
    "${TC}/aes_encrypt.c" "${TC}/ccm_mode.c" "${TC}/utils.c")

target_include_directories(glucobridge PRIVATE "${L3}" "${JUGGLUCO}/libre3" "${TC}")
# The core can take entropy from the caller instead of linking OpenSSL; we pass bionic's arc4random.
target_compile_definitions(glucobridge PRIVATE L3_EXTERNAL_ENTROPY_ONLY=1)
target_compile_options(glucobridge PRIVATE -O2 -fvisibility=hidden -Wall)
