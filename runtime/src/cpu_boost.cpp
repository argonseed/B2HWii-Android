#include "cpu_boost.h"

#include "runtime_log.h"
#include "thermal.h"

#if defined(__ANDROID__)
#include <android/api-level.h>
#include <dirent.h>
#include <dlfcn.h>
#include <sched.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#endif

namespace CpuBoost {

#if defined(__ANDROID__)
namespace {

constexpr int kMaxCpus = 32;
constexpr int64_t kFrameTargetNanos = 16'666'667;

long long MaxFreqKhz(int cpu) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* file = std::fopen(path, "r");
    if (file == nullptr) {
        return -1;
    }
    long long khz = -1;
    if (std::fscanf(file, "%lld", &khz) != 1) {
        khz = -1;
    }
    std::fclose(file);
    return khz;
}

// Phones mix core types (little, mid, prime). The scheduler starts a busy thread on a mid core and
// may keep it there, which on a Snapdragon 8 Gen 2 is the difference between 60 fps and 35 in a
// crowded battle. The game thread is the one that has to finish within the frame, so it is pinned to
// the fastest cluster, or, where the kernel refuses that (some vendors reject a prime-core-only mask),
// to every core but the slowest cluster. Everything else keeps the default placement.
cpu_set_t g_pinMask;
bool g_pinned = false;
int g_repins = 0;

bool PinTo(const long long* freq, long long minKhz, int known) {
    cpu_set_t set;
    CPU_ZERO(&set);
    int chosen = 0;
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        if (freq[cpu] >= minKhz) {
            CPU_SET(cpu, &set);
            ++chosen;
        }
    }
    if (chosen == 0 || chosen == known) {
        return false;
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        RT_LOGF(RT_TAG_RUNTIME, "cpu boost: %d cores from %lld MHz refused (%s)\n", chosen, minKhz / 1000,
                std::strerror(errno));
        return false;
    }
    RT_LOGF(RT_TAG_RUNTIME, "cpu boost: game thread %d on %d of %d cores (%lld MHz and up)\n", gettid(),
            chosen, known, minKhz / 1000);
    g_pinMask = set;
    g_pinned = true;
    return true;
}

// Helper threads that never hold the game thread up within a frame (the render worker queues GPU
// work a frame behind, the SDL audio callback is a few hundred microseconds) are kept off the game
// thread's cores, which on a phone with two big cores (Helio G81) are otherwise shared with them.
// The GX thread is not one: it is in the hint session instead and the scheduler places it (except on
// the Android 10 four-big-core phones of PinHelpers).
bool IsHelperThread(const char* name) {
    return std::strncmp(name, "FrameWorker", 11) == 0 || std::strncmp(name, "SDLAudio", 8) == 0;
}

bool ReadThreadName(int tid, char* name, size_t size) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
    FILE* file = std::fopen(path, "r");
    if (file == nullptr) {
        return false;
    }
    const bool ok = std::fgets(name, static_cast<int>(size), file) != nullptr;
    std::fclose(file);
    if (ok) {
        name[std::strcspn(name, "\n")] = '\0';
    }
    return ok;
}

// Calls visit(tid, name) for every thread of the process.
template <typename Visit>
void ForEachThread(Visit visit) {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) {
        return;
    }
    while (dirent* entry = readdir(dir)) {
        const int tid = std::atoi(entry->d_name);
        char name[32];
        if (tid > 0 && ReadThreadName(tid, name, sizeof(name))) {
            visit(tid, name);
        }
    }
    closedir(dir);
}

int g_helperPins = 0;

