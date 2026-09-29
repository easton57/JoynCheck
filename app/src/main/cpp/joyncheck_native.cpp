// Runs inside the Shizuku user-service process (UID 2000 / shell), exactly like JoynCon's own
// bridge. Each probe exercises one kernel operation JoynCon's merge depends on — opening
// /dev/input/eventX, EVIOCGID/EVIOCGBIT, EVIOCGRAB, and creating a /dev/uinput gamepad — so a
// pass here means the same call succeeds for JoynCon on this phone's SELinux policy.

#include <jni.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <cerrno>
#include <cstring>
#include <cmath>
#include <string>
#include <sstream>
#include <map>
#include <mutex>
#include <thread>
#include <atomic>

#define LOG_TAG "joyncheck_native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int VENDOR_NINTENDO = 0x057e;
constexpr int PRODUCT_JOYCON_L = 0x2006;
constexpr int PRODUCT_JOYCON_R = 0x2007;
constexpr int ROLE_LEFT = 0;
constexpr int ROLE_RIGHT = 1;

// Must match CheckIds in Kotlin: how the app recognizes the test gamepad's events.
constexpr int TEST_VENDOR = 0x0ee2;
constexpr int TEST_PRODUCT = 0x00c7;

std::string json_escape(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        if (c == '"' || c == '\\') out += '\\';
        if ((unsigned char) c < 0x20) continue;
        out += c;
    }
    return out;
}

const char *jbool(bool b) { return b ? "true" : "false"; }

std::string errno_hint(int err) {
    std::string msg = strerror(err);
    if (err == EBUSY) msg += " (another process already grabbed it — is JoynCon's merge running?)";
    if (err == EACCES || err == EPERM) msg += " (blocked by this phone's SELinux policy)";
    return msg;
}

struct AbsRange {
    int minVal = -32767;
    int maxVal = 32767;
};

struct FoundJoyCon {
    int fd = -1;
    std::string name;
    int keyCount = 0;
    int absCount = 0;
    std::map<int, AbsRange> abs;
};

struct ScanStats {
    int opened = 0;
    int denied = 0;
    int ioctlDenied = 0;
    int nintendoNodes = 0;
    std::string dirError;
};

int count_bits(const unsigned char *bits, size_t len) {
    int n = 0;
    for (size_t i = 0; i < len; i++) n += __builtin_popcount(bits[i]);
    return n;
}

/**
 * Same identification JoynCon uses: match vendor/product, then skip the Joy-Con's separate IMU
 * node (same IDs, no EV_KEY bits). Leaves the returned fd open and ungrabbed.
 */
bool find_joycon(int role, FoundJoyCon &out, ScanStats &stats) {
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        stats.dirError = std::string("opendir /dev/input: ") + errno_hint(errno);
        return false;
    }
    int wantProduct = (role == ROLE_LEFT) ? PRODUCT_JOYCON_L : PRODUCT_JOYCON_R;
    bool found = false;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
        std::string fname = entry->d_name;
        if (fname.rfind("event", 0) != 0) continue;
        int fd = open(("/dev/input/" + fname).c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            if (errno == EACCES || errno == EPERM) stats.denied++;
            continue;
        }
        stats.opened++;

        struct input_id id{};
        if (ioctl(fd, EVIOCGID, &id) < 0) {
            if (errno == EACCES || errno == EPERM) stats.ioctlDenied++;
            close(fd);
            continue;
        }
        if (id.vendor != VENDOR_NINTENDO || id.product != wantProduct) {
            close(fd);
            continue;
        }
        stats.nintendoNodes++;

        unsigned char keybits[96] = {0};
        unsigned char absbits[8] = {0};
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
        int keyCount = count_bits(keybits, sizeof(keybits));
        if (keyCount == 0) { // IMU node
            close(fd);
            continue;
        }

        char nameBuf[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(nameBuf) - 1), nameBuf) < 0) {
            strncpy(nameBuf, "Joy-Con", sizeof(nameBuf) - 1);
        }
        out.fd = fd;
        out.name = nameBuf;
        out.keyCount = keyCount;
        out.absCount = count_bits(absbits, sizeof(absbits));
        for (int code : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) {
            struct input_absinfo info{};
            if (ioctl(fd, EVIOCGABS(code), &info) >= 0 && info.maximum > info.minimum) {
                out.abs[code] = {info.minimum, info.maximum};
            }
        }
        found = true;
        break;
    }
    closedir(dir);
    return found;
}

// ---- Test uinput gamepad ------------------------------------------------------------------------

std::mutex g_uinput_mutex;
int g_uinput_fd = -1;

void emit_locked(int type, int code, int value) {
    if (g_uinput_fd < 0) return;
    struct input_event ev{};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ssize_t written = write(g_uinput_fd, &ev, sizeof(ev));
    (void) written;
}

void emit_and_sync(int type, int code, int value) {
    std::lock_guard<std::mutex> lock(g_uinput_mutex);
    emit_locked(type, code, value);
    emit_locked(EV_SYN, SYN_REPORT, 0);
}

