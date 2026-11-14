// SPDX-License-Identifier: Apache-2.0
//
// The presentation survey; see survey.h. Each hook notes what the call touches, under one lock, and calls the next
// layer with its arguments unchanged. A kind of call is written once, as a line, and counted every time. A line
// names an image by what it is (format, size, usage) and, for the images the survey watches, by a label: shared#n
// for an image made for exportable memory or bound to it, swap#n.i for image i of swapchain n, via#n for an image a
// blit, copy or resolve reached from one of those, at most two transfers away.
#include "survey.h"

#include <windows.h>

#pragma comment(lib, "user32.lib")  // the surface's window, by GetClassNameA and GetClientRect

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace rrp::survey {
namespace {

std::atomic<bool> g_enabled{false};

std::mutex g_lock;  // guards everything below
constexpr size_t kMaxLines = 400;
std::string g_pending;  // lines not yet taken
size_t g_lines = 0;
std::map<std::string, uint64_t> g_counts;  // kind -> times seen

void* key(const void* dispatchable) { return *(void* const*)dispatchable; }
template <typename H>
uint64_t id(H h) {
    return (uint64_t)(uintptr_t)h;
}

// Writes kind as a line the first time it happens, and counts it every time. g_lock held.
void note(const std::string& kind) {
    uint64_t& n = g_counts[kind];
    if (n++ == 0 && g_lines < kMaxLines) {
        g_pending += kind;
        g_pending += '\n';
        if (++g_lines == kMaxLines) g_pending += "line cap reached: later kinds are only counted\n";
    }
}

std::string hex(uint64_t v) {
    char s[24];
    snprintf(s, sizeof s, "0x%llx", (unsigned long long)v);
    return s;
}

std::string formatName(VkFormat f) {
    switch (f) {
#define RRP_NAME(x) \
    case VK_FORMAT_##x: \
        return #x;
        RRP_NAME(UNDEFINED)
        RRP_NAME(R8_UNORM)
        RRP_NAME(R8G8_UNORM)
        RRP_NAME(R8G8B8A8_UNORM)
        RRP_NAME(R8G8B8A8_SRGB)
        RRP_NAME(B8G8R8A8_UNORM)
        RRP_NAME(B8G8R8A8_SRGB)
        RRP_NAME(A8B8G8R8_UNORM_PACK32)
        RRP_NAME(A8B8G8R8_SRGB_PACK32)
        RRP_NAME(A2R10G10B10_UNORM_PACK32)
        RRP_NAME(A2B10G10R10_UNORM_PACK32)
        RRP_NAME(R16_SFLOAT)
        RRP_NAME(R16G16_SFLOAT)
        RRP_NAME(R16G16B16A16_UNORM)
        RRP_NAME(R16G16B16A16_SFLOAT)
        RRP_NAME(R32_UINT)
        RRP_NAME(R32_SFLOAT)
        RRP_NAME(R32G32_SFLOAT)
        RRP_NAME(R32G32B32A32_SFLOAT)
        RRP_NAME(B10G11R11_UFLOAT_PACK32)
        RRP_NAME(E5B9G9R9_UFLOAT_PACK32)
        RRP_NAME(D16_UNORM)
        RRP_NAME(X8_D24_UNORM_PACK32)
        RRP_NAME(D32_SFLOAT)
        RRP_NAME(D24_UNORM_S8_UINT)
        RRP_NAME(D32_SFLOAT_S8_UINT)
#undef RRP_NAME
        default:
            return "format" + std::to_string((int)f);
    }
}

std::string colorSpaceName(VkColorSpaceKHR c) {
    switch (c) {
#define RRP_NAME(x) \
    case VK_COLOR_SPACE_##x: \
        return #x;
        RRP_NAME(SRGB_NONLINEAR_KHR)
        RRP_NAME(DISPLAY_P3_NONLINEAR_EXT)
        RRP_NAME(EXTENDED_SRGB_LINEAR_EXT)
        RRP_NAME(DISPLAY_P3_LINEAR_EXT)
        RRP_NAME(DCI_P3_NONLINEAR_EXT)
        RRP_NAME(BT709_LINEAR_EXT)
        RRP_NAME(BT709_NONLINEAR_EXT)
        RRP_NAME(BT2020_LINEAR_EXT)
        RRP_NAME(HDR10_ST2084_EXT)
        RRP_NAME(HDR10_HLG_EXT)
        RRP_NAME(ADOBERGB_LINEAR_EXT)
        RRP_NAME(ADOBERGB_NONLINEAR_EXT)
        RRP_NAME(PASS_THROUGH_EXT)
        RRP_NAME(EXTENDED_SRGB_NONLINEAR_EXT)
#undef RRP_NAME
        default:
            return "colorSpace" + std::to_string((int)c);
    }
}

std::string layoutName(VkImageLayout l) {
    switch (l) {
        case VK_IMAGE_LAYOUT_UNDEFINED: return "UNDEFINED";
        case VK_IMAGE_LAYOUT_GENERAL: return "GENERAL";
        case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL: return "COLOR_ATTACHMENT";
        case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL: return "DEPTH_STENCIL_ATTACHMENT";
        case VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL: return "DEPTH_STENCIL_READ_ONLY";
        case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL: return "SHADER_READ_ONLY";
        case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL: return "TRANSFER_SRC";
        case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL: return "TRANSFER_DST";
        case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR: return "PRESENT_SRC";
        case VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL: return "ATTACHMENT";
        case VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL: return "READ_ONLY";
        default: return "layout" + std::to_string((int)l);
    }
}

std::string descriptorName(VkDescriptorType t) {
    switch (t) {
        case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER: return "COMBINED_IMAGE_SAMPLER";
        case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE: return "SAMPLED_IMAGE";
        case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE: return "STORAGE_IMAGE";
        case VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT: return "INPUT_ATTACHMENT";
        default: return "descriptor" + std::to_string((int)t);
    }
}

bool imageDescriptor(VkDescriptorType t) {
    return t == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER || t == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE ||
           t == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE || t == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
}

std::string presentModeName(VkPresentModeKHR m) {
    switch (m) {
        case VK_PRESENT_MODE_IMMEDIATE_KHR: return "IMMEDIATE";
        case VK_PRESENT_MODE_MAILBOX_KHR: return "MAILBOX";
        case VK_PRESENT_MODE_FIFO_KHR: return "FIFO";
        case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "FIFO_RELAXED";
        default: return "mode" + std::to_string((int)m);
    }
}

std::string usageName(VkImageUsageFlags u) {
    static const std::pair<VkImageUsageFlags, const char*> bits[] = {
        {VK_IMAGE_USAGE_TRANSFER_SRC_BIT, "src"},     {VK_IMAGE_USAGE_TRANSFER_DST_BIT, "dst"},
        {VK_IMAGE_USAGE_SAMPLED_BIT, "sampled"},      {VK_IMAGE_USAGE_STORAGE_BIT, "storage"},
        {VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, "color"}, {VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, "depth"},
        {VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT, "transient"}, {VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT, "input"}};
    std::string s;
    VkImageUsageFlags rest = u;
    for (auto& [bit, name] : bits) {
        if (!(u & bit)) continue;
        if (!s.empty()) s += '|';
        s += name;
        rest &= ~bit;
    }
    if (rest) s += (s.empty() ? "" : "|") + hex(rest);
    return s.empty() ? "none" : s;
}

std::string size(uint32_t w, uint32_t h) { return std::to_string(w) + "x" + std::to_string(h); }

// ---- what the survey knows (g_lock) ----------------------------------------------------------------

struct Image {
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent3D extent{};
    VkImageUsageFlags usage = 0;
    VkImageCreateFlags flags = 0;
    uint32_t mips = 1, layers = 1;
    std::string label;  // shared#n, swap#n.i or via#n when the survey watches it
    int steps = 0;      // transfers from a shared or swapchain image, for via#n
};
std::unordered_map<uint64_t, Image> g_images;
std::set<uint64_t> g_exportedMemory;
struct View {
    uint64_t image;
    VkFormat format;
    VkImageViewType type;
};
std::unordered_map<uint64_t, View> g_views;  // every live view, so an image watched later shows its views
struct Framebuffer {
    uint32_t width, height;
    std::vector<uint64_t> views;
};
std::unordered_map<uint64_t, Framebuffer> g_framebuffers;  // every framebuffer with attachments
constexpr int kMaxSteps = 2;  // how far a transfer from a watched image carries the watch
int g_viaCount = 0;
struct TemplateEntry {
    uint32_t binding, count;
    VkDescriptorType type;
    size_t offset, stride;
};
std::unordered_map<uint64_t, std::vector<TemplateEntry>> g_templates;  // image entries of each update template
struct Swapchain {
    int number;
    VkFormat format;
    VkExtent2D extent;
    VkImageUsageFlags usage;
};
std::unordered_map<uint64_t, Swapchain> g_swapchains;
int g_sharedCount = 0, g_swapchainCount = 0;

std::string describe(const Image& im) {
    std::string s = (im.label.empty() ? std::string("image") : im.label) + " " + formatName(im.format) + " " +
                    size(im.extent.width, im.extent.height);
    if (im.extent.depth > 1) s += "x" + std::to_string(im.extent.depth);
    s += " usage=" + usageName(im.usage);
    if (im.mips > 1) s += " mips=" + std::to_string(im.mips);
    if (im.layers > 1) s += " layers=" + std::to_string(im.layers);
    if (im.flags) s += " flags=" + hex(im.flags);
    return s;
}

std::string describeImage(uint64_t image) {
    auto it = g_images.find(image);
    return it == g_images.end() ? "unknown image" : describe(it->second);
}

bool watched(uint64_t image) {
    auto it = g_images.find(image);
    return it != g_images.end() && !it->second.label.empty();
}

std::string viewLine(const std::string& label, const View& v) {
    return label + " view " + formatName(v.format) + " type " + std::to_string((int)v.type);
}

// A view of a watched image as "label view FORMAT type T", or empty.
std::string watchedView(VkImageView view) {
    auto it = g_views.find(id(view));
    if (it == g_views.end()) return {};
    auto im = g_images.find(it->second.image);
    if (im == g_images.end() || im->second.label.empty()) return {};
    return viewLine(im->second.label, it->second);
}

// Watches an image a transfer reached from a watched one, as via#n, with the views it already has.
void follow(uint64_t image, int steps, const std::string& how) {
    auto it = g_images.find(image);
    if (it == g_images.end() || !it->second.label.empty() || steps > kMaxSteps) return;
    it->second.label = "via#" + std::to_string(++g_viaCount);
    it->second.steps = steps;
    note(describe(it->second) + " reached by " + how);
    for (const auto& [view, v] : g_views) {
        if (v.image == image) note(viewLine(it->second.label, v) + " (made before)");
    }
}

// Notes a transfer between images when either is watched, first carrying the watch to the other one.
void transferred(const char* op, VkImage src, VkImageLayout srcLayout, VkImage dst, VkImageLayout dstLayout,
                 uint32_t regions, const std::string& extra) {
    auto s = g_images.find(id(src)), d = g_images.find(id(dst));
    bool ws = s != g_images.end() && !s->second.label.empty();
    bool wd = d != g_images.end() && !d->second.label.empty();
    if (!ws && !wd) return;
    if (ws && !wd) follow(id(dst), s->second.steps + 1, std::string(op) + " from " + s->second.label);
    if (wd && !ws) follow(id(src), d->second.steps + 1, std::string(op) + " into " + d->second.label);
    note(std::string(op) + " " + describeImage(id(src)) + " (" + layoutName(srcLayout) + ") -> " +
         describeImage(id(dst)) + " (" + layoutName(dstLayout) + ") regions=" + std::to_string(regions) + extra);
}

std::string blitSizes(const VkOffset3D (&s)[2], const VkOffset3D (&d)[2]) {
    return " " + size(abs(s[1].x - s[0].x), abs(s[1].y - s[0].y)) + " to " +
           size(abs(d[1].x - d[0].x), abs(d[1].y - d[0].y));
}

std::string filterName(VkFilter f) {
    return f == VK_FILTER_LINEAR ? " linear" : f == VK_FILTER_NEAREST ? " nearest" : " filter" + std::to_string((int)f);
}

// ---- next layer, per device and instance --------------------------------------------------------------

struct Device {
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkInstance instance = VK_NULL_HANDLE;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImages = nullptr;
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkBindImageMemory2 bindImageMemory2 = nullptr;
    PFN_vkCreateImageView createImageView = nullptr;
    PFN_vkDestroyImageView destroyImageView = nullptr;
    PFN_vkCreateFramebuffer createFramebuffer = nullptr;
    PFN_vkDestroyFramebuffer destroyFramebuffer = nullptr;
    PFN_vkCmdBeginRenderPass cmdBeginRenderPass = nullptr;
    PFN_vkCmdBeginRenderPass2 cmdBeginRenderPass2 = nullptr;
    PFN_vkCmdBeginRendering cmdBeginRendering = nullptr;
    PFN_vkCmdBlitImage cmdBlitImage = nullptr;
    PFN_vkCmdBlitImage2 cmdBlitImage2 = nullptr;
    PFN_vkCmdCopyImage cmdCopyImage = nullptr;
    PFN_vkCmdCopyImage2 cmdCopyImage2 = nullptr;
    PFN_vkCmdResolveImage cmdResolveImage = nullptr;
    PFN_vkCmdClearColorImage cmdClearColorImage = nullptr;
    PFN_vkCmdCopyBufferToImage cmdCopyBufferToImage = nullptr;
    PFN_vkCmdCopyImageToBuffer cmdCopyImageToBuffer = nullptr;
    PFN_vkUpdateDescriptorSets updateDescriptorSets = nullptr;
    PFN_vkCreateDescriptorUpdateTemplate createDescriptorUpdateTemplate = nullptr;
    PFN_vkDestroyDescriptorUpdateTemplate destroyDescriptorUpdateTemplate = nullptr;
    PFN_vkUpdateDescriptorSetWithTemplate updateDescriptorSetWithTemplate = nullptr;
};
std::unordered_map<void*, Device> g_devices;  // elements stay put while the map grows

struct Instance {
    PFN_vkCreateWin32SurfaceKHR createWin32Surface = nullptr;
};
std::unordered_map<void*, Instance> g_instances;

// The device a device, queue or command buffer belongs to. Only a device the survey set up hands out its hooks.
const Device& device(const void* dispatchable) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_devices.at(key(dispatchable));
}

// ---- hooks ------------------------------------------------------------------------------------------------

std::string surfaceOffers(const Device& d, VkSurfaceKHR surface) {
    auto getFormats =
        (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)d.gipa(d.instance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    auto getCaps =
        (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)d.gipa(d.instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    auto getModes =
        (PFN_vkGetPhysicalDeviceSurfacePresentModesKHR)d.gipa(d.instance, "vkGetPhysicalDeviceSurfacePresentModesKHR");
    std::string s = "surface offers:";
    uint32_t n = 0;
    if (getFormats && getFormats(d.gpu, surface, &n, nullptr) == VK_SUCCESS) {
        std::vector<VkSurfaceFormatKHR> formats(n);
        if (getFormats(d.gpu, surface, &n, formats.data()) >= VK_SUCCESS) {
            for (uint32_t i = 0; i < n; i++) {
                s += " " + formatName(formats[i].format) + "/" + colorSpaceName(formats[i].colorSpace);
            }
        }
    }
    VkSurfaceCapabilitiesKHR caps{};
    if (getCaps && getCaps(d.gpu, surface, &caps) == VK_SUCCESS) {
        s += "; images " + std::to_string(caps.minImageCount) + ".." + std::to_string(caps.maxImageCount) +
             ", current " + size(caps.currentExtent.width, caps.currentExtent.height) + ", usage " +
             usageName(caps.supportedUsageFlags) + ", composite " + hex(caps.supportedCompositeAlpha);
    }
    n = 0;
    if (getModes && getModes(d.gpu, surface, &n, nullptr) == VK_SUCCESS) {
        std::vector<VkPresentModeKHR> modes(n);
        if (getModes(d.gpu, surface, &n, modes.data()) >= VK_SUCCESS) {
            s += "; present";
            for (uint32_t i = 0; i < n; i++) s += " " + presentModeName(modes[i]);
        }
    }
    return s;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateWin32SurfaceKHR(VkInstance instance, const VkWin32SurfaceCreateInfoKHR* info,
                                                     const VkAllocationCallbacks* alloc, VkSurfaceKHR* out) {
    PFN_vkCreateWin32SurfaceKHR next;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        next = g_instances.at(key(instance)).createWin32Surface;
    }
    VkResult result = next(instance, info, alloc, out);
    char cls[128] = "?";
    GetClassNameA(info->hwnd, cls, sizeof cls);
    RECT client{};
    GetClientRect(info->hwnd, &client);
    std::string line = "surface on window " + hex(id(info->hwnd)) + " class " + cls + " client " +
                       size(client.right - client.left, client.bottom - client.top) + " parent " +
                       hex(id(GetParent(info->hwnd))) + " result " + std::to_string(result);
    std::lock_guard<std::mutex> lock(g_lock);
    note(line);
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateSwapchainKHR(VkDevice dev, const VkSwapchainCreateInfoKHR* info,
                                                  const VkAllocationCallbacks* alloc, VkSwapchainKHR* out) {
    const Device& d = device(dev);
    std::string offered = surfaceOffers(d, info->surface);
    VkResult result = d.createSwapchain(dev, info, alloc, out);
    std::string chain;
    for (auto* p = (const VkBaseInStructure*)info->pNext; p; p = p->pNext) chain += " " + std::to_string((int)p->sType);
    std::lock_guard<std::mutex> lock(g_lock);
    note(offered);
    int n = ++g_swapchainCount;
    if (result == VK_SUCCESS) {
        g_swapchains[id(*out)] = Swapchain{n, info->imageFormat, info->imageExtent, info->imageUsage};
    }
    note("swapchain #" + std::to_string(n) + ": " + formatName(info->imageFormat) + "/" +
         colorSpaceName(info->imageColorSpace) + " " + size(info->imageExtent.width, info->imageExtent.height) +
         " images>=" + std::to_string(info->minImageCount) + " usage=" + usageName(info->imageUsage) + " present " +
         presentModeName(info->presentMode) + " composite " + hex(info->compositeAlpha) + " flags " +
         hex(info->flags) + " pNext [" + chain + " ] result " + std::to_string(result));
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL GetSwapchainImagesKHR(VkDevice dev, VkSwapchainKHR swapchain, uint32_t* count,
                                                     VkImage* images) {
    const Device& d = device(dev);
    VkResult result = d.getSwapchainImages(dev, swapchain, count, images);
    if (images == nullptr || (result != VK_SUCCESS && result != VK_INCOMPLETE)) return result;
    std::lock_guard<std::mutex> lock(g_lock);
    auto it = g_swapchains.find(id(swapchain));
    if (it == g_swapchains.end()) return result;
    const Swapchain& s = it->second;
    for (uint32_t i = 0; i < *count; i++) {
        Image im;
        im.format = s.format;
        im.extent = {s.extent.width, s.extent.height, 1};
        im.usage = s.usage;
        im.label = "swap#" + std::to_string(s.number) + "." + std::to_string(i);
        g_images[id(images[i])] = im;
    }
    note("swap#" + std::to_string(s.number) + " holds " + std::to_string(*count) + " images");
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateImage(VkDevice dev, const VkImageCreateInfo* info, const VkAllocationCallbacks* alloc,
                                           VkImage* out) {
    const Device& d = device(dev);
    VkResult result = d.createImage(dev, info, alloc, out);
    if (result != VK_SUCCESS) return result;
    VkExternalMemoryHandleTypeFlags external = 0;
    std::string viewFormats;
    for (auto* p = (const VkBaseInStructure*)info->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO) {
            external = ((const VkExternalMemoryImageCreateInfo*)p)->handleTypes;
        } else if (p->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
            auto* list = (const VkImageFormatListCreateInfo*)p;
            for (uint32_t i = 0; i < list->viewFormatCount; i++) viewFormats += " " + formatName(list->pViewFormats[i]);
        }
    }
    Image im;
    im.format = info->format;
    im.extent = info->extent;
    im.usage = info->usage;
    im.flags = info->flags;
    im.mips = info->mipLevels;
    im.layers = info->arrayLayers;
    std::lock_guard<std::mutex> lock(g_lock);
    if (external) {
        im.label = "shared#" + std::to_string(++g_sharedCount);
        note(describe(im) + " made for external memory " + hex(external) +
             (info->tiling == VK_IMAGE_TILING_OPTIMAL ? " optimal" : " tiling" + std::to_string((int)info->tiling)) +
             (viewFormats.empty() ? "" : ", views as" + viewFormats));
    }
    g_images[id(*out)] = std::move(im);
    return result;
}

VKAPI_ATTR void VKAPI_CALL DestroyImage(VkDevice dev, VkImage image, const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_images.erase(id(image));
    }
    d.destroyImage(dev, image, alloc);
}

VKAPI_ATTR VkResult VKAPI_CALL AllocateMemory(VkDevice dev, const VkMemoryAllocateInfo* info,
                                              const VkAllocationCallbacks* alloc, VkDeviceMemory* out) {
    const Device& d = device(dev);
    VkResult result = d.allocateMemory(dev, info, alloc, out);
    if (result != VK_SUCCESS) return result;
    for (auto* p = (const VkBaseInStructure*)info->pNext; p; p = p->pNext) {
        if (p->sType != VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO) continue;
        std::lock_guard<std::mutex> lock(g_lock);
        g_exportedMemory.insert(id(*out));
        note("exportable memory allocated, handle types " + hex(((const VkExportMemoryAllocateInfo*)p)->handleTypes));
    }
    return result;
}

VKAPI_ATTR void VKAPI_CALL FreeMemory(VkDevice dev, VkDeviceMemory memory, const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_exportedMemory.erase(id(memory));
    }
    d.freeMemory(dev, memory, alloc);
}

// An image bound to exportable memory is watched even when it was made without saying so. g_lock held.
void bound(VkImage image, VkDeviceMemory memory) {
    if (!g_exportedMemory.count(id(memory))) return;
    auto it = g_images.find(id(image));
    if (it == g_images.end() || !it->second.label.empty()) return;
    it->second.label = "shared#" + std::to_string(++g_sharedCount);
    note(describe(it->second) + " bound to exportable memory");
}

VKAPI_ATTR VkResult VKAPI_CALL BindImageMemory(VkDevice dev, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
    const Device& d = device(dev);
    VkResult result = d.bindImageMemory(dev, image, memory, offset);
    std::lock_guard<std::mutex> lock(g_lock);
    if (result == VK_SUCCESS) bound(image, memory);
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL BindImageMemory2(VkDevice dev, uint32_t count, const VkBindImageMemoryInfo* infos) {
    const Device& d = device(dev);
    VkResult result = d.bindImageMemory2(dev, count, infos);
    std::lock_guard<std::mutex> lock(g_lock);
    if (result == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++) bound(infos[i].image, infos[i].memory);
    }
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL CreateImageView(VkDevice dev, const VkImageViewCreateInfo* info,
                                               const VkAllocationCallbacks* alloc, VkImageView* out) {
    const Device& d = device(dev);
    VkResult result = d.createImageView(dev, info, alloc, out);
    if (result != VK_SUCCESS) return result;
    std::lock_guard<std::mutex> lock(g_lock);
    View v{id(info->image), info->format, info->viewType};
    g_views[id(*out)] = v;
    auto it = g_images.find(id(info->image));
    if (it == g_images.end() || it->second.label.empty()) return result;
    const VkImageSubresourceRange& r = info->subresourceRange;
    note(viewLine(it->second.label, v) + " aspect " + hex(r.aspectMask) + " mips " + std::to_string(r.baseMipLevel) +
         "+" + std::to_string(r.levelCount) + " layers " + std::to_string(r.baseArrayLayer) + "+" +
         std::to_string(r.layerCount));
    return result;
}

VKAPI_ATTR void VKAPI_CALL DestroyImageView(VkDevice dev, VkImageView view, const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_views.erase(id(view));
    }
    d.destroyImageView(dev, view, alloc);
}

VKAPI_ATTR VkResult VKAPI_CALL CreateFramebuffer(VkDevice dev, const VkFramebufferCreateInfo* info,
                                                 const VkAllocationCallbacks* alloc, VkFramebuffer* out) {
    const Device& d = device(dev);
    VkResult result = d.createFramebuffer(dev, info, alloc, out);
    if (result != VK_SUCCESS || (info->flags & VK_FRAMEBUFFER_CREATE_IMAGELESS_BIT)) return result;
    Framebuffer fb{info->width, info->height, {}};
    for (uint32_t i = 0; i < info->attachmentCount; i++) fb.views.push_back(id(info->pAttachments[i]));
    std::lock_guard<std::mutex> lock(g_lock);
    g_framebuffers[id(*out)] = std::move(fb);
    return result;
}

VKAPI_ATTR void VKAPI_CALL DestroyFramebuffer(VkDevice dev, VkFramebuffer fb, const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_framebuffers.erase(id(fb));
    }
    d.destroyFramebuffer(dev, fb, alloc);
}

// What a render pass begins drawing into, when it holds a view of a watched image. g_lock held.
std::string renderPassTargets(const VkRenderPassBeginInfo* begin) {
    std::string into;
    auto fb = g_framebuffers.find(id(begin->framebuffer));
    if (fb != g_framebuffers.end()) {
        for (size_t i = 0; i < fb->second.views.size(); i++) {
            std::string v = watchedView((VkImageView)(uintptr_t)fb->second.views[i]);
            if (!v.empty()) into += " [" + std::to_string(i) + "] " + v;
        }
    }
    for (auto* p = (const VkBaseInStructure*)begin->pNext; p; p = p->pNext) {
        if (p->sType != VK_STRUCTURE_TYPE_RENDER_PASS_ATTACHMENT_BEGIN_INFO) continue;
        auto* a = (const VkRenderPassAttachmentBeginInfo*)p;
        for (uint32_t i = 0; i < a->attachmentCount; i++) {
            std::string v = watchedView(a->pAttachments[i]);
            if (!v.empty()) into += " [" + std::to_string(i) + "] " + v;
        }
    }
    if (into.empty()) return into;
    return "render pass into" + into + " area " + size(begin->renderArea.extent.width, begin->renderArea.extent.height);
}

VKAPI_ATTR void VKAPI_CALL CmdBeginRenderPass(VkCommandBuffer cmd, const VkRenderPassBeginInfo* begin,
                                              VkSubpassContents contents) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        std::string into = renderPassTargets(begin);
        if (!into.empty()) note(into);
    }
    d.cmdBeginRenderPass(cmd, begin, contents);
}

