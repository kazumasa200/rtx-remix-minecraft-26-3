// SPDX-License-Identifier: Apache-2.0
//
// The mod's Vulkan layer for the RTX Remix runtime. The mod calls rrp_arm before the runtime loads;
// rrp_arm puts this layer on the loader's search path and enabled list for the process, and the
// loader then loads this same DLL into the runtime's instance. The layer sees every
// vkCreateShaderModule the runtime makes.
//
// The layer does no pattern matching and no SPIR-V editing. The build tool (tools/src/rtx_tools/
// runtime_patch) writes validated replacements into patches/ beside this DLL as deltas against the
// runtime's own modules, with a manifest. rrp_arm reads the manifest and checks every entry against the
// bytes of the d3d9.dll that sits beside it; a patch group applies only when every one of its modules
// matched, so a group is never half applied. A module two groups edit has one delta per combination of
// them, and the layer takes the one for the groups that applied.
//
// Keys are SHA-256 over the module with every OpDecorate Binding and DescriptorSet literal zeroed:
// dxvk rewrites binding literals before vkCreateShaderModule (17 in the composite module), so the
// module that arrives differs from the embedded one there and only there. A delta copies the arriving
// module's words wherever the replacement keeps the original's, so dxvk's literals stay as it wrote them.
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <windows.h>
#include <bcrypt.h>

#pragma comment(lib, "bcrypt.lib")

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#define RRP_EXPORT extern "C" __declspec(dllexport)

namespace {

constexpr const char* LAYER_NAME = "VK_LAYER_RTXREMIX_spirv_patch";
constexpr const wchar_t* LAYER_NAME_W = L"VK_LAYER_RTXREMIX_spirv_patch";
constexpr const wchar_t* MANIFEST_NAME = L"VkLayer_rtxremix_spirv_patch.json";

std::atomic<uint32_t> g_armed{0}, g_hooked{0}, g_devices{0}, g_seen{0}, g_replaced{0}, g_fellBack{0};

struct InstanceChain {
    PFN_vkGetInstanceProcAddr next;
    PFN_vkDestroyInstance destroy;
};
struct DeviceChain {
    PFN_vkGetDeviceProcAddr next;
    PFN_vkDestroyDevice destroy;
    PFN_vkCreateShaderModule createShaderModule;
};

std::mutex g_chainLock;
std::unordered_map<void*, InstanceChain> g_instanceChains;
std::unordered_map<void*, DeviceChain> g_deviceChains;

void* dispatchKey(const void* handle) { return *(void* const*)handle; }

// ---- keys -----------------------------------------------------------------------------------

constexpr uint32_t OP_DECORATE = 71;
constexpr uint32_t DECORATION_BINDING = 33;
constexpr uint32_t DECORATION_DESCRIPTOR_SET = 34;

struct Key {
    uint8_t bytes[32];
    bool operator==(const Key& other) const { return memcmp(bytes, other.bytes, 32) == 0; }
};
struct KeyHash {
    size_t operator()(const Key& k) const {
        size_t h;
        memcpy(&h, k.bytes, sizeof h);
        return h;
    }
};

bool sha256(const void* data, size_t bytes, uint8_t out[32]) {
    return BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, (PUCHAR)data, (ULONG)bytes, out, 32) == 0;
}

// Calls visit(wordIndex) for the literal word of every OpDecorate Binding/DescriptorSet. False when
// the module is not well formed.
template <typename Visit>
bool forEachBindingLiteral(const uint32_t* code, size_t words, Visit visit) {
    if (words < 5 || code[0] != 0x07230203) return false;
    for (size_t i = 5; i < words;) {
        uint32_t count = code[i] >> 16, op = code[i] & 0xffff;
        if (count == 0 || i + count > words) return false;
        if (op == OP_DECORATE && count >= 4 &&
            (code[i + 2] == DECORATION_BINDING || code[i + 2] == DECORATION_DESCRIPTOR_SET)) {
            visit(i);
        }
        i += count;
    }
    return true;
}

// The one key rule; rrp_key gives the build tool the same function.
bool moduleKey(const uint32_t* code, size_t words, Key& out) {
    std::vector<uint32_t> scratch(code, code + words);
    if (!forEachBindingLiteral(scratch.data(), words, [&](size_t i) { scratch[i + 3] = 0; })) return false;
    return sha256(scratch.data(), words * 4, out.bytes);
}

