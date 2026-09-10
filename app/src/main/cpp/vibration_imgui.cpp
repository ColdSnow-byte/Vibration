// 用 Dear ImGui 绘制悬浮面板：实时震动波形 + 函数图像（正弦 / 余弦 / 方波 / 锯齿 / 三角）。
//
// 运行方式：WindowManager 悬浮窗内的 GLSurfaceView(OpenGL ES 3) -> nativeRender() -> ImGui 绘制。
// 数据流向：主线程每 5ms 采样一次振幅写入环形缓冲，GL 线程每帧读取并绘制。
// 环形缓冲用原子变量，两个线程无需加锁。
//
// 面板的拖动 / 缩放由面板内的 ImGui 控件产生增量，Kotlin 侧每帧取走并更新 WindowManager 布局参数。

#include <jni.h>

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <ctime>

#include "imgui.h"
#include "imgui_impl_opengl3.h"

#define LOG_TAG "VibImGui"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float kPi2 = 6.28318530718f;

// 环形缓冲：约 40 秒 @200Hz
constexpr int kCapacity = 8192;
// 采样值里用来标记「此处马达被重启」的位
constexpr int kEventBit = 0x100;

std::atomic<int> g_buffer[kCapacity];
std::atomic<int> g_write{0};     // 下一个写入位置
std::atomic<int> g_written{0};   // 已写入总数

std::atomic<int> g_mode{0};
std::atomic<int> g_strategy{0};
std::atomic<int> g_segmentMs{0};
std::atomic<int> g_useMaxAmp{1};
std::atomic<float> g_sampleHz{200.f};
std::atomic<int> g_lastAmp{0};

// 只在 GL 线程访问
int g_windowMs = 5000;
int g_surfaceW = 0;
int g_surfaceH = 0;
float g_scale = 1.0f;
bool g_ready = false;
double g_lastFrameTime = 0.0;

int g_viewMode = 0;  // 0 = 震动波形, 1 = 函数图像
int g_funcType = 0;  // 0 sin / 1 cos / 2 方波 / 3 锯齿 / 4 三角
float g_funcAmp = 0.8f;
float g_funcFreq = 1.0f;
float g_funcSpeed = 0.5f;
float g_funcPhase = 0.0f;
float g_funcTime = 0.0f;
// 函数参数被用户改过，等待 Kotlin 取走（用于驱动马达）
bool g_funcDirty = true;
// 本帧是否正在拖动函数参数的控件；拖动期间不应用到马达，避免每帧重启波形
bool g_funcEditing = false;

// 面板拖动 / 缩放 / 关闭的待取增量
int g_pendingDx = 0;
int g_pendingDy = 0;
int g_pendingDw = 0;
int g_pendingDh = 0;
bool g_pendingClose = false;

const char* ModeName(int m) {
    switch (m) {
        case 1: return "间歇 Intermittent";
        case 2: return "持续 Continuous";
        case 3: return "函数 Function";
        default: return "待机 Idle";
    }
}

const char* StrategyName(int s) {
    return s == 1 ? "单次续期" : "循环波形";
}

double NowSeconds() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

void SetupFonts() {
    ImGuiIO& io = ImGui::GetIO();
    io.Fonts->Clear();
    // 默认字体不含中文字形，优先加载系统中文字体
    static const char* kCandidates[] = {
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansTC-Regular.otf",
            "/system/fonts/NotoSansJP-Regular.otf",
    };
    bool loaded = false;
    for (const char* path : kCandidates) {
        if (io.Fonts->AddFontFromFileTTF(path, 14.0f * g_scale, nullptr,
                                         io.Fonts->GetGlyphRangesChineseSimplifiedCommon())) {
            LOGI("font loaded: %s", path);
            loaded = true;
            break;
        }
    }
    if (!loaded) {
        LOGE("no CJK font found, fall back to default (Chinese labels will be blank)");
        io.Fonts->AddFontDefault();
    }
    // 不要调用 io.Fonts->Build()：1.92 的后端带 ImGuiBackendFlags_RendererHasTextures，
    // 字体图集由后端在首帧自动构建，提前 Build() 会触发断言并 abort
}

// ---------- 震动波形 ----------

