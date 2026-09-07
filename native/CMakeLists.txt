cmake_minimum_required(VERSION 3.18)

project(aap-lv2-sfizz LANGUAGES C CXX)

get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
set(SFIZZ_ROOT "${REPOSITORY_ROOT}/external/sfizz")
set(AAP_LV2_ROOT "${REPOSITORY_ROOT}/external/aap-lv2")

add_subdirectory("${SFIZZ_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/sfizz")

find_package(androidaudioplugin REQUIRED CONFIG)

add_library(aap-lv2-sfizz-resources SHARED AudioPluginLV2Resources_jni.cpp)
target_compile_features(aap-lv2-sfizz-resources PRIVATE cxx_std_20)
target_include_directories(aap-lv2-sfizz-resources PRIVATE
        "${AAP_LV2_ROOT}/androidaudioplugin-lv2/src/main/cpp/src"
        "${AAP_LV2_ROOT}/external/aap-core/include"
        "${AAP_LV2_ROOT}/external/aap-core/external/cmidi2"
        "${AAP_LV2_ROOT}/external"
        "${AAP_LV2_ROOT}/external/serd/src"
        "${AAP_LV2_ROOT}/external/sord/src"
        "${AAP_LV2_ROOT}/external/lilv/src"
        "${AAP_LV2_ROOT}/external/lilv/include"
        "${AAP_LV2_ROOT}/external/lv2"
        "${AAP_LV2_ROOT}/external/serd/include"
        "${AAP_LV2_ROOT}/external/sord/include"
        "${AAP_LV2_ROOT}/external/sratom/include"
        "${SFIZZ_ROOT}/src")
target_compile_options(aap-lv2-sfizz-resources PRIVATE -Wall -Wextra)
target_link_libraries(aap-lv2-sfizz-resources PRIVATE
        android
        log
        androidaudioplugin::androidaudioplugin)
