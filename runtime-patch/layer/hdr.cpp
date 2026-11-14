// SPDX-License-Identifier: Apache-2.0
//
// HDR output; see hdr.h.
#include "hdr.h"

#include "survey.h"

#pragma comment(lib, "user32.lib")  // the window's monitor and its display configuration

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace rrp::hdr {
namespace {

constexpr VkFormat kWide = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkSurfaceFormatKHR kScRgb{VK_FORMAT_R16G16B16A16_SFLOAT, VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT};

std::atomic<bool> g_accepted{false};
std::atomic<uint32_t> g_state{kOff}, g_swapchains{0}, g_widened{0}, g_interopWidened{0};
uint32_t g_windowsHdr = 0, g_sdrWhiteNits = 80;  // written once, by request, before the runtime starts

std::mutex g_lock;  // guards everything below
std::string g_reason = "not asked";
std::unordered_set<uint64_t> g_wideImages;

void* key(const void* dispatchable) { return *(void* const*)dispatchable; }
template <typename H>
uint64_t id(H h) {
    return (uint64_t)(uintptr_t)h;
}

void decide(uint32_t state, const std::string& reason) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_state = state;
    g_reason = reason;
}

std::string narrow(const wchar_t* text) {
    char out[256] = "";
    WideCharToMultiByte(CP_UTF8, 0, text, -1, out, sizeof out, nullptr, nullptr);
    return out;
}

// DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2 (Windows 11 24H2), declared here since older SDKs lack it.
constexpr DISPLAYCONFIG_DEVICE_INFO_TYPE kAdvancedColorInfo2 = (DISPLAYCONFIG_DEVICE_INFO_TYPE)15;
struct AdvancedColorInfo2 {
    DISPLAYCONFIG_DEVICE_INFO_HEADER header;
    UINT32 value;  // bit 1 advancedColorActive, bit 5 highDynamicRangeUserEnabled, ...
    UINT32 colorEncoding;
    UINT32 bitsPerColorChannel;
    UINT32 activeColorMode;  // 0 SDR, 1 WCG, 2 HDR
};

struct Display {
    bool found = false;
    bool hdr = false;
    uint32_t sdrWhiteNits = 80;
    std::string name;
};

// The window's monitor as Windows composes it. WCG (Auto Color Management on an SDR output) is not HDR: the older
// advanced-colour query reports it as enabled.
Display displayOf(HWND window) {
    Display d;
    MONITORINFOEXW monitor{};
    monitor.cbSize = sizeof monitor;
    if (!GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) return d;
    d.name = narrow(monitor.szDevice);
    UINT32 paths = 0, modes = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &paths, &modes) != ERROR_SUCCESS) return d;
    std::vector<DISPLAYCONFIG_PATH_INFO> path(paths);
    std::vector<DISPLAYCONFIG_MODE_INFO> mode(modes);
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &paths, path.data(), &modes, mode.data(), nullptr) != ERROR_SUCCESS) {
        return d;
    }
    for (UINT32 i = 0; i < paths; i++) {
        DISPLAYCONFIG_SOURCE_DEVICE_NAME source{};
        source.header = {DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME, sizeof source, path[i].sourceInfo.adapterId,
                         path[i].sourceInfo.id};
        if (DisplayConfigGetDeviceInfo(&source.header) != ERROR_SUCCESS ||
            wcscmp(source.viewGdiDeviceName, monitor.szDevice) != 0) {
            continue;
        }
        d.found = true;
        LUID adapter = path[i].targetInfo.adapterId;
        UINT32 target = path[i].targetInfo.id;
        DISPLAYCONFIG_TARGET_DEVICE_NAME name{};
        name.header = {DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME, sizeof name, adapter, target};
        if (DisplayConfigGetDeviceInfo(&name.header) == ERROR_SUCCESS && name.monitorFriendlyDeviceName[0]) {
            d.name += " " + narrow(name.monitorFriendlyDeviceName);
        }
        AdvancedColorInfo2 color2{};
        color2.header = {kAdvancedColorInfo2, sizeof color2, adapter, target};
        if (DisplayConfigGetDeviceInfo(&color2.header) == ERROR_SUCCESS) {
            d.hdr = color2.activeColorMode == 2;
        } else {
            DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO color{};
            color.header = {DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO, sizeof color, adapter, target};
            d.hdr = DisplayConfigGetDeviceInfo(&color.header) == ERROR_SUCCESS && color.advancedColorEnabled &&
                    !color.wideColorEnforced;
        }
        DISPLAYCONFIG_SDR_WHITE_LEVEL white{};
        white.header = {DISPLAYCONFIG_DEVICE_INFO_GET_SDR_WHITE_LEVEL, sizeof white, adapter, target};
        if (DisplayConfigGetDeviceInfo(&white.header) == ERROR_SUCCESS && white.SDRWhiteLevel > 0) {
            d.sdrWhiteNits = (uint32_t)(white.SDRWhiteLevel * 80 / 1000);
        }
        break;
    }
    return d;
}

