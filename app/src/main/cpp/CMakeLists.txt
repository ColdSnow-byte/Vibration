cmake_minimum_required(VERSION 3.22.1)
project(vibration_imgui CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/imgui-1.92.9b)

# Dear ImGui 核心 + OpenGL ES 3 后端（imgui_impl_android 面向 NativeActivity，
# 这里以 GLSurfaceView 嵌入 Compose，输入事件由 WaveformSurfaceView 直接转发，故不使用）
add_library(imgui STATIC
        ${IMGUI_DIR}/imgui.cpp
        ${IMGUI_DIR}/imgui_draw.cpp
        ${IMGUI_DIR}/imgui_tables.cpp
        ${IMGUI_DIR}/imgui_widgets.cpp
        ${IMGUI_DIR}/backends/imgui_impl_opengl3.cpp
)

target_include_directories(imgui PUBLIC ${IMGUI_DIR} ${IMGUI_DIR}/backends)

# Android 上不会自动定义，显式指定使用 OpenGL ES 3
target_compile_definitions(imgui PUBLIC IMGUI_IMPL_OPENGL_ES3)

add_library(vibration_imgui SHARED vibration_imgui.cpp)

target_link_libraries(vibration_imgui
        imgui
        GLESv3
        log
)