void DrawWaveGraph(const ImVec2& size) {
    ImGui::Dummy(size);
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 p0 = ImGui::GetItemRectMin();
    const ImVec2 p1 = ImGui::GetItemRectMax();

    dl->AddRectFilled(p0, p1, IM_COL32(13, 15, 21, 255), 6.0f);
    dl->AddRect(p0, p1, IM_COL32(48, 56, 72, 255), 6.0f);

    const float pad = 6.0f;
    const float plotW = size.x - pad * 2.0f;
    const float plotH = size.y - pad * 2.0f;
    const float topY = p0.y + pad;
    const float baseY = p1.y - pad;
    const float leftX = p0.x + pad;
    if (plotW <= 0.0f || plotH <= 0.0f) return;

    // 振幅参考线
    for (int lvl : {64, 128, 191, 255}) {
        const float y = baseY - (lvl / 255.0f) * plotH;
        dl->AddLine(ImVec2(leftX, y), ImVec2(leftX + plotW, y), IM_COL32(42, 50, 66, 220));
    }

    const int available = std::min(g_written.load(std::memory_order_relaxed), kCapacity);
    const float hz = std::max(g_sampleHz.load(std::memory_order_relaxed), 1.0f);
    const int want = (int)(g_windowMs / 1000.0f * hz);
    const int count = std::min(std::min(available, want), kCapacity);

    if (count < 2) {
        dl->AddText(ImVec2(leftX + 8.0f, p0.y + plotH * 0.5f), IM_COL32(120, 132, 150, 255),
                    "等待采样数据...");
        return;
    }

    const int writeIdx = g_write.load(std::memory_order_acquire);
    const int startIdx = (writeIdx - count + kCapacity) % kCapacity;
    int cols = (int)plotW;
    if (cols < 1) cols = 1;

    for (int c = 0; c < cols; ++c) {
        int from = (int)((long long)c * count / cols);
        int to = std::max((int)((long long)(c + 1) * count / cols), from + 1);
        if (to > count) to = count;

        int maxAmp = 0;
        bool hasEvent = false;
        for (int i = from; i < to; ++i) {
            const int v = g_buffer[(startIdx + i) % kCapacity].load(std::memory_order_relaxed);
            maxAmp = std::max(maxAmp, v & 0xFF);
            if (v & kEventBit) hasEvent = true;
        }

        const float x = leftX + c;
        if (hasEvent) {  // 马达重启：贯穿全高的橙色竖线
            dl->AddLine(ImVec2(x, topY), ImVec2(x, baseY), IM_COL32(255, 170, 60, 210));
        }
        float h = (maxAmp / 255.0f) * plotH;  // 0 时保留 1px，保证时间轴连续、缺口可见
        if (h < 1.0f) h = 1.0f;
        const ImU32 color = maxAmp > 0 ? IM_COL32(90, 210, 255, 255) : IM_COL32(58, 68, 84, 255);
        dl->AddLine(ImVec2(x, baseY), ImVec2(x, baseY - h), color);
    }

    // 时间刻度：每 1 秒
    const float colsPerSecond = hz * (float)cols / (float)count;
    if (colsPerSecond > 4.0f) {
        for (float sec = 1.0f; sec * colsPerSecond < plotW; sec += 1.0f) {
            const float x = leftX + plotW - sec * colsPerSecond;
            dl->AddLine(ImVec2(x, topY), ImVec2(x, baseY), IM_COL32(60, 72, 92, 140));
        }
    }
}

// ---------- 函数图像 ----------

float EvalFunction(int type, float phase) {
    const float cycles = phase / kPi2;
    switch (type) {
        case 1: return std::cos(phase);
        case 2: return std::sin(phase) >= 0.0f ? 1.0f : -1.0f;             // 方波
        case 3: {                                                          // 锯齿
            float t = cycles - std::floor(cycles);
            return 2.0f * t - 1.0f;
        }
        case 4: {                                                          // 三角
            float t = cycles - std::floor(cycles);
            return 4.0f * std::abs(t - 0.5f) - 1.0f;
        }
        default: return std::sin(phase);
    }
}

const char* FunctionLabel(int type) {
    switch (type) {
        case 1: return "cos";
        case 2: return "方波";
        case 3: return "锯齿";
        case 4: return "三角";
        default: return "sin";
    }
}