VKAPI_ATTR void VKAPI_CALL CmdBeginRenderPass2(VkCommandBuffer cmd, const VkRenderPassBeginInfo* begin,
                                               const VkSubpassBeginInfo* subpass) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        std::string into = renderPassTargets(begin);
        if (!into.empty()) note(into);
    }
    d.cmdBeginRenderPass2(cmd, begin, subpass);
}

VKAPI_ATTR void VKAPI_CALL CmdBeginRendering(VkCommandBuffer cmd, const VkRenderingInfo* info) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        std::string into;
        for (uint32_t i = 0; i < info->colorAttachmentCount; i++) {
            const VkRenderingAttachmentInfo& a = info->pColorAttachments[i];
            std::string v = watchedView(a.imageView);
            if (!v.empty()) into += " color[" + std::to_string(i) + "] " + v + " load " + std::to_string((int)a.loadOp);
            std::string r = watchedView(a.resolveImageView);
            if (!r.empty()) into += " resolve[" + std::to_string(i) + "] " + r;
        }
        if (info->pDepthAttachment) {
            std::string v = watchedView(info->pDepthAttachment->imageView);
            if (!v.empty()) into += " depth " + v;
        }
        if (!into.empty()) {
            note("rendering into" + into + " area " + size(info->renderArea.extent.width, info->renderArea.extent.height));
        }
    }
    d.cmdBeginRendering(cmd, info);
}

