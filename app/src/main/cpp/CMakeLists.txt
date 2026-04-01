// [SIPHON_CUSTOM_ENGINE]
cmake_minimum_required(VERSION 3.22.1)

project("rhythm_audio")

# Add NDK's log library
find_library(log-lib log)
find_library(android-lib android)
# AAudio removed — Siphon uses only libusb for USB audio

# Define the main native library
add_library(
    rhythm_audio
    SHARED
    rhythm_audio.cpp
    NativePlayer.cpp
    dsp/ParametricEq.cpp
    usb/UacDescriptorParser.h
    usb/UsbTransfer.h
)

target_include_directories(
    rhythm_audio
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/usb
)

target_link_libraries(
    rhythm_audio
    ${log-lib}
    ${android-lib}
)

# Siphon Engine native bindings
add_library(
    siphon_engine
    SHARED
    siphon_engine.cpp
    siphon_software_gain.cpp
    siphon_dsp_pipeline.cpp
    siphon_volume_controller.cpp
    siphon_resampler.cpp
    jni_bridge.cpp
    format_validator.cpp
    wav_parser.cpp
    flac_parser.cpp
)

target_compile_features(siphon_engine PRIVATE cxx_std_17)
target_compile_options(siphon_engine PRIVATE -O3 -ffast-math)

# Link required libraries
target_link_libraries(
    siphon_engine
    ${log-lib}
    ${android-lib}
)

# Vendored libusb (no network fetch required)
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

# Build libusb statically for Android
add_library(usb-1.0 STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

target_include_directories(usb-1.0 PUBLIC
    ${LIBUSB_ROOT}/libusb
    ${LIBUSB_ROOT}/android
    ${LIBUSB_ROOT}/libusb/os
)

target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 log)

# Ensure siphon_engine can find <libusb.h>
include_directories(${LIBUSB_ROOT}/libusb)

include(FetchContent)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  FLAC
  GIT_REPOSITORY https://github.com/xiph/flac.git
  GIT_TAG        1.4.3
)
FetchContent_MakeAvailable(FLAC)

target_include_directories(siphon_engine PRIVATE ${flac_SOURCE_DIR}/include)

target_link_libraries(
    siphon_engine
    ${log-lib}
    ${android-lib}
    usb-1.0
    FLAC
)