/** Mirrors JoynCon's virtual gamepad capabilities, so the same device class gets tested. */
std::string create_test_device() {
    std::lock_guard<std::mutex> lock(g_uinput_mutex);
    if (g_uinput_fd >= 0) return "";

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return std::string("open /dev/uinput: ") + errno_hint(errno);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int code : {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
                     BTN_SELECT, BTN_START, BTN_MODE, BTN_Z, BTN_THUMBL, BTN_THUMBR,
                     BTN_TRIGGER_HAPPY1, BTN_TRIGGER_HAPPY2, BTN_TRIGGER_HAPPY3, BTN_TRIGGER_HAPPY4}) {
        ioctl(fd, UI_SET_KEYBIT, code);
    }
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    for (int code : {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_HAT0X, ABS_HAT0Y, ABS_Z, ABS_RZ}) {
        ioctl(fd, UI_SET_ABSBIT, code);
    }

    struct uinput_setup usetup{};
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = TEST_VENDOR;
    usetup.id.product = TEST_PRODUCT;
    usetup.id.version = 1;
    strncpy(usetup.name, "JoynCon Compatibility Test", sizeof(usetup.name) - 1);
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        std::string err = std::string("UI_DEV_SETUP: ") + errno_hint(errno);
        close(fd);
        return err;
    }

    auto setup_abs = [&](int code, int minV, int maxV, int flat) {
        struct uinput_abs_setup asetup{};
        asetup.code = code;
        asetup.absinfo.minimum = minV;
        asetup.absinfo.maximum = maxV;
        asetup.absinfo.flat = flat;
        ioctl(fd, UI_ABS_SETUP, &asetup);
    };
    for (int code : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) setup_abs(code, -32767, 32767, 1000);
    setup_abs(ABS_HAT0X, -1, 1, 0);
    setup_abs(ABS_HAT0Y, -1, 1, 0);
    setup_abs(ABS_Z, 0, 255, 0);
    setup_abs(ABS_RZ, 0, 255, 0);

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        std::string err = std::string("UI_DEV_CREATE: ") + errno_hint(errno);
        close(fd);
        return err;
    }
    g_uinput_fd = fd;
    return "";
}

void destroy_test_device() {
    std::lock_guard<std::mutex> lock(g_uinput_mutex);
    if (g_uinput_fd >= 0) {
        ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
    }
    g_uinput_fd = -1;
}

// ---- Live test ----------------------------------------------------------------------------------

struct LiveCtx {
    int fd = -1;
    std::map<int, AbsRange> abs;
    std::atomic<bool> button{false};
    std::atomic<bool> stick{false};
    std::thread th;
};

LiveCtx g_live[2];
std::atomic<bool> g_live_running{false};
std::mutex g_live_mutex; // serializes start/stop

/**
 * Forwards each Joy-Con's input to the test gamepad, so the app can confirm the whole path:
 * raw read → virtual device → Android's normal dispatch. Any left button becomes BTN_SOUTH, any
 * right button BTN_EAST; sticks become ABS_X/Y (left) and ABS_RX/RY (right).
 */
void live_reader(int role) {
    LiveCtx &ctx = g_live[role];
    int outKey = (role == ROLE_LEFT) ? BTN_SOUTH : BTN_EAST;
    while (g_live_running.load()) {
        struct pollfd pfd{};
        pfd.fd = ctx.fd;
        pfd.events = POLLIN;
        int rc = poll(&pfd, 1, 200);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (rc == 0) continue;
        if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) break;

        struct input_event ev{};
        while (read(ctx.fd, &ev, sizeof(ev)) == (ssize_t) sizeof(ev)) {
            if (ev.type == EV_KEY && ev.value != 2) {
                if (ev.value == 1) ctx.button = true;
                emit_and_sync(EV_KEY, outKey, ev.value ? 1 : 0);
            } else if (ev.type == EV_ABS) {
                auto it = ctx.abs.find(ev.code);
                if (it == ctx.abs.end()) continue;
                double norm = (double) (ev.value - it->second.minVal) / (it->second.maxVal - it->second.minVal);
                double centered = norm * 2.0 - 1.0;
                if (std::fabs(centered) > 0.5) ctx.stick = true;
                bool isX = (ev.code == ABS_X || ev.code == ABS_RX);
                int outCode = (role == ROLE_LEFT) ? (isX ? ABS_X : ABS_Y) : (isX ? ABS_RX : ABS_RY);
                emit_and_sync(EV_ABS, outCode, (int) std::lround(centered * 32767.0));
            }
        }
    }
}