VKAPI_ATTR void VKAPI_CALL CmdBlitImage(VkCommandBuffer cmd, VkImage src, VkImageLayout srcLayout, VkImage dst,
                                        VkImageLayout dstLayout, uint32_t count, const VkImageBlit* regions,
                                        VkFilter filter) {
    const Device& d = device(cmd);
    if (count > 0) {
        std::lock_guard<std::mutex> lock(g_lock);
        transferred("blit", src, srcLayout, dst, dstLayout, count,
                    blitSizes(regions[0].srcOffsets, regions[0].dstOffsets) + filterName(filter));
    }
    d.cmdBlitImage(cmd, src, srcLayout, dst, dstLayout, count, regions, filter);
}

VKAPI_ATTR void VKAPI_CALL CmdBlitImage2(VkCommandBuffer cmd, const VkBlitImageInfo2* info) {
    const Device& d = device(cmd);
    if (info->regionCount > 0) {
        std::lock_guard<std::mutex> lock(g_lock);
        transferred("blit2", info->srcImage, info->srcImageLayout, info->dstImage, info->dstImageLayout,
                    info->regionCount,
                    blitSizes(info->pRegions[0].srcOffsets, info->pRegions[0].dstOffsets) + filterName(info->filter));
    }
    d.cmdBlitImage2(cmd, info);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyImage(VkCommandBuffer cmd, VkImage src, VkImageLayout srcLayout, VkImage dst,
                                        VkImageLayout dstLayout, uint32_t count, const VkImageCopy* regions) {
    const Device& d = device(cmd);
    if (count > 0) {
        std::lock_guard<std::mutex> lock(g_lock);
        transferred("copy", src, srcLayout, dst, dstLayout, count,
                    " " + size(regions[0].extent.width, regions[0].extent.height));
    }
    d.cmdCopyImage(cmd, src, srcLayout, dst, dstLayout, count, regions);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyImage2(VkCommandBuffer cmd, const VkCopyImageInfo2* info) {
    const Device& d = device(cmd);
    if (info->regionCount > 0) {
        std::lock_guard<std::mutex> lock(g_lock);
        transferred("copy2", info->srcImage, info->srcImageLayout, info->dstImage, info->dstImageLayout,
                    info->regionCount, " " + size(info->pRegions[0].extent.width, info->pRegions[0].extent.height));
    }
    d.cmdCopyImage2(cmd, info);
}

VKAPI_ATTR void VKAPI_CALL CmdResolveImage(VkCommandBuffer cmd, VkImage src, VkImageLayout srcLayout, VkImage dst,
                                           VkImageLayout dstLayout, uint32_t count, const VkImageResolve* regions) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        transferred("resolve", src, srcLayout, dst, dstLayout, count, "");
    }
    d.cmdResolveImage(cmd, src, srcLayout, dst, dstLayout, count, regions);
}