void DrawFunctionGraph(const ImVec2& size) {
    ImGui::Dummy(size);
    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 p0 = ImGui::GetItemRectMin();
    const ImVec2 p1 = ImGui::GetItemRectMax();

    dl->AddRectFilled(p0, p1, IM_COL32(13, 15, 21, 255), 6.0f);
    dl->AddRect(p0, p1, IM_COL32(48, 56, 72, 255), 6.0f);

    const float pad = 6.0f;
    const float plotW = size.x - pad * 2.0f;
    const float plotH = size.y - pad * 2.0f;
    const float leftX = p0.x + pad;
    const float centerY = p0.y + pad + plotH * 0.5f;
    if (plotW <= 0.0f || plotH <= 0.0f) return;

    const float amp = plotH * 0.5f * 0.9f * g_funcAmp;

    // 网格：横轴 ±0.5 / 0，竖轴每 1/4 周期
    for (float g : {-0.5f, 0.5f}) {
        const float y = centerY - g * amp * 2.0f;
        dl->AddLine(ImVec2(leftX, y), ImVec2(leftX + plotW, y), IM_COL32(42, 50, 66, 200));
    }
    dl->AddLine(ImVec2(leftX, centerY), ImVec2(leftX + plotW, centerY), IM_COL32(70, 82, 100, 255));
    for (int i = 1; i < 8; ++i) {
        const float x = leftX + plotW * i / 8.0f;
        dl->AddLine(ImVec2(x, p0.y + pad), ImVec2(x, p1.y - pad), IM_COL32(38, 46, 60, 160));
    }

    // 画 3 个周期
    const float span = 3.0f / std::max(g_funcFreq, 0.1f);
    const int samples = (int)plotW;
    if (samples < 2) return;

    ImVector<ImVec2> points;
    points.resize(samples);
    for (int i = 0; i < samples; ++i) {
        const float u = (float)i / (float)(samples - 1);
        const float t = g_funcTime - span * (1.0f - u);
        const float y = g_funcAmp * EvalFunction(g_funcType, kPi2 * g_funcFreq * t + g_funcPhase);
        points[i] = ImVec2(leftX + u * plotW, centerY - y * (plotH * 0.5f * 0.9f));
    }
    dl->AddPolyline(points.Data, samples, IM_COL32(120, 225, 150, 255), 0, 2.0f * g_scale * 0.6f);

    // 当前值标记
    dl->AddCircleFilled(points[samples - 1], 3.0f * g_scale * 0.8f, IM_COL32(255, 210, 90, 255));
}

// ---------- 面板 ----------