// ---- the published patch set ------------------------------------------------------------------

struct Replacement {
    std::wstring file;
    std::vector<uint32_t> delta;  // loaded at arm time
};

// Immutable once published: vkCreateShaderModule reads it from the runtime's compile threads.
struct PatchSet {
    std::unordered_map<Key, Replacement, KeyHash> byKey;
    std::set<size_t> sizes;  // a module of another size is never hashed
};

std::atomic<const PatchSet*> g_set{nullptr};

// ---- deltas -----------------------------------------------------------------------------------

// magic, version, original words, output words, then operations of (kind << 28 | count): COPY count
// words of the arriving module, SKIP count of them, or WORDS count words that follow. spirv.py writes them.
constexpr uint32_t DELTA_MAGIC = 0x44505252, DELTA_VERSION = 1;
constexpr uint32_t DELTA_COPY = 1u << 28, DELTA_SKIP = 2u << 28, DELTA_WORDS = 3u << 28;

// The one applier: the build tool checks every delta through rrp_apply. False when the delta does not fit.
bool applyDelta(const uint32_t* in, size_t words, const uint32_t* delta, size_t deltaWords, std::vector<uint32_t>& out) {
    if (deltaWords < 4 || delta[0] != DELTA_MAGIC || delta[1] != DELTA_VERSION || delta[2] != words) return false;
    out.clear();
    out.reserve(delta[3]);
    size_t i = 4, cursor = 0;
    while (i < deltaWords) {
        uint32_t kind = delta[i] & 0xF0000000u, count = delta[i] & 0x0FFFFFFFu;
        i++;
        if (kind == DELTA_COPY) {
            if (cursor + count > words) return false;
            out.insert(out.end(), in + cursor, in + cursor + count);
            cursor += count;
        } else if (kind == DELTA_SKIP) {
            cursor += count;
        } else if (kind == DELTA_WORDS) {
            if (i + count > deltaWords) return false;
            out.insert(out.end(), delta + i, delta + i + count);
            i += count;
        } else {
            return false;
        }
    }
    return out.size() == delta[3];
}

// ---- layer entry points -----------------------------------------------------------------------

VKAPI_ATTR VkResult VKAPI_CALL CreateShaderModule(VkDevice device, const VkShaderModuleCreateInfo* info,
                                                  const VkAllocationCallbacks* alloc, VkShaderModule* out) {
    PFN_vkCreateShaderModule next;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        next = g_deviceChains.at(dispatchKey(device)).createShaderModule;
    }
    g_seen++;
    const PatchSet* set = g_set.load(std::memory_order_acquire);
    if (set == nullptr || !set->sizes.count(info->codeSize)) return next(device, info, alloc, out);
    Key key;
    if (!moduleKey(info->pCode, info->codeSize / 4, key)) return next(device, info, alloc, out);
    auto hit = set->byKey.find(key);
    if (hit == set->byKey.end()) return next(device, info, alloc, out);
    std::vector<uint32_t> code;
    const std::vector<uint32_t>& delta = hit->second.delta;
    if (!applyDelta(info->pCode, info->codeSize / 4, delta.data(), delta.size(), code)) {
        g_fellBack++;
        return next(device, info, alloc, out);
    }
    VkShaderModuleCreateInfo patched = *info;
    patched.pCode = code.data();
    patched.codeSize = code.size() * 4;
    VkResult result = next(device, &patched, alloc, out);
    if (result == VK_SUCCESS) {
        g_replaced++;
        return result;
    }
    // The driver refused the replacement: keep the runtime working, unpatched.
    g_fellBack++;
    return next(device, info, alloc, out);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char* name);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetInstanceProcAddr(VkInstance instance, const char* name);