VKAPI_ATTR void VKAPI_CALL CmdClearColorImage(VkCommandBuffer cmd, VkImage image, VkImageLayout layout,
                                              const VkClearColorValue* color, uint32_t count,
                                              const VkImageSubresourceRange* ranges) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (watched(id(image))) note("clear " + describeImage(id(image)) + " (" + layoutName(layout) + ")");
    }
    d.cmdClearColorImage(cmd, image, layout, color, count, ranges);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyBufferToImage(VkCommandBuffer cmd, VkBuffer buffer, VkImage dst, VkImageLayout layout,
                                                uint32_t count, const VkBufferImageCopy* regions) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (watched(id(dst))) note("buffer -> " + describeImage(id(dst)) + " (" + layoutName(layout) + ")");
    }
    d.cmdCopyBufferToImage(cmd, buffer, dst, layout, count, regions);
}

VKAPI_ATTR void VKAPI_CALL CmdCopyImageToBuffer(VkCommandBuffer cmd, VkImage src, VkImageLayout layout, VkBuffer buffer,
                                                uint32_t count, const VkBufferImageCopy* regions) {
    const Device& d = device(cmd);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (watched(id(src))) note(describeImage(id(src)) + " (" + layoutName(layout) + ") -> buffer");
    }
    d.cmdCopyImageToBuffer(cmd, src, layout, buffer, count, regions);
}