// ---- next layer --------------------------------------------------------------------------------------------

struct Instance {
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR formats = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormats2KHR formats2 = nullptr;
};
struct Device {
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkCreateImageView createImageView = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain = nullptr;
};
std::unordered_map<void*, Instance> g_instances;  // by dispatch key, which a physical device shares
std::unordered_map<void*, Device> g_devices;

const Instance& instance(const void* dispatchable) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_instances.at(key(dispatchable));
}
const Device& device(const void* dispatchable) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_devices.at(key(dispatchable));
}

// ---- hooks -------------------------------------------------------------------------------------------------

bool offersScRgb(const std::vector<VkSurfaceFormatKHR>& formats) {
    for (const VkSurfaceFormatKHR& f : formats) {
        if (f.format == kScRgb.format && f.colorSpace == kScRgb.colorSpace) return true;
    }
    return false;
}

// Only the scRGB pair is reported, so the runtime picks it whichever way it picks. When the window does not offer
// it, the list goes through unchanged and the swapchain is refused.
VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice gpu, VkSurfaceKHR surface,
                                                                  uint32_t* count, VkSurfaceFormatKHR* formats) {
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR next = instance(gpu).formats;
    if (!g_accepted.load()) return next(gpu, surface, count, formats);
    uint32_t n = 0;
    VkResult r = next(gpu, surface, &n, nullptr);
    if (r != VK_SUCCESS) return r;
    std::vector<VkSurfaceFormatKHR> all(n);
    r = next(gpu, surface, &n, all.data());
    if (r < VK_SUCCESS) return r;
    all.resize(n);
    if (!offersScRgb(all)) return next(gpu, surface, count, formats);
    if (formats == nullptr) {
        *count = 1;
        return VK_SUCCESS;
    }
    if (*count < 1) return VK_INCOMPLETE;
    formats[0] = kScRgb;
    *count = 1;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceSurfaceFormats2KHR(VkPhysicalDevice gpu,
                                                                   const VkPhysicalDeviceSurfaceInfo2KHR* info,
                                                                   uint32_t* count, VkSurfaceFormat2KHR* formats) {
    PFN_vkGetPhysicalDeviceSurfaceFormats2KHR next = instance(gpu).formats2;
    if (!g_accepted.load()) return next(gpu, info, count, formats);
    uint32_t n = 0;
    VkResult r = next(gpu, info, &n, nullptr);
    if (r != VK_SUCCESS) return r;
    std::vector<VkSurfaceFormat2KHR> all(n, VkSurfaceFormat2KHR{VK_STRUCTURE_TYPE_SURFACE_FORMAT_2_KHR, nullptr, {}});
    r = next(gpu, info, &n, all.data());
    if (r < VK_SUCCESS) return r;
    std::vector<VkSurfaceFormatKHR> plain;
    for (uint32_t i = 0; i < n; i++) plain.push_back(all[i].surfaceFormat);
    if (!offersScRgb(plain)) return next(gpu, info, count, formats);
    if (formats == nullptr) {
        *count = 1;
        return VK_SUCCESS;
    }
    if (*count < 1) return VK_INCOMPLETE;
    formats[0].surfaceFormat = kScRgb;  // the caller's sType and pNext stay
    *count = 1;
    return VK_SUCCESS;
}

// The one widening rule, verified by count in game (three: the two back buffers and the interop colour image; a
// resize adds more). The interop depth image is R32_SFLOAT and never matches.
bool widens(const VkImageCreateInfo& info, bool external) {
    if (info.imageType != VK_IMAGE_TYPE_2D || info.mipLevels != 1 || info.arrayLayers != 1 ||
        info.samples != VK_SAMPLE_COUNT_1_BIT || info.tiling != VK_IMAGE_TILING_OPTIMAL) {
        return false;
    }
    if (external) return info.format == VK_FORMAT_R8G8B8A8_UNORM;
    constexpr VkImageUsageFlags backBuffer = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                             VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    return info.format == VK_FORMAT_B8G8R8A8_UNORM && (info.flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) &&
           info.usage == backBuffer;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateImage(VkDevice dev, const VkImageCreateInfo* info, const VkAllocationCallbacks* alloc,
                                           VkImage* out) {
    const Device& d = device(dev);
    bool external = false;
    VkImageFormatListCreateInfo* list = nullptr;
    for (auto* p = (const VkBaseInStructure*)info->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO) external = true;
        if (p->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) list = (VkImageFormatListCreateInfo*)p;
    }
    if (!widens(*info, external)) return d.createImage(dev, info, alloc, out);
    VkImageCreateInfo wide = *info;
    wide.format = kWide;
    // The runtime's view-format list names the 8-bit formats; every view of the image is made fp16 below, so the list
    // names fp16 for the call and is given back afterwards.
    uint32_t listed = list ? list->viewFormatCount : 0;
    const VkFormat* formats = list ? list->pViewFormats : nullptr;
    if (list) {
        list->viewFormatCount = 1;
        list->pViewFormats = &kWide;
    }
    VkResult r = d.createImage(dev, &wide, alloc, out);
    if (list) {
        list->viewFormatCount = listed;
        list->pViewFormats = formats;
    }
    if (r != VK_SUCCESS) {
        decide(g_state, "the driver refused an fp16 image (" + std::to_string(r) + "); it stays 8-bit");
        return d.createImage(dev, info, alloc, out);
    }
    std::lock_guard<std::mutex> lock(g_lock);
    g_wideImages.insert(id(*out));
    g_widened++;
    if (external) g_interopWidened = 1;
    return r;
}

VKAPI_ATTR void VKAPI_CALL DestroyImage(VkDevice dev, VkImage image, const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_wideImages.erase(id(image));
    }
    d.destroyImage(dev, image, alloc);
}