void PinHelpers() {
    static const bool s_enabled = [] {
        const char* value = std::getenv("BASARA_CPU_HELPERS");
        return value == nullptr || std::strcmp(value, "0") != 0;
    }();
    static const cpu_set_t others = [] {
        cpu_set_t set;
        CPU_ZERO(&set);
        for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
            if (!CPU_ISSET(cpu, &g_pinMask) && MaxFreqKhz(cpu) > 0) {
                CPU_SET(cpu, &set);
            }
        }
        return set;
    }();
    // Android 10 phones whose fast cluster has four cores or more (Snapdragon 660/665: 4x A73 + 4x A53)
    // keep the render workers and the GX thread on that cluster with the game thread, the way ARMSX2
    // confines every busy emulator thread to the performance tier when the tier has a core for each:
    // the game thread, two frame workers and the GX thread fit on four cores, and the other side is
    // the A53 cluster, where the render worker's encode left a realme 5 (Adreno 610) waiting 6-11 ms a
    // frame on it. Android 11+ is unchanged. BASARA_CPU_HELPERS=fast forces it, 0 turns placement off.
    static const bool s_shareFast = [] {
        const char* value = std::getenv("BASARA_CPU_HELPERS");
        if (value != nullptr && std::strcmp(value, "fast") == 0) {
            return true;
        }
        return CPU_COUNT(&g_pinMask) >= 4;
    }();
    if (!s_enabled || CPU_COUNT(&others) == 0) {
        return;
    }
    ForEachThread([&](int tid, const char* name) {
        const bool worker = std::strncmp(name, "FrameWorker", 11) == 0;
        const bool gx = std::strncmp(name, "GxThread", 8) == 0;
        const cpu_set_t* target = nullptr;
        if (s_shareFast && (worker || gx)) {
            target = &g_pinMask;
        } else if (IsHelperThread(name)) {
            target = &others;
        } else {
            return;
        }
        cpu_set_t current;
        if (sched_getaffinity(tid, sizeof(current), &current) == 0 && CPU_EQUAL(&current, target)) {
            return;
        }
        if (sched_setaffinity(tid, sizeof(*target), target) == 0 && ++g_helperPins <= 6) {
            RT_LOGF(RT_TAG_RUNTIME, "cpu boost: %s (%d) %s (%d cores)\n", name, tid,
                    target == &g_pinMask ? "shares the game thread's cluster" : "kept off the game thread's cores",
                    CPU_COUNT(target));
        }
    });
}

void UpdateHintThreads();

// Android resets a thread's affinity to its cpuset when the app moves between cgroups (top-app,
// foreground): the logs of a Dimensity 7300 and a Snapdragon 8 Gen 2 show the game thread back on
// little cores minutes after a successful pin. Check about once a second and pin again; the helper
// threads and the hint session's thread list are refreshed at the same time.
void KeepPinned() {
    static int s_frames = 0;
    if (++s_frames < 60) {
        return;
    }
    s_frames = 0;
    UpdateHintThreads();
    if (!g_pinned) {
        return;
    }
    PinHelpers();
    cpu_set_t current;
    if (sched_getaffinity(0, sizeof(current), &current) != 0 || CPU_EQUAL(&current, &g_pinMask)) {
        return;
    }
    if (sched_setaffinity(0, sizeof(g_pinMask), &g_pinMask) != 0) {
        return;
    }
    if (++g_repins <= 3 || g_repins % 100 == 0) {
        RT_LOGF(RT_TAG_RUNTIME, "cpu boost: affinity was reset (%d cores allowed), pinned again (%d times)\n",
                CPU_COUNT(&current), g_repins);
    }
}

void PinToFastestCores() {
    long long freq[kMaxCpus];
    long long fastest = -1, slowest = -1;
    int known = 0;
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        freq[cpu] = MaxFreqKhz(cpu);
        if (freq[cpu] > 0) {
            ++known;
            fastest = freq[cpu] > fastest ? freq[cpu] : fastest;
            slowest = slowest < 0 || freq[cpu] < slowest ? freq[cpu] : slowest;
        }
    }
    if (known < 2 || fastest == slowest) {
        return;  // one core type: nothing to choose
    }
    if (PinTo(freq, fastest, known)) {
        return;
    }
    PinTo(freq, slowest + 1, known);
}

// ADPF (Android 13+), looked up at run time so older systems still load the library.
using GetManagerFn = void* (*)();
using CreateSessionFn = void* (*)(void*, const int32_t*, size_t, int64_t);
using ReportFn = int (*)(void*, int64_t);
using SetThreadsFn = int (*)(void*, const int32_t*, size_t);
using CloseFn = void (*)(void*);

void* g_manager = nullptr;
void* g_session = nullptr;
CreateSessionFn g_create = nullptr;
ReportFn g_report = nullptr;
SetThreadsFn g_setThreads = nullptr;  // Android 14+
CloseFn g_close = nullptr;
int32_t g_gameTid = 0;
int32_t g_gxTid = 0;  // the GX thread in the session, 0 when it is not

int32_t FindThread(const char* wanted) {
    int32_t found = 0;
    ForEachThread([&](int tid, const char* name) {
        if (found == 0 && std::strcmp(name, wanted) == 0) {
            found = tid;
        }
    });
    return found;
}