VKAPI_ATTR VkResult VKAPI_CALL CreateInstance(const VkInstanceCreateInfo* info, const VkAllocationCallbacks* alloc,
                                              VkInstance* out) {
    auto* link = (VkLayerInstanceCreateInfo*)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO)) {
        link = (VkLayerInstanceCreateInfo*)link->pNext;
    }
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    auto create = (PFN_vkCreateInstance)next(VK_NULL_HANDLE, "vkCreateInstance");
    VkResult result = create(info, alloc, out);
    if (result != VK_SUCCESS) return result;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        g_instanceChains[dispatchKey(*out)] =
            InstanceChain{next, (PFN_vkDestroyInstance)next(*out, "vkDestroyInstance")};
    }
    g_hooked = 1;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL DestroyInstance(VkInstance instance, const VkAllocationCallbacks* alloc) {
    PFN_vkDestroyInstance destroy;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        auto it = g_instanceChains.find(dispatchKey(instance));
        if (it == g_instanceChains.end()) return;
        destroy = it->second.destroy;
        g_instanceChains.erase(it);
    }
    destroy(instance, alloc);
}

VKAPI_ATTR VkResult VKAPI_CALL CreateDevice(VkPhysicalDevice gpu, const VkDeviceCreateInfo* info,
                                            const VkAllocationCallbacks* alloc, VkDevice* out) {
    auto* link = (VkLayerDeviceCreateInfo*)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO)) {
        link = (VkLayerDeviceCreateInfo*)link->pNext;
    }
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr nextInstance = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr nextDevice = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    auto create = (PFN_vkCreateDevice)nextInstance(VK_NULL_HANDLE, "vkCreateDevice");
    VkResult result = create(gpu, info, alloc, out);
    if (result != VK_SUCCESS) return result;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        g_deviceChains[dispatchKey(*out)] =
            DeviceChain{nextDevice, (PFN_vkDestroyDevice)nextDevice(*out, "vkDestroyDevice"),
                        (PFN_vkCreateShaderModule)nextDevice(*out, "vkCreateShaderModule")};
    }
    g_devices++;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks* alloc) {
    PFN_vkDestroyDevice destroy;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        auto it = g_deviceChains.find(dispatchKey(device));
        if (it == g_deviceChains.end()) return;
        destroy = it->second.destroy;
        g_deviceChains.erase(it);
    }
    destroy(device, alloc);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char* name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)DestroyDevice;
    if (!strcmp(name, "vkCreateShaderModule")) return (PFN_vkVoidFunction)CreateShaderModule;
    PFN_vkGetDeviceProcAddr next;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        auto it = g_deviceChains.find(dispatchKey(device));
        if (it == g_deviceChains.end()) return nullptr;
        next = it->second.next;
    }
    return next(device, name);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetInstanceProcAddr(VkInstance instance, const char* name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)GetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)CreateDevice;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)DestroyDevice;
    if (!strcmp(name, "vkCreateShaderModule")) return (PFN_vkVoidFunction)CreateShaderModule;
    if (instance == VK_NULL_HANDLE) return nullptr;
    PFN_vkGetInstanceProcAddr next;
    {
        std::lock_guard<std::mutex> lock(g_chainLock);
        auto it = g_instanceChains.find(dispatchKey(instance));
        if (it == g_instanceChains.end()) return nullptr;
        next = it->second.next;
    }
    return next(instance, name);
}

// ---- arming -----------------------------------------------------------------------------------

std::wstring thisDirectory() {
    HMODULE self = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)&thisDirectory, &self);
    wchar_t path[MAX_PATH];
    DWORD n = GetModuleFileNameW(self, path, MAX_PATH);
    std::wstring dir(path, n);
    return dir.substr(0, dir.find_last_of(L"\\/"));
}

bool readFile(const std::wstring& path, uint64_t offset, size_t bytes, std::vector<uint8_t>& out) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER at;
    at.QuadPart = (LONGLONG)offset;
    out.resize(bytes);
    DWORD got = 0;
    bool ok = SetFilePointerEx(f, at, nullptr, FILE_BEGIN) && ReadFile(f, out.data(), (DWORD)bytes, &got, nullptr) && got == bytes;
    CloseHandle(f);
    return ok;
}

bool parseKey(const std::string& hex, Key& out) {
    if (hex.size() != 64) return false;
    for (int i = 0; i < 32; i++) out.bytes[i] = (uint8_t)strtoul(hex.substr(i * 2, 2).c_str(), nullptr, 16);
    return true;
}

std::wstring widen(const std::string& s) { return std::wstring(s.begin(), s.end()); }