// A view of a widened image takes the image's format: UNORM and SRGB views alike, since fp16 has no sRGB view and
// the presenter reads floats either way. The swizzle stays the runtime's.
VKAPI_ATTR VkResult VKAPI_CALL CreateImageView(VkDevice dev, const VkImageViewCreateInfo* info,
                                               const VkAllocationCallbacks* alloc, VkImageView* out) {
    const Device& d = device(dev);
    bool wide;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        wide = g_wideImages.count(id(info->image)) != 0;
    }
    if (!wide) return d.createImageView(dev, info, alloc, out);
    VkImageViewCreateInfo copy = *info;
    copy.format = kWide;
    return d.createImageView(dev, &copy, alloc, out);
}

VKAPI_ATTR VkResult VKAPI_CALL CreateSwapchainKHR(VkDevice dev, const VkSwapchainCreateInfoKHR* info,
                                                  const VkAllocationCallbacks* alloc, VkSwapchainKHR* out) {
    const Device& d = device(dev);
    VkResult r = d.createSwapchain(dev, info, alloc, out);
    if (r != VK_SUCCESS) return r;
    g_swapchains++;
    if (info->imageFormat == kScRgb.format && info->imageColorSpace == kScRgb.colorSpace) {
        decide(kActive, "scRGB swapchain " + std::to_string(info->imageExtent.width) + "x" +
                            std::to_string(info->imageExtent.height));
    } else {
        decide(kRefused, "the runtime made its swapchain in format " + std::to_string((int)info->imageFormat) +
                             ", colour space " + std::to_string((int)info->imageColorSpace) +
                             " (the window may offer no scRGB)");
    }
    return r;
}