// The GX thread (BASARA_GX_THREAD) does the other half of each frame's draw work, so it shares the
// game thread's session: the boost then covers both instead of only the thread that reports.
void UpdateHintThreads() {
    if (g_manager == nullptr || g_create == nullptr) {
        return;
    }
    const int32_t gx = FindThread("GxThread");
    if (g_session != nullptr && gx == g_gxTid) {
        return;
    }
    const int32_t tids[2] = {g_gameTid, gx};
    const size_t count = gx != 0 ? 2 : 1;
    if (g_session != nullptr && g_setThreads != nullptr) {
        if (g_setThreads(g_session, tids, count) != 0) {
            return;
        }
    } else {
        if (g_session != nullptr && g_close != nullptr) {
            g_close(g_session);
        }
        g_session = g_create(g_manager, tids, count, kFrameTargetNanos);
    }
    g_gxTid = g_session != nullptr ? gx : 0;
    if (g_session == nullptr) {
        g_create = nullptr;  // refused: do not ask again every second
    }
    RT_LOGF(RT_TAG_RUNTIME, "cpu boost: performance hint session %s (game thread%s)\n",
            g_session ? "on" : "unavailable", g_gxTid ? " + GX thread" : "");
}

void CreateHintSession() {
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (lib == nullptr) {
        return;
    }
    const auto getManager = reinterpret_cast<GetManagerFn>(dlsym(lib, "APerformanceHint_getManager"));
    g_create = reinterpret_cast<CreateSessionFn>(dlsym(lib, "APerformanceHint_createSession"));
    g_report = reinterpret_cast<ReportFn>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    g_setThreads = reinterpret_cast<SetThreadsFn>(dlsym(lib, "APerformanceHint_setThreads"));
    g_close = reinterpret_cast<CloseFn>(dlsym(lib, "APerformanceHint_closeSession"));
    if (getManager == nullptr || g_create == nullptr || g_report == nullptr) {
        RT_LOGF(RT_TAG_RUNTIME, "cpu boost: no performance hint API on this system\n");
        g_create = nullptr;
        return;
    }
    g_manager = getManager();
    g_gameTid = gettid();
    UpdateHintThreads();
}

bool Enabled() {
    const char* value = std::getenv("BASARA_CPU_BOOST");
    return value == nullptr || std::strcmp(value, "0") != 0;
}

// SetEnabled's request, taken on the game thread: -1 none, 0 off, 1 on.
std::atomic<int> g_request{-1};

// Boost off while playing (Sustained Performance turned on): every thread back on every core, and
// the hint session closed, as if the game had started with BASARA_CPU_BOOST=0.
void Unboost() {
    cpu_set_t all;
    CPU_ZERO(&all);
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        if (MaxFreqKhz(cpu) > 0) {
            CPU_SET(cpu, &all);
        }
    }
    if (CPU_COUNT(&all) > 0) {
        sched_setaffinity(0, sizeof(all), &all);
        ForEachThread([&](int tid, const char* name) {
            if (IsHelperThread(name) || std::strncmp(name, "FrameWorker", 11) == 0 ||
                std::strncmp(name, "GxThread", 8) == 0) {
                sched_setaffinity(tid, sizeof(all), &all);
            }
        });
    }
    g_pinned = false;
    if (g_session != nullptr && g_close != nullptr) {
        g_close(g_session);
    }
    g_session = nullptr;
    g_gxTid = 0;
    RT_LOGF(RT_TAG_RUNTIME, "cpu boost: off (Sustained Performance)\n");
}

} // namespace

void SetEnabled(bool enabled) {
    g_request.store(enabled ? 1 : 0, std::memory_order_release);
}

void ReportFrameWork(uint64_t workNanos) {
    static bool s_started = false;
    static bool s_active = false;
    if (!s_started) {
        s_started = true;
        Thermal::Start();
        if (!Enabled()) {
            RT_LOGF(RT_TAG_RUNTIME, "cpu boost: off (BASARA_CPU_BOOST=0)\n");
        } else {
            PinToFastestCores();
            CreateHintSession();
            s_active = true;
        }
    }
    const int request = g_request.exchange(-1, std::memory_order_acq_rel);
    if (request == 0 && s_active) {
        Unboost();
        s_active = false;
    } else if (request == 1 && !s_active) {
        PinToFastestCores();
        if (g_manager != nullptr) {
            UpdateHintThreads();
        } else {
            CreateHintSession();
        }
        s_active = true;
        RT_LOGF(RT_TAG_RUNTIME, "cpu boost: on again (Sustained Performance off)\n");
    }
    if (s_active) {
        KeepPinned();
    }
    if (s_active && g_session != nullptr && workNanos > 0) {
        g_report(g_session, static_cast<int64_t>(workNanos));
    }
}
#else
void SetEnabled(bool) {}
void ReportFrameWork(uint64_t) {}
#endif

} // namespace CpuBoost