// A descriptor holding a watched view. g_lock held.
void described(VkDescriptorType type, uint32_t binding, const VkDescriptorImageInfo& image) {
    std::string v = watchedView(image.imageView);
    if (!v.empty()) {
        note("descriptor " + descriptorName(type) + " binding " + std::to_string(binding) + " holds " + v + " (" +
             layoutName(image.imageLayout) + ")");
    }
}

VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSets(VkDevice dev, uint32_t writeCount, const VkWriteDescriptorSet* writes,
                                                uint32_t copyCount, const VkCopyDescriptorSet* copies) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (!g_views.empty()) {
            for (uint32_t i = 0; i < writeCount; i++) {
                if (!imageDescriptor(writes[i].descriptorType) || writes[i].pImageInfo == nullptr) continue;
                for (uint32_t j = 0; j < writes[i].descriptorCount; j++) {
                    described(writes[i].descriptorType, writes[i].dstBinding, writes[i].pImageInfo[j]);
                }
            }
        }
    }
    d.updateDescriptorSets(dev, writeCount, writes, copyCount, copies);
}

VKAPI_ATTR VkResult VKAPI_CALL CreateDescriptorUpdateTemplate(VkDevice dev, const VkDescriptorUpdateTemplateCreateInfo* info,
                                                              const VkAllocationCallbacks* alloc,
                                                              VkDescriptorUpdateTemplate* out) {
    const Device& d = device(dev);
    VkResult result = d.createDescriptorUpdateTemplate(dev, info, alloc, out);
    if (result != VK_SUCCESS || info->templateType != VK_DESCRIPTOR_UPDATE_TEMPLATE_TYPE_DESCRIPTOR_SET) return result;
    std::vector<TemplateEntry> entries;
    for (uint32_t i = 0; i < info->descriptorUpdateEntryCount; i++) {
        const VkDescriptorUpdateTemplateEntry& e = info->pDescriptorUpdateEntries[i];
        if (imageDescriptor(e.descriptorType)) {
            entries.push_back({e.dstBinding, e.descriptorCount, e.descriptorType, e.offset, e.stride});
        }
    }
    if (entries.empty()) return result;
    std::lock_guard<std::mutex> lock(g_lock);
    g_templates[id(*out)] = std::move(entries);
    return result;
}