void stop_live_test() {
    std::lock_guard<std::mutex> lock(g_live_mutex);
    g_live_running = false;
    for (auto &ctx : g_live) {
        if (ctx.th.joinable()) ctx.th.join();
        if (ctx.fd >= 0) {
            ioctl(ctx.fd, EVIOCGRAB, 0);
            close(ctx.fd);
        }
        ctx.fd = -1;
        ctx.abs.clear();
        ctx.button = false;
        ctx.stick = false;
    }
    destroy_test_device();
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeProbeEvdev(JNIEnv *env, jobject) {
    int total = 0, opened = 0, denied = 0;
    std::string dirError;
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        dirError = std::string("opendir /dev/input: ") + errno_hint(errno);
    } else {
        struct dirent *entry;
        while ((entry = readdir(dir)) != nullptr) {
            std::string fname = entry->d_name;
            if (fname.rfind("event", 0) != 0) continue;
            total++;
            int fd = open(("/dev/input/" + fname).c_str(), O_RDONLY | O_NONBLOCK);
            if (fd >= 0) {
                opened++;
                close(fd);
            } else if (errno == EACCES || errno == EPERM) {
                denied++;
            }
        }
        closedir(dir);
    }
    std::ostringstream out;
    out << "{\"total\":" << total << ",\"opened\":" << opened << ",\"denied\":" << denied
        << ",\"error\":\"" << json_escape(dirError) << "\"}";
    return to_jstring(env, out.str());
}

JNIEXPORT jstring JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeCreateTestDevice(JNIEnv *env, jobject) {
    return to_jstring(env, create_test_device());
}

JNIEXPORT void JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeDestroyTestDevice(JNIEnv *, jobject) {
    destroy_test_device();
}

JNIEXPORT jstring JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeProbeJoyCon(JNIEnv *env, jobject, jstring roleStr) {
    int role = (jstr(env, roleStr) == "right") ? ROLE_RIGHT : ROLE_LEFT;
    FoundJoyCon jc;
    ScanStats stats;
    bool found = find_joycon(role, jc, stats);
    bool grabOk = false;
    std::string grabError;
    if (found) {
        if (ioctl(jc.fd, EVIOCGRAB, 1) == 0) {
            grabOk = true;
            ioctl(jc.fd, EVIOCGRAB, 0);
        } else {
            grabError = std::string("EVIOCGRAB: ") + errno_hint(errno);
        }
        close(jc.fd);
    }
    std::ostringstream out;
    out << "{\"found\":" << jbool(found)
        << ",\"name\":\"" << json_escape(jc.name) << "\""
        << ",\"keyCount\":" << jc.keyCount
        << ",\"absCount\":" << jc.absCount
        << ",\"hasStick\":" << jbool(!jc.abs.empty())
        << ",\"grabOk\":" << jbool(grabOk)
        << ",\"grabError\":\"" << json_escape(grabError) << "\""
        << ",\"opened\":" << stats.opened
        << ",\"denied\":" << stats.denied
        << ",\"ioctlDenied\":" << stats.ioctlDenied
        << ",\"nintendoNodes\":" << stats.nintendoNodes
        << ",\"dirError\":\"" << json_escape(stats.dirError) << "\"}";
    return to_jstring(env, out.str());
}

JNIEXPORT jstring JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeStartLiveTest(JNIEnv *env, jobject) {
    stop_live_test();
    std::lock_guard<std::mutex> lock(g_live_mutex);

    std::string uinputError = create_test_device();
    std::string errors[2];
    if (uinputError.empty()) {
        for (int role = 0; role < 2; role++) {
            FoundJoyCon jc;
            ScanStats stats;
            if (!find_joycon(role, jc, stats)) {
                errors[role] = "not found";
                continue;
            }
            if (ioctl(jc.fd, EVIOCGRAB, 1) < 0) {
                errors[role] = std::string("EVIOCGRAB: ") + errno_hint(errno);
                close(jc.fd);
                continue;
            }
            g_live[role].fd = jc.fd;
            g_live[role].abs = jc.abs;
        }
        g_live_running = true;
        for (int role = 0; role < 2; role++) {
            if (g_live[role].fd >= 0) g_live[role].th = std::thread(live_reader, role);
        }
    }

    std::ostringstream out;
    out << "{\"uinputError\":\"" << json_escape(uinputError) << "\""
        << ",\"leftError\":\"" << json_escape(errors[ROLE_LEFT]) << "\""
        << ",\"rightError\":\"" << json_escape(errors[ROLE_RIGHT]) << "\"}";
    return to_jstring(env, out.str());
}

JNIEXPORT jstring JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativePollLiveTest(JNIEnv *env, jobject) {
    std::ostringstream out;
    out << "{\"leftButton\":" << jbool(g_live[ROLE_LEFT].button.load())
        << ",\"leftStick\":" << jbool(g_live[ROLE_LEFT].stick.load())
        << ",\"rightButton\":" << jbool(g_live[ROLE_RIGHT].button.load())
        << ",\"rightStick\":" << jbool(g_live[ROLE_RIGHT].stick.load()) << "}";
    return to_jstring(env, out.str());
}

JNIEXPORT void JNICALL
Java_com_eastonseidel_joyncheck_bridge_CheckBridgeService_nativeStopLiveTest(JNIEnv *, jobject) {
    stop_live_test();
}

} // extern "C"