// manifest.tsv, written by the build tool:
//   #format 2
//   <key hex64> \t <file offset in d3d9.dll> \t <original bytes> \t <groups, comma-separated> \t <delta file>
// A module two groups edit has a line per combination of them. A group is active when every line naming
// it matched the runtime file and its delta loaded; a module then takes the line whose groups are exactly
// its active ones. Returns the published set (or nullptr) and appends one report line per group.
const PatchSet* loadAndVerify(const std::wstring& dir, std::string& report) {
    std::vector<uint8_t> manifestBytes;
    std::wstring manifestPath = dir + L"\\patches\\manifest.tsv";
    WIN32_FILE_ATTRIBUTE_DATA attrs;
    if (!GetFileAttributesExW(manifestPath.c_str(), GetFileExInfoStandard, &attrs) ||
        !readFile(manifestPath, 0, attrs.nFileSizeLow, manifestBytes)) {
        report += "; no patch set";
        return nullptr;
    }
    struct Entry {
        Key key;
        uint64_t offset;
        size_t bytes;
        std::set<std::string> groups;
        std::string file;
        std::vector<uint32_t> delta;
        bool ok = false;
    };
    std::vector<Entry> entries;
    std::map<std::string, bool> groups;  // group -> every line naming it verified
    std::istringstream lines(std::string(manifestBytes.begin(), manifestBytes.end()));
    std::string line;
    bool formatSeen = false;
    while (std::getline(lines, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line == "#format 2") formatSeen = true;
        if (line.empty() || line[0] == '#') continue;
        std::istringstream fields(line);
        std::string key, offset, bytes, groupList, file;
        if (!std::getline(fields, key, '\t') || !std::getline(fields, offset, '\t') || !std::getline(fields, bytes, '\t') ||
            !std::getline(fields, groupList, '\t') || !std::getline(fields, file, '\t')) {
            report += "; malformed manifest line";
            return nullptr;
        }
        Entry e;
        if (!parseKey(key, e.key)) {
            report += "; malformed key";
            return nullptr;
        }
        e.offset = _strtoui64(offset.c_str(), nullptr, 10);
        e.bytes = (size_t)_strtoui64(bytes.c_str(), nullptr, 10);
        std::istringstream names(groupList);
        for (std::string g; std::getline(names, g, ',');) {
            if (!g.empty()) e.groups.insert(g);
        }
        if (e.groups.empty()) {
            report += "; manifest line without a group";
            return nullptr;
        }
        e.file = file;
        for (auto& g : e.groups) groups.emplace(g, true);
        entries.push_back(std::move(e));
    }
    if (!formatSeen) {
        report += "; manifest is not format 2";
        return nullptr;
    }
    std::wstring runtime = dir + L"\\d3d9.dll";
    for (auto& e : entries) {
        std::vector<uint8_t> original, delta;
        Key found;
        std::wstring path = dir + L"\\patches\\" + widen(e.file);
        WIN32_FILE_ATTRIBUTE_DATA a;
        e.ok = e.bytes % 4 == 0 && readFile(runtime, e.offset, e.bytes, original) &&
               moduleKey((const uint32_t*)original.data(), e.bytes / 4, found) && found == e.key &&
               GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &a) && a.nFileSizeLow % 4 == 0 &&
               readFile(path, 0, a.nFileSizeLow, delta);
        if (e.ok) {
            e.delta.assign((const uint32_t*)delta.data(), (const uint32_t*)delta.data() + delta.size() / 4);
            std::vector<uint32_t> probe;
            e.ok = applyDelta((const uint32_t*)original.data(), e.bytes / 4, e.delta.data(), e.delta.size(), probe);
        }
        if (!e.ok) {
            for (auto& g : e.groups) groups[g] = false;
        }
    }
    // Each module takes the line whose groups are exactly the active ones among the groups that edit it.
    std::map<Key, std::set<std::string>, bool (*)(const Key&, const Key&)> moduleGroups(
        [](const Key& a, const Key& b) { return memcmp(a.bytes, b.bytes, 32) < 0; });
    for (auto& e : entries) moduleGroups[e.key].insert(e.groups.begin(), e.groups.end());
    auto* set = new PatchSet();
    std::map<std::string, int> counts;
    for (auto& e : entries) {
        std::set<std::string> wanted;
        for (auto& g : moduleGroups[e.key]) {
            if (groups[g]) wanted.insert(g);
        }
        if (!e.ok || wanted.empty() || e.groups != wanted) continue;
        set->byKey.emplace(e.key, Replacement{widen(e.file), std::move(e.delta)});
        set->sizes.insert(e.bytes);
        for (auto& g : e.groups) counts[g]++;
    }
    int active = 0;
    for (auto& [group, ok] : groups) {
        report += "; " + group + (ok ? " ACTIVE (" + std::to_string(counts[group]) + " modules)" : " MISMATCH");
        active += ok ? 1 : 0;
    }
    if (active == 0) {
        delete set;
        return nullptr;
    }
    return set;
}