VKAPI_ATTR void VKAPI_CALL DestroyDescriptorUpdateTemplate(VkDevice dev, VkDescriptorUpdateTemplate tmpl,
                                                           const VkAllocationCallbacks* alloc) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        g_templates.erase(id(tmpl));
    }
    d.destroyDescriptorUpdateTemplate(dev, tmpl, alloc);
}

VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSetWithTemplate(VkDevice dev, VkDescriptorSet set,
                                                           VkDescriptorUpdateTemplate tmpl, const void* data) {
    const Device& d = device(dev);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        auto it = g_views.empty() ? g_templates.end() : g_templates.find(id(tmpl));
        if (it != g_templates.end()) {
            for (const TemplateEntry& e : it->second) {
                for (uint32_t j = 0; j < e.count; j++) {
                    described(e.type, e.binding,
                              *(const VkDescriptorImageInfo*)((const char*)data + e.offset + j * e.stride));
                }
            }
        }
    }
    d.updateDescriptorSetWithTemplate(dev, set, tmpl, data);
}

template <typename T>
T resolve(PFN_vkGetDeviceProcAddr gdpa, VkDevice dev, const char* name, const char* alias = nullptr) {
    PFN_vkVoidFunction f = gdpa(dev, name);
    if (f == nullptr && alias != nullptr) f = gdpa(dev, alias);
    return (T)f;
}

