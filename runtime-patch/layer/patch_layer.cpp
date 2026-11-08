// SPDX-License-Identifier: Apache-2.0
//
// The mod's Vulkan layer for the RTX Remix runtime. The mod calls rrp_arm before the runtime loads;
// rrp_arm puts this layer on the loader's search path and enabled list for the process, and the
// loader then loads this same DLL into the runtime's instance. The layer sees every
// vkCreateShaderModule the runtime makes.
//
// This first version only passes calls through and counts them, so the mod can confirm that the
// loader loaded the layer (instanceHooked) and see the modules that reach it. With
// RTXREMIX_PATCH_DUMP set to a directory, every module of a size listed in RTXREMIX_PATCH_DUMP_SIZES
// (comma-separated byte counts) is written there, for comparing incoming code with the modules
// embedded in d3d9.dll.
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <windows.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>

#define RRP_EXPORT extern "C" __declspec(dllexport)

namespace {

constexpr const char* LAYER_NAME = "VK_LAYER_RTXREMIX_spirv_patch";
constexpr const wchar_t* LAYER_NAME_W = L"VK_LAYER_RTXREMIX_spirv_patch";
constexpr const wchar_t* MANIFEST_NAME = L"VkLayer_rtxremix_spirv_patch.json";

std::atomic<uint32_t> g_armed{0}, g_hooked{0}, g_devices{0}, g_seen{0}, g_dumped{0};

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

// ---- module dumps (diagnostic) ----------------------------------------------------------------

std::once_flag g_dumpOnce;
std::wstring g_dumpDir;
std::set<size_t> g_dumpSizes;

void readDumpSettings() {
    wchar_t dir[MAX_PATH];
    if (GetEnvironmentVariableW(L"RTXREMIX_PATCH_DUMP", dir, MAX_PATH) == 0) return;
    char sizes[1024];
    if (GetEnvironmentVariableA("RTXREMIX_PATCH_DUMP_SIZES", sizes, sizeof sizes) == 0) return;
    g_dumpDir = dir;
    for (char* token = strtok(sizes, ","); token; token = strtok(nullptr, ",")) {
        g_dumpSizes.insert((size_t)_strtoui64(token, nullptr, 10));
    }
}

void maybeDump(const VkShaderModuleCreateInfo* info) {
    std::call_once(g_dumpOnce, readDumpSettings);
    if (g_dumpDir.empty() || !g_dumpSizes.count(info->codeSize)) return;
    uint32_t n = g_dumped.fetch_add(1);
    wchar_t path[MAX_PATH + 64];
    swprintf(path, MAX_PATH + 64, L"%s\\incoming_%zu_%u.spv", g_dumpDir.c_str(), info->codeSize, n);
    HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD written = 0;
    WriteFile(f, info->pCode, (DWORD)info->codeSize, &written, nullptr);
    CloseHandle(f);
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
    maybeDump(info);
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
    appendEnv(L"VK_ADD_LAYER_PATH", dir);
    appendEnv(L"VK_INSTANCE_LAYERS", LAYER_NAME_W);
    bool loaderLoaded = GetModuleHandleW(L"vulkan-1.dll") != nullptr;
    snprintf(report, capacity, "armed %s%s", LAYER_NAME,
             loaderLoaded ? "; vulkan-1.dll was already loaded before arming" : "");
    return 1;
}

// Counters since load: [armed, instanceHooked, devices, modulesSeen, modulesDumped].
RRP_EXPORT void rrp_status(uint32_t* out) {
    out[0] = g_armed.load();
    out[1] = g_hooked.load();
    out[2] = g_devices.load();
    out[3] = g_seen.load();
    out[4] = g_dumped.load();
}