// Appends value to the semicolon-separated variable unless it is already listed.
void appendEnv(const wchar_t* name, const std::wstring& value) {
    wchar_t current[8192];
    DWORD n = GetEnvironmentVariableW(name, current, 8192);
    std::wstring list = n > 0 && n < 8192 ? std::wstring(current, n) : std::wstring();
    if (!list.empty() && (L";" + list + L";").find(L";" + value + L";") != std::wstring::npos) return;
    if (!list.empty()) list += L";";
    list += value;
    SetEnvironmentVariableW(name, list.c_str());
}

}  // namespace

// vk_layer.h declares it without dllexport, so the export comes from the linker.
#pragma comment(linker, "/EXPORT:vkNegotiateLoaderLayerInterfaceVersion")
extern "C" VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* v) {
    if (v->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (v->loaderLayerInterfaceVersion >= 2) {
        v->pfnGetInstanceProcAddr = GetInstanceProcAddr;
        v->pfnGetDeviceProcAddr = GetDeviceProcAddr;
        v->pfnGetPhysicalDeviceProcAddr = nullptr;
        v->loaderLayerInterfaceVersion = 2;
    }
    return VK_SUCCESS;
}

// Puts this layer on the process's layer path and enabled list. Call before the runtime creates its
// Vulkan instance. Writes a one-line report (UTF-8) and returns 1 when armed, 0 when it was already
// armed, negative on failure: -1 the manifest is missing beside this DLL.
RRP_EXPORT int32_t rrp_arm(char* report, int32_t capacity) {
    std::wstring dir = thisDirectory();
    if (GetFileAttributesW((dir + L"\\" + MANIFEST_NAME).c_str()) == INVALID_FILE_ATTRIBUTES) {
        snprintf(report, capacity, "layer manifest missing beside the layer");
        return -1;
    }
    if (g_armed.exchange(1)) {
        snprintf(report, capacity, "already armed");
        return 0;
    }
    std::string text = std::string("armed ") + LAYER_NAME;
    const PatchSet* set = loadAndVerify(dir, text);
    g_set.store(set, std::memory_order_release);
    appendEnv(L"VK_ADD_LAYER_PATH", dir);
    appendEnv(L"VK_INSTANCE_LAYERS", LAYER_NAME_W);
    snprintf(report, capacity, "%s", text.c_str());
    return 1;
}

// Counters since load: [armed, instanceHooked, devices, modulesSeen, modulesReplaced, replacementsRefused].
RRP_EXPORT void rrp_status(uint32_t* out) {
    out[0] = g_armed.load();
    out[1] = g_hooked.load();
    out[2] = g_devices.load();
    out[3] = g_seen.load();
    out[4] = g_replaced.load();
    out[5] = g_fellBack.load();
}

// Applies a delta to a module, as vkCreateShaderModule does: writes the output into out (capacity words)
// and returns its word count; -1 when the delta does not fit the module, -2 when out is too small. The
// build tool checks every delta through this.
RRP_EXPORT int64_t rrp_apply(const uint32_t* in, size_t words, const uint32_t* delta, size_t deltaWords, uint32_t* out,
                             size_t capacity) {
    std::vector<uint32_t> code;
    if (!applyDelta(in, words, delta, deltaWords, code)) return -1;
    if (code.size() > capacity) return -2;
    memcpy(out, code.data(), code.size() * 4);
    return (int64_t)code.size();
}

// The layer's key of a module (32 bytes into out); 0 on success. The build tool keys with this.
RRP_EXPORT int32_t rrp_key(const uint32_t* code, size_t words, uint8_t* out) {
    Key key;
    if (!moduleKey(code, words, key)) return -1;
    memcpy(out, key.bytes, 32);
    return 0;
}