std::string extensionList(uint32_t count, const char* const* names) {
    std::string s;
    for (uint32_t i = 0; i < count; i++) s += std::string(i ? " " : "") + names[i];
    return s;
}

}  // namespace

void enable(bool colorSpaceOffered) {
    if (g_enabled.exchange(true)) return;
    std::lock_guard<std::mutex> lock(g_lock);
    note(std::string("survey on; the driver ") + (colorSpaceOffered ? "offers" : "does not offer") + " " +
         VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
}

bool enabled() { return g_enabled.load(); }

void instanceCreated(const VkInstanceCreateInfo* info, const char* colorSpace, VkResult result, VkInstance instance,
                     PFN_vkGetInstanceProcAddr gipa) {
    const VkApplicationInfo* app = info->pApplicationInfo;
    std::string line = "instance: api " +
                       (app ? std::to_string(VK_API_VERSION_MAJOR(app->apiVersion)) + "." +
                                  std::to_string(VK_API_VERSION_MINOR(app->apiVersion))
                            : std::string("?")) +
                       ", app " + (app && app->pApplicationName ? app->pApplicationName : "?") + ", engine " +
                       (app && app->pEngineName ? app->pEngineName : "?") + ", extensions: " +
                       extensionList(info->enabledExtensionCount, info->ppEnabledExtensionNames) + "; " +
                       VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME + " " + colorSpace + "; result " +
                       std::to_string(result);
    std::lock_guard<std::mutex> lock(g_lock);
    note(line);
    if (result == VK_SUCCESS) {
        g_instances[key(instance)] = Instance{(PFN_vkCreateWin32SurfaceKHR)gipa(instance, "vkCreateWin32SurfaceKHR")};
    }
}
void deviceCreated(VkPhysicalDevice gpu, VkDevice dev, const VkDeviceCreateInfo* info, PFN_vkGetDeviceProcAddr gdpa,
                   PFN_vkGetInstanceProcAddr gipa, VkInstance instance) {
    Device d;
    d.gpu = gpu;
    d.instance = instance;
    d.gipa = gipa;
    d.createSwapchain = resolve<PFN_vkCreateSwapchainKHR>(gdpa, dev, "vkCreateSwapchainKHR");
    d.getSwapchainImages = resolve<PFN_vkGetSwapchainImagesKHR>(gdpa, dev, "vkGetSwapchainImagesKHR");
    d.createImage = resolve<PFN_vkCreateImage>(gdpa, dev, "vkCreateImage");
    d.destroyImage = resolve<PFN_vkDestroyImage>(gdpa, dev, "vkDestroyImage");
    d.allocateMemory = resolve<PFN_vkAllocateMemory>(gdpa, dev, "vkAllocateMemory");
    d.freeMemory = resolve<PFN_vkFreeMemory>(gdpa, dev, "vkFreeMemory");
    d.bindImageMemory = resolve<PFN_vkBindImageMemory>(gdpa, dev, "vkBindImageMemory");
    d.bindImageMemory2 = resolve<PFN_vkBindImageMemory2>(gdpa, dev, "vkBindImageMemory2", "vkBindImageMemory2KHR");
    d.createImageView = resolve<PFN_vkCreateImageView>(gdpa, dev, "vkCreateImageView");
    d.destroyImageView = resolve<PFN_vkDestroyImageView>(gdpa, dev, "vkDestroyImageView");
    d.createFramebuffer = resolve<PFN_vkCreateFramebuffer>(gdpa, dev, "vkCreateFramebuffer");
    d.destroyFramebuffer = resolve<PFN_vkDestroyFramebuffer>(gdpa, dev, "vkDestroyFramebuffer");
    d.cmdBeginRenderPass = resolve<PFN_vkCmdBeginRenderPass>(gdpa, dev, "vkCmdBeginRenderPass");
    d.cmdBeginRenderPass2 =
        resolve<PFN_vkCmdBeginRenderPass2>(gdpa, dev, "vkCmdBeginRenderPass2", "vkCmdBeginRenderPass2KHR");
    d.cmdBeginRendering = resolve<PFN_vkCmdBeginRendering>(gdpa, dev, "vkCmdBeginRendering", "vkCmdBeginRenderingKHR");
    d.cmdBlitImage = resolve<PFN_vkCmdBlitImage>(gdpa, dev, "vkCmdBlitImage");
    d.cmdBlitImage2 = resolve<PFN_vkCmdBlitImage2>(gdpa, dev, "vkCmdBlitImage2", "vkCmdBlitImage2KHR");
    d.cmdCopyImage = resolve<PFN_vkCmdCopyImage>(gdpa, dev, "vkCmdCopyImage");
    d.cmdCopyImage2 = resolve<PFN_vkCmdCopyImage2>(gdpa, dev, "vkCmdCopyImage2", "vkCmdCopyImage2KHR");
    d.cmdResolveImage = resolve<PFN_vkCmdResolveImage>(gdpa, dev, "vkCmdResolveImage");
    d.cmdClearColorImage = resolve<PFN_vkCmdClearColorImage>(gdpa, dev, "vkCmdClearColorImage");
    d.cmdCopyBufferToImage = resolve<PFN_vkCmdCopyBufferToImage>(gdpa, dev, "vkCmdCopyBufferToImage");
    d.cmdCopyImageToBuffer = resolve<PFN_vkCmdCopyImageToBuffer>(gdpa, dev, "vkCmdCopyImageToBuffer");
    d.updateDescriptorSets = resolve<PFN_vkUpdateDescriptorSets>(gdpa, dev, "vkUpdateDescriptorSets");
    d.createDescriptorUpdateTemplate = resolve<PFN_vkCreateDescriptorUpdateTemplate>(
        gdpa, dev, "vkCreateDescriptorUpdateTemplate", "vkCreateDescriptorUpdateTemplateKHR");
    d.destroyDescriptorUpdateTemplate = resolve<PFN_vkDestroyDescriptorUpdateTemplate>(
        gdpa, dev, "vkDestroyDescriptorUpdateTemplate", "vkDestroyDescriptorUpdateTemplateKHR");
    d.updateDescriptorSetWithTemplate = resolve<PFN_vkUpdateDescriptorSetWithTemplate>(
        gdpa, dev, "vkUpdateDescriptorSetWithTemplate", "vkUpdateDescriptorSetWithTemplateKHR");

    VkPhysicalDeviceProperties props{};
    auto getProps = (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
    if (getProps) getProps(gpu, &props);
    bool hdrMetadata = false;
    auto enumerate = (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    uint32_t n = 0;
    if (enumerate && enumerate(gpu, nullptr, &n, nullptr) == VK_SUCCESS) {
        std::vector<VkExtensionProperties> exts(n);
        if (enumerate(gpu, nullptr, &n, exts.data()) >= VK_SUCCESS) {
            for (uint32_t i = 0; i < n; i++) hdrMetadata |= !strcmp(exts[i].extensionName, VK_EXT_HDR_METADATA_EXTENSION_NAME);
        }
    }
    std::string line = std::string("device ") + props.deviceName + ": extensions: " +
                       extensionList(info->enabledExtensionCount, info->ppEnabledExtensionNames) + "; " +
                       VK_EXT_HDR_METADATA_EXTENSION_NAME + (hdrMetadata ? " offered" : " not offered");
    std::lock_guard<std::mutex> lock(g_lock);
    g_devices[key(dev)] = d;
    note(line);
}

void deviceDestroyed(VkDevice dev) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_devices.erase(key(dev));
}

PFN_vkVoidFunction deviceHook(VkDevice dev, const char* name) {
    if (!g_enabled.load() || strncmp(name, "vk", 2) != 0) return nullptr;
    const Device* d;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        auto it = g_devices.find(key(dev));
        if (it == g_devices.end()) return nullptr;
        d = &it->second;
    }
    struct Hook {
        const char* name;
        PFN_vkVoidFunction hook;
        bool present;
    };
#define RRP_HOOK(fn, field) {"vk" #fn, (PFN_vkVoidFunction)fn, d->field != nullptr}
#define RRP_ALIAS(fn, suffix, field) {"vk" #fn #suffix, (PFN_vkVoidFunction)fn, d->field != nullptr}
    const Hook hooks[] = {
        RRP_HOOK(CreateSwapchainKHR, createSwapchain),
        RRP_HOOK(GetSwapchainImagesKHR, getSwapchainImages),
        RRP_HOOK(CreateImage, createImage),
        RRP_HOOK(DestroyImage, destroyImage),
        RRP_HOOK(AllocateMemory, allocateMemory),
        RRP_HOOK(FreeMemory, freeMemory),
        RRP_HOOK(BindImageMemory, bindImageMemory),
        RRP_HOOK(BindImageMemory2, bindImageMemory2),
        RRP_ALIAS(BindImageMemory2, KHR, bindImageMemory2),
        RRP_HOOK(CreateImageView, createImageView),
        RRP_HOOK(DestroyImageView, destroyImageView),
        RRP_HOOK(CreateFramebuffer, createFramebuffer),
        RRP_HOOK(DestroyFramebuffer, destroyFramebuffer),
        RRP_HOOK(CmdBeginRenderPass, cmdBeginRenderPass),
        RRP_HOOK(CmdBeginRenderPass2, cmdBeginRenderPass2),
        RRP_ALIAS(CmdBeginRenderPass2, KHR, cmdBeginRenderPass2),
        RRP_HOOK(CmdBeginRendering, cmdBeginRendering),
        RRP_ALIAS(CmdBeginRendering, KHR, cmdBeginRendering),
        RRP_HOOK(CmdBlitImage, cmdBlitImage),
        RRP_HOOK(CmdBlitImage2, cmdBlitImage2),
        RRP_ALIAS(CmdBlitImage2, KHR, cmdBlitImage2),
        RRP_HOOK(CmdCopyImage, cmdCopyImage),
        RRP_HOOK(CmdCopyImage2, cmdCopyImage2),
        RRP_ALIAS(CmdCopyImage2, KHR, cmdCopyImage2),
        RRP_HOOK(CmdResolveImage, cmdResolveImage),
        RRP_HOOK(CmdClearColorImage, cmdClearColorImage),
        RRP_HOOK(CmdCopyBufferToImage, cmdCopyBufferToImage),
        RRP_HOOK(CmdCopyImageToBuffer, cmdCopyImageToBuffer),
        RRP_HOOK(UpdateDescriptorSets, updateDescriptorSets),
        RRP_HOOK(CreateDescriptorUpdateTemplate, createDescriptorUpdateTemplate),
        RRP_ALIAS(CreateDescriptorUpdateTemplate, KHR, createDescriptorUpdateTemplate),
        RRP_HOOK(DestroyDescriptorUpdateTemplate, destroyDescriptorUpdateTemplate),
        RRP_ALIAS(DestroyDescriptorUpdateTemplate, KHR, destroyDescriptorUpdateTemplate),
        RRP_HOOK(UpdateDescriptorSetWithTemplate, updateDescriptorSetWithTemplate),
        RRP_ALIAS(UpdateDescriptorSetWithTemplate, KHR, updateDescriptorSetWithTemplate),
    };
#undef RRP_HOOK
#undef RRP_ALIAS
    for (const Hook& h : hooks) {
        if (!strcmp(name, h.name)) return h.present ? h.hook : nullptr;
    }
    return nullptr;
}

PFN_vkVoidFunction instanceHook(VkInstance instance, const char* name) {
    if (!g_enabled.load() || instance == VK_NULL_HANDLE || strcmp(name, "vkCreateWin32SurfaceKHR") != 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_lock);
    auto it = g_instances.find(key(instance));
    return it != g_instances.end() && it->second.createWin32Surface ? (PFN_vkVoidFunction)CreateWin32SurfaceKHR : nullptr;
}

std::string take(bool counts) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::string out;
    out.swap(g_pending);
    if (counts) {
        for (const auto& [kind, n] : g_counts) out += "x" + std::to_string(n) + " " + kind + "\n";
    }
    return out;
}

}  // namespace rrp::survey