void DrawPanel() {
    ImGuiIO& io = ImGui::GetIO();
    const float headerH = 26.0f * g_scale;

    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f));
    ImGui::SetNextWindowSize(io.DisplaySize);
    ImGui::Begin("##vibration-panel", nullptr,
                 ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                         ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoCollapse |
                         ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoBringToFrontOnFocus);

    const ImVec2 winPos = ImGui::GetWindowPos();
    const float winW = ImGui::GetWindowWidth();
    const float winH = ImGui::GetWindowHeight();
    ImDrawList* dl = ImGui::GetWindowDrawList();

    // 标题栏（拖动区）
    dl->AddRectFilled(winPos, ImVec2(winPos.x + winW, winPos.y + headerH), IM_COL32(28, 32, 42, 255));
    dl->AddText(ImVec2(winPos.x + 8.0f, winPos.y + 5.0f), IM_COL32(190, 205, 230, 255),
                "ImGui 面板（拖动此处 / 右下角缩放）");
    ImGui::SetCursorScreenPos(winPos);
    ImGui::InvisibleButton("##drag", ImVec2(winW - 26.0f * g_scale, headerH));
    if (ImGui::IsItemActive() && ImGui::IsMouseDragging(0)) {
        g_pendingDx += (int)io.MouseDelta.x;
        g_pendingDy += (int)io.MouseDelta.y;
    }
    ImGui::SetCursorScreenPos(ImVec2(winPos.x + winW - 24.0f * g_scale, winPos.y + 3.0f));
    if (ImGui::Button("x", ImVec2(20.0f * g_scale, headerH - 6.0f))) g_pendingClose = true;

    ImGui::SetCursorScreenPos(ImVec2(winPos.x + 10.0f, winPos.y + headerH + 6.0f));

    // 视图切换
    if (ImGui::RadioButton("震动波形", g_viewMode == 0)) g_viewMode = 0;
    ImGui::SameLine();
    if (ImGui::RadioButton("函数图像", g_viewMode == 1)) g_viewMode = 1;

    if (g_viewMode == 0) {
        ImGui::Text("%s | %s | %d ms | 振幅 %s",
                    ModeName(g_mode.load(std::memory_order_relaxed)),
                    StrategyName(g_strategy.load(std::memory_order_relaxed)),
                    g_segmentMs.load(std::memory_order_relaxed),
                    g_useMaxAmp.load(std::memory_order_relaxed) ? "255" : "默认");
        for (int w : {2000, 5000, 10000, 30000}) {
            char label[16];
            snprintf(label, sizeof(label), "%ds", w / 1000);
            if (ImGui::RadioButton(label, g_windowMs == w)) g_windowMs = w;
            ImGui::SameLine();
        }
        ImGui::TextDisabled("时间窗");
    } else {
        g_funcEditing = false;
        for (int t = 0; t < 5; ++t) {
            if (ImGui::RadioButton(FunctionLabel(t), g_funcType == t)) {
                g_funcType = t;
                g_funcDirty = true;
            }
            if (ImGui::IsItemActive()) g_funcEditing = true;
            if (t < 4) ImGui::SameLine();
        }
        ImGui::SetNextItemWidth(90.0f * g_scale);
        if (ImGui::SliderFloat("振幅 A", &g_funcAmp, 0.1f, 1.0f, "%.2f")) g_funcDirty = true;
        if (ImGui::IsItemActive()) g_funcEditing = true;
        ImGui::SameLine();
        ImGui::SetNextItemWidth(90.0f * g_scale);
        if (ImGui::SliderFloat("频率 f", &g_funcFreq, 0.1f, 10.0f, "%.1f")) g_funcDirty = true;
        if (ImGui::IsItemActive()) g_funcEditing = true;
        ImGui::SameLine();
        ImGui::SetNextItemWidth(90.0f * g_scale);
        if (ImGui::SliderFloat("速度", &g_funcSpeed, -2.0f, 2.0f, "%.1f")) g_funcDirty = true;
        if (ImGui::IsItemActive()) g_funcEditing = true;
        ImGui::TextDisabled("松手后应用到「函数震动」");
    }

    const float bottomH = 26.0f * g_scale;
    const ImVec2 graphSize = ImVec2(std::max(ImGui::GetContentRegionAvail().x, 32.0f),
                                    std::max(io.DisplaySize.y - ImGui::GetCursorPosY() - bottomH,
                                             40.0f));
    if (g_viewMode == 0) {
        DrawWaveGraph(graphSize);
        ImGui::Text("当前振幅: %d", g_lastAmp.load(std::memory_order_relaxed));
        ImGui::SameLine();
        ImGui::TextDisabled("| 橙线 = 马达重启");
    } else {
        DrawFunctionGraph(graphSize);
        ImGui::Text("y = %.2f x %s(2 pi x %.1f t)", g_funcAmp, FunctionLabel(g_funcType),
                    g_funcFreq);
    }

    // 右下角缩放手柄
    const ImVec2 handlePos(winPos.x + winW - 18.0f * g_scale, winPos.y + winH - 18.0f * g_scale);
    ImGui::SetCursorScreenPos(handlePos);
    ImGui::InvisibleButton("##resize", ImVec2(18.0f * g_scale, 18.0f * g_scale));
    if (ImGui::IsItemActive() && ImGui::IsMouseDragging(0)) {
        g_pendingDw += (int)io.MouseDelta.x;
        g_pendingDh += (int)io.MouseDelta.y;
    }
    const ImVec2 corner(winPos.x + winW - 4.0f, winPos.y + winH - 4.0f);
    dl->AddTriangleFilled(ImVec2(corner.x - 12.0f * g_scale, corner.y), corner,
                          ImVec2(corner.x, corner.y - 12.0f * g_scale),
                          IM_COL32(110, 125, 150, 200));

    ImGui::End();
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeInit(JNIEnv*, jobject, jfloat density) {
    if (g_ready) return;

    for (auto& slot : g_buffer) slot.store(0, std::memory_order_relaxed);
    g_write.store(0, std::memory_order_relaxed);
    g_written.store(0, std::memory_order_relaxed);

    g_scale = std::clamp(density, 1.0f, 4.0f);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();

    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;      // 不写 imgui.ini（Android 上没有可写工作目录）
    io.LogFilename = nullptr;

    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.ScaleAllSizes(g_scale);
    style.WindowPadding = ImVec2(8.0f * g_scale, 6.0f * g_scale);
    style.WindowRounding = 8.0f * g_scale;
    style.FrameRounding = 4.0f * g_scale;
    style.Colors[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.08f, 0.10f, 1.0f);
    style.Colors[ImGuiCol_TitleBg] = ImVec4(0.10f, 0.12f, 0.16f, 1.0f);

    // 先初始化后端：新后端会设置 ImGuiBackendFlags_RendererHasTextures，
    // 之后添加的字体由它在首帧自动上传纹理
    ImGui_ImplOpenGL3_Init("#version 300 es");

    SetupFonts();

    g_lastFrameTime = NowSeconds();
    g_ready = true;
    LOGI("imgui ready, density=%.2f", density);
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    g_surfaceW = w;
    g_surfaceH = h;
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2((float)w, (float)h);
    io.DisplayFramebufferScale = ImVec2(1.0f, 1.0f);
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeRender(JNIEnv*, jobject) {
    if (!g_ready || g_surfaceW == 0 || g_surfaceH == 0) return;

    const double now = NowSeconds();
    const float dt = (float)(now - g_lastFrameTime);
    g_lastFrameTime = now;

    ImGuiIO& io = ImGui::GetIO();
    io.DeltaTime = std::clamp(dt, 1.0f / 1000.0f, 1.0f / 10.0f);

    // 函数图像的时间推进
    g_funcTime += io.DeltaTime;
    g_funcPhase += kPi2 * g_funcSpeed * io.DeltaTime;

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    DrawPanel();

    ImGui::Render();
    glViewport(0, 0, g_surfaceW, g_surfaceH);
    glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeDestroy(JNIEnv*, jobject) {
    if (!g_ready) return;
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext();
    g_ready = false;
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativePushSample(JNIEnv*, jobject, jint value) {
    const int idx = g_write.load(std::memory_order_relaxed);
    g_buffer[idx].store(value, std::memory_order_release);
    g_write.store((idx + 1) % kCapacity, std::memory_order_relaxed);
    g_written.fetch_add(1, std::memory_order_relaxed);
    g_lastAmp.store(value & 0xFF, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeSetStatus(JNIEnv*, jobject,
                                                                jint mode, jint strategy,
                                                                jint segmentMs,
                                                                jboolean useMaxAmp,
                                                                jfloat sampleHz) {
    g_mode.store(mode, std::memory_order_relaxed);
    g_strategy.store(strategy, std::memory_order_relaxed);
    g_segmentMs.store(segmentMs, std::memory_order_relaxed);
    g_useMaxAmp.store(useMaxAmp == JNI_TRUE ? 1 : 0, std::memory_order_relaxed);
    g_sampleHz.store(sampleHz, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeTouch(JNIEnv*, jobject,
                                                            jfloat x, jfloat y, jint action) {
    ImGuiIO& io = ImGui::GetIO();
    // MotionEvent: 0=DOWN 1=UP 2=MOVE
    io.AddMousePosEvent(x, y);
    io.AddMouseButtonEvent(0, action != 1);
}

/** 若函数参数被改动过，写入 out[4] = {type, amplitude, frequency, speed}，并清除脏标记。 */
JNIEXPORT jboolean JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeConsumeFunctionParams(JNIEnv* env, jobject,
                                                                            jfloatArray out) {
    // 拖动过程中只让图像跟着变，不重启马达；松手（或点选）后才上报
    if (g_funcEditing || !g_funcDirty) return JNI_FALSE;

    const jfloat values[4] = {(jfloat)g_funcType, g_funcAmp, g_funcFreq, g_funcSpeed};
    g_funcDirty = false;
    if (out != nullptr && env->GetArrayLength(out) >= 4) {
        env->SetFloatArrayRegion(out, 0, 4, values);
    }
    return JNI_TRUE;
}

/** 取走面板的拖动 / 缩放增量写入 out[4]，返回值 bit0 表示用户点了关闭。 */
JNIEXPORT jint JNICALL
Java_com_xxy_vibration_imgui_WaveformSurfaceView_nativeConsumePanelDelta(JNIEnv* env, jobject,
                                                                        jintArray out) {
    const jint delta[4] = {g_pendingDx, g_pendingDy, g_pendingDw, g_pendingDh};
    g_pendingDx = 0;
    g_pendingDy = 0;
    g_pendingDw = 0;
    g_pendingDh = 0;
    if (out != nullptr && env->GetArrayLength(out) >= 4) {
        env->SetIntArrayRegion(out, 0, 4, delta);
    }
    const jint flags = g_pendingClose ? 1 : 0;
    g_pendingClose = false;
    return flags;
}

}  // extern "C"