template <typename T>
T resolve(PFN_vkGetDeviceProcAddr gdpa, VkDevice dev, const char* name) {
    PFN_vkVoidFunction f = survey::deviceHook(dev, name);  // through the survey's, which then logs what hdr asked
    return (T)(f ? f : gdpa(dev, name));
}

}  // namespace

bool request(HWND window, std::string& report) {
    Display display = displayOf(window);
    g_windowsHdr = display.hdr ? 1 : 0;
    g_sdrWhiteNits = display.sdrWhiteNits;
    std::string where = display.found ? display.name : "the window's display";
    if (!display.hdr) {
        decide(kDeclined, "Windows HDR is off on " + where);
        report += "; HDR output declined: Windows HDR is off on " + where;
        return false;
    }
    decide(kWaiting, "waiting for the swapchain on " + where);
    g_accepted = true;
    report += "; HDR output accepted on " + where + " (SDR white " + std::to_string(display.sdrWhiteNits) + " nits)";
    return true;
}

bool accepted() { return g_accepted.load(); }

void withdraw(const std::string& why) {
    if (!g_accepted.exchange(false)) return;
    decide(kDeclined, why);
}

void instanceCreated(VkInstance inst, PFN_vkGetInstanceProcAddr gipa) {
    Instance i;
    i.formats = (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)gipa(inst, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    i.formats2 = (PFN_vkGetPhysicalDeviceSurfaceFormats2KHR)gipa(inst, "vkGetPhysicalDeviceSurfaceFormats2KHR");
    std::lock_guard<std::mutex> lock(g_lock);
    g_instances[key(inst)] = i;
}

void deviceCreated(VkDevice dev, PFN_vkGetDeviceProcAddr gdpa) {
    Device d;
    d.createImage = resolve<PFN_vkCreateImage>(gdpa, dev, "vkCreateImage");
    d.destroyImage = resolve<PFN_vkDestroyImage>(gdpa, dev, "vkDestroyImage");
    d.createImageView = resolve<PFN_vkCreateImageView>(gdpa, dev, "vkCreateImageView");
    d.createSwapchain = resolve<PFN_vkCreateSwapchainKHR>(gdpa, dev, "vkCreateSwapchainKHR");
    std::lock_guard<std::mutex> lock(g_lock);
    g_devices[key(dev)] = d;
}

void deviceDestroyed(VkDevice dev) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_devices.erase(key(dev));
}

PFN_vkVoidFunction deviceHook(VkDevice dev, const char* name) {
    if (!g_accepted.load()) return nullptr;
    const Device* d;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        auto it = g_devices.find(key(dev));
        if (it == g_devices.end()) return nullptr;
        d = &it->second;
    }
    if (!strcmp(name, "vkCreateImage") && d->createImage) return (PFN_vkVoidFunction)CreateImage;
    if (!strcmp(name, "vkDestroyImage") && d->destroyImage) return (PFN_vkVoidFunction)DestroyImage;
    if (!strcmp(name, "vkCreateImageView") && d->createImageView) return (PFN_vkVoidFunction)CreateImageView;
    if (!strcmp(name, "vkCreateSwapchainKHR") && d->createSwapchain) return (PFN_vkVoidFunction)CreateSwapchainKHR;
    return nullptr;
}

PFN_vkVoidFunction instanceHook(VkInstance inst, const char* name) {
    if (!g_accepted.load() || inst == VK_NULL_HANDLE) return nullptr;
    std::lock_guard<std::mutex> lock(g_lock);
    auto it = g_instances.find(key(inst));
    if (it == g_instances.end()) return nullptr;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceFormatsKHR") && it->second.formats) {
        return (PFN_vkVoidFunction)GetPhysicalDeviceSurfaceFormatsKHR;
    }
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceFormats2KHR") && it->second.formats2) {
        return (PFN_vkVoidFunction)GetPhysicalDeviceSurfaceFormats2KHR;
    }
    return nullptr;
}

void status(uint32_t* out, std::string& reason) {
    std::lock_guard<std::mutex> lock(g_lock);
    out[0] = g_state.load();
    out[1] = g_swapchains.load();
    out[2] = g_widened.load();
    out[3] = g_interopWidened.load();
    out[4] = g_windowsHdr;
    out[5] = g_sdrWhiteNits;
    reason = g_reason;
}

}  // namespace rrp::hdr
