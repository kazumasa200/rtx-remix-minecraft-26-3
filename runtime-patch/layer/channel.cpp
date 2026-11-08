// SPDX-License-Identifier: Apache-2.0
//
// The frame channel (channel.h). The mod writes a frame block through rrp_publish_frame and textures through
// rrp_upload; at the first graphics-queue submit after a present, the layer latches the latest frame into the
// next of kSlots host-visible ring slots and submits its own command buffer first, in its own vkQueueSubmit
// with the slot's fence: it copies the slot into the device-local frame buffer and any pending texture upload,
// between barriers whose scopes cover every command before and after it on that queue. Every pass of the
// runtime's frame then reads one frame block, and no slot is rewritten before its fence signals.
#include "channel.h"

#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <unordered_set>
#include <utility>
#include <vector>

namespace rrp {
namespace {

constexpr uint64_t kFenceWaitNanos = 50'000'000;
constexpr uint32_t kStaleLatches = 30;

#define RRP_FNS(X)                                                                                             \
    X(CreatePipelineLayout) X(CmdBindDescriptorSets) X(QueueSubmit) X(QueuePresentKHR) X(GetDeviceQueue)     \
    X(GetDeviceQueue2) X(DeviceWaitIdle) X(CreateBuffer) X(DestroyBuffer) X(CreateImage) X(DestroyImage)       \
    X(CreateImageView) X(DestroyImageView) X(CreateSampler) X(DestroySampler) X(AllocateMemory) X(FreeMemory)   \
    X(MapMemory) X(BindBufferMemory) X(BindImageMemory) X(GetBufferMemoryRequirements)                          \
    X(GetImageMemoryRequirements) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout)                    \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets)          \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer)                  \
    X(EndCommandBuffer) X(ResetCommandBuffer) X(CmdPipelineBarrier) X(CmdCopyBuffer) X(CmdCopyBufferToImage)   \
    X(CmdFillBuffer) X(CmdClearColorImage) X(CreateFence) X(DestroyFence) X(WaitForFences) X(ResetFences)        \
    X(GetFenceStatus) X(CreateShaderModule) X(DestroyShaderModule) X(CreateComputePipelines) X(DestroyPipeline)    \
    X(DestroyPipelineLayout) X(CmdBindPipeline) X(CmdPushConstants) X(CmdDispatch)

struct Fns {
#define RRP_DECLARE(name) PFN_vk##name name = nullptr;
    RRP_FNS(RRP_DECLARE)
#undef RRP_DECLARE
};

// The mod's latest frame: one writer (the render thread), readers on the runtime's submit thread. A sequence
// lock; a reader that keeps meeting a write keeps the slot's previous frame.
class Mailbox {
public:
    int32_t write(const void* bytes, uint32_t size) {
        if (size == 0 || size > kMaxFrameBytes) return -1;
        seq_.fetch_add(1, std::memory_order_acq_rel);  // odd: a write is in progress
        std::atomic_thread_fence(std::memory_order_release);
        memset(data_, 0, kMaxFrameBytes);
        memcpy(data_, bytes, size);
        std::atomic_thread_fence(std::memory_order_release);
        seq_.fetch_add(1, std::memory_order_release);
        return 0;
    }

    // The latest frame and its generation (frames written since load); false when none was written or a
    // write was always in progress.
    bool read(uint8_t* out, uint32_t& generation) const {
        for (int tries = 0; tries < 8; tries++) {
            uint32_t s0 = seq_.load(std::memory_order_acquire);
            if (s0 == 0) return false;
            if (s0 & 1) continue;
            memcpy(out, data_, kMaxFrameBytes);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (seq_.load(std::memory_order_relaxed) == s0) {
                generation = s0 / 2;
                return true;
            }
        }
        return false;
    }

private:
    std::atomic<uint32_t> seq_{0};
    alignas(16) uint8_t data_[kMaxFrameBytes] = {};
};

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageType type = VK_IMAGE_TYPE_2D;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent3D extent{};
    uint32_t texelBytes = 0;
    uint64_t bytes() const { return (uint64_t)extent.width * extent.height * extent.depth * texelBytes; }
};

Mailbox g_mailbox;
std::mutex g_uploadsLock;
std::array<std::vector<uint8_t>, kBindingCount> g_uploads;  // pending texture contents by binding
std::atomic<Channel*> g_live{nullptr};

}  // namespace

struct Channel {
    std::atomic<int> state{0};  // 0 down, 1 up, 2 device lost
    VkDevice device = VK_NULL_HANDLE;
    Fns fn;
    PFN_vkSetDeviceLoaderData setLoaderData = nullptr;
    VkPhysicalDeviceMemoryProperties memory{};
    std::vector<uint32_t> families;
    uint32_t graphicsFamily = UINT32_MAX;

    VkDescriptorSetLayout emptyLayout = VK_NULL_HANDLE, setLayout = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkBuffer frame = VK_NULL_HANDLE, ring = VK_NULL_HANDLE, staging = VK_NULL_HANDLE;
    VkDeviceMemory frameMemory = VK_NULL_HANDLE, ringMemory = VK_NULL_HANDLE, stagingMemory = VK_NULL_HANDLE;
    uint8_t *ringMapped = nullptr, *stagingMapped = nullptr;
    uint64_t stagingBytes = 0;
    Image noise, cirrus, skyCache, shadow;
    VkSampler repeatLinear = VK_NULL_HANDLE, clampLinear = VK_NULL_HANDLE;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    struct Slot {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
    };
    std::array<Slot, kSlots> slots{};
    // Touched only inside the submit hook, which the runtime calls with the queue externally synchronized.
    uint32_t cursor = 0;
    int32_t stagingSlot = -1;
    bool initialised = false;

    // The layer's own pass (rrp_cloud_pass.comp): the sky cache (mode 1) and the shadow map (mode 2).
    VkPipelineLayout passLayout = VK_NULL_HANDLE;
    VkPipeline pass = VK_NULL_HANDLE;
    uint32_t lastGeneration = 0, staleLatches = 0;

    std::atomic<bool> boundary{true};
    std::mutex queuesLock;
    std::vector<std::pair<VkQueue, uint32_t>> queues;
    std::mutex layoutsLock;
    std::unordered_set<VkPipelineLayout> extended;

    std::atomic<uint32_t> layoutsExtended{0}, layoutsRefused{0}, setBinds{0}, framesLatched{0}, submitsSkipped{0},
        uploads{0};
};

namespace {

int32_t memoryType(const Channel* c, uint32_t bits, VkMemoryPropertyFlags wanted) {
    for (uint32_t i = 0; i < c->memory.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (c->memory.memoryTypes[i].propertyFlags & wanted) == wanted) return (int32_t)i;
    }
    return -1;
}

bool allocate(Channel* c, VkMemoryRequirements req, VkMemoryPropertyFlags props, VkDeviceMemory& out) {
    int32_t type = memoryType(c, req.memoryTypeBits, props);
    if (type < 0) return false;
    VkMemoryAllocateInfo info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, req.size, (uint32_t)type};
    return c->fn.AllocateMemory(c->device, &info, nullptr, &out) == VK_SUCCESS;
}

void share(const Channel* c, VkSharingMode& mode, uint32_t& count, const uint32_t*& families) {
    if (c->families.size() > 1) {
        mode = VK_SHARING_MODE_CONCURRENT;
        count = (uint32_t)c->families.size();
        families = c->families.data();
    } else {
        mode = VK_SHARING_MODE_EXCLUSIVE;
        count = 0;
        families = nullptr;
    }
}

bool makeBuffer(Channel* c, uint64_t size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props, VkBuffer& buffer,
                VkDeviceMemory& memory, uint8_t** mapped) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = usage;
    share(c, info.sharingMode, info.queueFamilyIndexCount, info.pQueueFamilyIndices);
    if (c->fn.CreateBuffer(c->device, &info, nullptr, &buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    c->fn.GetBufferMemoryRequirements(c->device, buffer, &req);
    if (!allocate(c, req, props, memory) || c->fn.BindBufferMemory(c->device, buffer, memory, 0) != VK_SUCCESS) {
        return false;
    }
    if (mapped && c->fn.MapMemory(c->device, memory, 0, VK_WHOLE_SIZE, 0, (void**)mapped) != VK_SUCCESS) return false;
    return true;
}

bool makeImage(Channel* c, Image& im, VkImageType type, VkFormat format, VkExtent3D extent, uint32_t texelBytes,
               VkImageUsageFlags usage) {
    im.type = type;
    im.format = format;
    im.extent = extent;
    im.texelBytes = texelBytes;
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = type;
    info.format = format;
    info.extent = extent;
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    share(c, info.sharingMode, info.queueFamilyIndexCount, info.pQueueFamilyIndices);
    if (c->fn.CreateImage(c->device, &info, nullptr, &im.image) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    c->fn.GetImageMemoryRequirements(c->device, im.image, &req);
    if (!allocate(c, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, im.memory) ||
        c->fn.BindImageMemory(c->device, im.image, im.memory, 0) != VK_SUCCESS) {
        return false;
    }
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = im.image;
    view.viewType = type == VK_IMAGE_TYPE_3D ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D;
    view.format = format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return c->fn.CreateImageView(c->device, &view, nullptr, &im.view) == VK_SUCCESS;
}

void dropImage(Channel* c, Image& im) {
    if (im.view) c->fn.DestroyImageView(c->device, im.view, nullptr);
    if (im.image) c->fn.DestroyImage(c->device, im.image, nullptr);
    if (im.memory) c->fn.FreeMemory(c->device, im.memory, nullptr);
    im = Image{};
}

bool makeSampler(Channel* c, VkSamplerAddressMode mode, VkSampler& out) {
    VkSamplerCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    info.magFilter = info.minFilter = VK_FILTER_LINEAR;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    info.addressModeU = info.addressModeV = info.addressModeW = mode;
    info.maxLod = 0.0f;
    return c->fn.CreateSampler(c->device, &info, nullptr, &out) == VK_SUCCESS;
}

Image* imageOf(Channel* c, uint32_t binding) {
    switch (binding) {
        case kNoise: return &c->noise;
        case kCirrus: return &c->cirrus;
        default: return nullptr;
    }
}

bool build(Channel* c, std::string& why) {
    VkDescriptorSetLayoutCreateInfo empty{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    if (c->fn.CreateDescriptorSetLayout(c->device, &empty, nullptr, &c->emptyLayout) != VK_SUCCESS) {
        why = "empty set layout";
        return false;
    }
    const VkShaderStageFlags reads = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_RAYGEN_BIT_KHR |
                                     VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR |
                                     VK_SHADER_STAGE_ANY_HIT_BIT_KHR | VK_SHADER_STAGE_INTERSECTION_BIT_KHR |
                                     VK_SHADER_STAGE_CALLABLE_BIT_KHR;
    VkDescriptorSetLayoutBinding bindings[kBindingCount] = {
        {kFrame, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, reads, nullptr},
        {kNoise, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, reads, nullptr},
        {kCirrus, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, reads, nullptr},
        {kSkyCache, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, reads, nullptr},
        {kShadow, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, reads, nullptr},
        {kSkyCacheStorage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {kShadowStorage, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
    };
    VkDescriptorSetLayoutCreateInfo layout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layout.bindingCount = kBindingCount;
    layout.pBindings = bindings;
    if (c->fn.CreateDescriptorSetLayout(c->device, &layout, nullptr, &c->setLayout) != VK_SUCCESS) {
        why = "set layout";
        return false;
    }
    VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
                                    {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4},
                                    {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2}};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets = 1;
    pool.poolSizeCount = 3;
    pool.pPoolSizes = sizes;
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    if (c->fn.CreateDescriptorPool(c->device, &pool, nullptr, &c->pool) != VK_SUCCESS) {
        why = "descriptor pool";
        return false;
    }
    alloc.descriptorPool = c->pool;
    alloc.descriptorSetCount = 1;
    alloc.pSetLayouts = &c->setLayout;
    if (c->fn.AllocateDescriptorSets(c->device, &alloc, &c->set) != VK_SUCCESS) {
        why = "descriptor set";
        return false;
    }

    const VkMemoryPropertyFlags host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    c->stagingBytes = (uint64_t)kNoiseSize * kNoiseSize * kNoiseSize * 4 + (uint64_t)kCirrusSize * kCirrusSize;
    if (!makeBuffer(c, kMaxFrameBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, c->frame, c->frameMemory, nullptr) ||
        !makeBuffer(c, (uint64_t)kSlots * kMaxFrameBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, host, c->ring,
                    c->ringMemory, &c->ringMapped) ||
        !makeBuffer(c, c->stagingBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, host, c->staging, c->stagingMemory,
                    &c->stagingMapped)) {
        why = "buffers";
        return false;
    }
    memset(c->ringMapped, 0, (size_t)kSlots * kMaxFrameBytes);
    if (!makeImage(c, c->noise, VK_IMAGE_TYPE_3D, VK_FORMAT_R8G8B8A8_UNORM, {kNoiseSize, kNoiseSize, kNoiseSize}, 4, 0) ||
        !makeImage(c, c->cirrus, VK_IMAGE_TYPE_2D, VK_FORMAT_R8_UNORM, {kCirrusSize, kCirrusSize, 1}, 1, 0) ||
        !makeImage(c, c->skyCache, VK_IMAGE_TYPE_2D, VK_FORMAT_R16G16B16A16_SFLOAT, {kSkyCacheSize, kSkyCacheSize, 1}, 8,
                   VK_IMAGE_USAGE_STORAGE_BIT) ||
        !makeImage(c, c->shadow, VK_IMAGE_TYPE_2D, VK_FORMAT_R16G16B16A16_SFLOAT, {kShadowSize, kShadowSize, 1}, 8,
                   VK_IMAGE_USAGE_STORAGE_BIT)) {
        why = "images";
        return false;
    }
    if (!makeSampler(c, VK_SAMPLER_ADDRESS_MODE_REPEAT, c->repeatLinear) ||
        !makeSampler(c, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, c->clampLinear)) {
        why = "samplers";
        return false;
    }

    VkDescriptorBufferInfo frameInfo{c->frame, 0, kMaxFrameBytes};
    VkDescriptorImageInfo images[] = {
        {c->repeatLinear, c->noise.view, VK_IMAGE_LAYOUT_GENERAL},
        {c->clampLinear, c->cirrus.view, VK_IMAGE_LAYOUT_GENERAL},
        {c->clampLinear, c->skyCache.view, VK_IMAGE_LAYOUT_GENERAL},
        {c->clampLinear, c->shadow.view, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, c->skyCache.view, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, c->shadow.view, VK_IMAGE_LAYOUT_GENERAL},
    };
    VkWriteDescriptorSet writes[kBindingCount] = {};
    for (uint32_t b = 0; b < kBindingCount; b++) {
        writes[b].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[b].dstSet = c->set;
        writes[b].dstBinding = b;
        writes[b].descriptorCount = 1;
        writes[b].descriptorType = bindings[b].descriptorType;
        if (b == kFrame) {
            writes[b].pBufferInfo = &frameInfo;
        } else {
            writes[b].pImageInfo = &images[b - 1];
        }
    }
    c->fn.UpdateDescriptorSets(c->device, kBindingCount, writes, 0, nullptr);

    if (c->graphicsFamily == UINT32_MAX) {
        why = "no graphics queue family";
        return false;
    }
    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cp.queueFamilyIndex = c->graphicsFamily;
    if (c->fn.CreateCommandPool(c->device, &cp, nullptr, &c->commandPool) != VK_SUCCESS) {
        why = "command pool";
        return false;
    }
    for (auto& slot : c->slots) {
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = c->commandPool;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT};
        if (c->fn.AllocateCommandBuffers(c->device, &ca, &slot.cmd) != VK_SUCCESS ||
            (c->setLoaderData && c->setLoaderData(c->device, slot.cmd) != VK_SUCCESS) ||
            c->fn.CreateFence(c->device, &fc, nullptr, &slot.fence) != VK_SUCCESS) {
            why = "command buffers";
            return false;
        }
    }
    return true;
}

// The layer's pass pipeline from the program the manifest named; without it the sky cache and the shadow map
// stay empty (no clouds in reflections, no cloud shadows) and the channel still carries the frame.
bool buildPass(Channel* c, const std::vector<uint32_t>& program, std::string& report) {
    if (program.empty()) {
        report += "; no cloud pass";
        return false;
    }
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = program.size() * 4;
    mi.pCode = program.data();
    VkShaderModule module = VK_NULL_HANDLE;
    if (c->fn.CreateShaderModule(c->device, &mi, nullptr, &module) != VK_SUCCESS) {
        report += "; cloud pass refused";
        return false;
    }
    VkDescriptorSetLayout sets[kFrameSet + 1] = {c->emptyLayout, c->emptyLayout, c->emptyLayout, c->emptyLayout,
                                                 c->setLayout};
    VkPushConstantRange mode{VK_SHADER_STAGE_COMPUTE_BIT, 0, 4};
    VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    li.setLayoutCount = kFrameSet + 1;
    li.pSetLayouts = sets;
    li.pushConstantRangeCount = 1;
    li.pPushConstantRanges = &mode;
    bool ok = c->fn.CreatePipelineLayout(c->device, &li, nullptr, &c->passLayout) == VK_SUCCESS;
    if (ok) {
        VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, module,
                    "main", nullptr};
        pi.layout = c->passLayout;
        ok = c->fn.CreateComputePipelines(c->device, VK_NULL_HANDLE, 1, &pi, nullptr, &c->pass) == VK_SUCCESS;
    }
    c->fn.DestroyShaderModule(c->device, module, nullptr);
    report += ok ? "; cloud pass up" : "; cloud pass failed";
    return ok;
}

void dispatchPass(Channel* c, VkCommandBuffer cmd, uint32_t mode, uint32_t size) {
    c->fn.CmdPushConstants(cmd, c->passLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 4, &mode);
    c->fn.CmdDispatch(cmd, (size + 7) / 8, (size + 7) / 8, 1);
}

void barrier(Channel* c, VkCommandBuffer cmd, VkPipelineStageFlags src, VkAccessFlags srcAccess,
             VkPipelineStageFlags dst, VkAccessFlags dstAccess) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, srcAccess, dstAccess};
    c->fn.CmdPipelineBarrier(cmd, src, dst, 0, srcAccess || dstAccess ? 1 : 0, &mb, 0, nullptr, 0, nullptr);
}

// Slot k's commands for this frame; uploads[b] holds the staging offset of a texture copied this frame.
void record(Channel* c, uint32_t k, const std::array<int64_t, kBindingCount>& uploads) {
    VkCommandBuffer cmd = c->slots[k].cmd;
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    c->fn.BeginCommandBuffer(cmd, &begin);
    // The previous frames' readers finish before this frame overwrites what they read (write after read).
    barrier(c, cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0);
    if (!c->initialised) {
        c->fn.CmdFillBuffer(cmd, c->frame, 0, VK_WHOLE_SIZE, 0);
        Image* all[] = {&c->noise, &c->cirrus, &c->skyCache, &c->shadow};
        VkImageMemoryBarrier ib[4] = {};
        for (int i = 0; i < 4; i++) {
            ib[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            ib[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            ib[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            ib[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            ib[i].srcQueueFamilyIndex = ib[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            ib[i].image = all[i]->image;
            ib[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        c->fn.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
                                 0, nullptr, 4, ib);
        VkClearColorValue zero{};
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        for (Image* im : all) c->fn.CmdClearColorImage(cmd, im->image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
        barrier(c, cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT);
        c->initialised = true;
    }
    for (uint32_t b = 0; b < kBindingCount; b++) {
        Image* im = imageOf(c, b);
        if (uploads[b] < 0 || im == nullptr) continue;
        VkBufferImageCopy copy{};
        copy.bufferOffset = (VkDeviceSize)uploads[b];
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = im->extent;
        c->fn.CmdCopyBufferToImage(cmd, c->staging, im->image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
    }
    VkBufferCopy frameCopy{(VkDeviceSize)k * kMaxFrameBytes, 0, kMaxFrameBytes};
    c->fn.CmdCopyBuffer(cmd, c->ring, c->frame, 1, &frameCopy);
    if (c->pass != VK_NULL_HANDLE) {
        barrier(c, cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        c->fn.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c->pass);
        c->fn.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c->passLayout, kFrameSet, 1, &c->set, 0,
                                    nullptr);
        dispatchPass(c, cmd, 1, kSkyCacheSize);
        dispatchPass(c, cmd, 2, kShadowSize);
    }
    // Every later command on the queue, the runtime's frame among them, sees this frame's block and images.
    barrier(c, cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
    c->fn.EndCommandBuffer(cmd);
}

uint32_t familyOf(Channel* c, VkQueue queue) {
    std::lock_guard<std::mutex> lock(c->queuesLock);
    for (auto& [q, family] : c->queues) {
        if (q == queue) return family;
    }
    return UINT32_MAX;
}

// The pending textures that fit, copied into staging; offsets by binding, -1 for none.
std::array<int64_t, kBindingCount> takeUploads(Channel* c, uint32_t k) {
    std::array<int64_t, kBindingCount> at;
    at.fill(-1);
    if (c->stagingSlot >= 0 && c->fn.GetFenceStatus(c->device, c->slots[c->stagingSlot].fence) != VK_SUCCESS) return at;
    std::unique_lock<std::mutex> lock(g_uploadsLock, std::try_to_lock);
    if (!lock.owns_lock()) return at;
    uint64_t offset = 0;
    for (uint32_t b = 0; b < kBindingCount; b++) {
        Image* im = imageOf(c, b);
        if (im == nullptr || g_uploads[b].empty()) continue;
        if (g_uploads[b].size() != im->bytes() || offset + im->bytes() > c->stagingBytes) {
            g_uploads[b].clear();
            continue;
        }
        memcpy(c->stagingMapped + offset, g_uploads[b].data(), g_uploads[b].size());
        at[b] = (int64_t)offset;
        offset += (im->bytes() + 15) / 16 * 16;
        g_uploads[b].clear();
        c->uploads++;
    }
    if (offset > 0) c->stagingSlot = (int32_t)k;
    return at;
}

}  // namespace

Channel* createChannel(VkPhysicalDevice gpu, VkDevice device, const VkDeviceCreateInfo* info,
                       PFN_vkGetDeviceProcAddr gdpa, PFN_vkGetInstanceProcAddr gipa, VkInstance instance,
                       PFN_vkSetDeviceLoaderData setLoaderData, const std::vector<uint32_t>& program,
                       std::string& report) {
    auto* c = new Channel();
    c->device = device;
    c->setLoaderData = setLoaderData;
#define RRP_LOAD(name) c->fn.name = (PFN_vk##name)gdpa(device, "vk" #name);
    RRP_FNS(RRP_LOAD)
#undef RRP_LOAD
    bool loaded = true;
#define RRP_CHECK(name) loaded = loaded && c->fn.name != nullptr;
    RRP_FNS(RRP_CHECK)
#undef RRP_CHECK
    auto memoryProps = (PFN_vkGetPhysicalDeviceMemoryProperties)gipa(instance, "vkGetPhysicalDeviceMemoryProperties");
    auto familyProps =
        (PFN_vkGetPhysicalDeviceQueueFamilyProperties)gipa(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    std::string why = "device functions";
    if (loaded && memoryProps && familyProps) {
        memoryProps(gpu, &c->memory);
        uint32_t count = 0;
        familyProps(gpu, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        familyProps(gpu, &count, families.data());
        for (uint32_t i = 0; i < info->queueCreateInfoCount; i++) {
            uint32_t f = info->pQueueCreateInfos[i].queueFamilyIndex;
            c->families.push_back(f);
            if (c->graphicsFamily == UINT32_MAX && f < count && (families[f].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                c->graphicsFamily = f;
            }
        }
        if (build(c, why)) {
            c->state = 1;
            g_live.store(c, std::memory_order_release);
            report += "; channel up";
            buildPass(c, program, report);
            return c;
        }
    }
    report += "; channel down (" + why + ")";
    return c;
}

void destroyChannel(Channel* c) {
    if (c == nullptr) return;
    Channel* live = c;
    g_live.compare_exchange_strong(live, nullptr);
    if (c->fn.DeviceWaitIdle) c->fn.DeviceWaitIdle(c->device);
    for (auto& slot : c->slots) {
        if (slot.fence) c->fn.DestroyFence(c->device, slot.fence, nullptr);
    }
    if (c->commandPool) c->fn.DestroyCommandPool(c->device, c->commandPool, nullptr);
    if (c->pass) c->fn.DestroyPipeline(c->device, c->pass, nullptr);
    if (c->passLayout) c->fn.DestroyPipelineLayout(c->device, c->passLayout, nullptr);
    if (c->repeatLinear) c->fn.DestroySampler(c->device, c->repeatLinear, nullptr);
    if (c->clampLinear) c->fn.DestroySampler(c->device, c->clampLinear, nullptr);
    for (Image* im : {&c->noise, &c->cirrus, &c->skyCache, &c->shadow}) {
        if (im->image || im->view || im->memory) dropImage(c, *im);
    }
    for (auto [buffer, memory] : {std::pair{c->frame, c->frameMemory}, std::pair{c->ring, c->ringMemory},
                                  std::pair{c->staging, c->stagingMemory}}) {
        if (buffer) c->fn.DestroyBuffer(c->device, buffer, nullptr);
        if (memory) c->fn.FreeMemory(c->device, memory, nullptr);
    }
    if (c->pool) c->fn.DestroyDescriptorPool(c->device, c->pool, nullptr);
    if (c->setLayout) c->fn.DestroyDescriptorSetLayout(c->device, c->setLayout, nullptr);
    if (c->emptyLayout) c->fn.DestroyDescriptorSetLayout(c->device, c->emptyLayout, nullptr);
    delete c;
}

bool channelUp(const Channel* c) { return c != nullptr && c->state.load() == 1; }

VkResult createPipelineLayout(Channel* c, VkDevice device, const VkPipelineLayoutCreateInfo* info,
                              const VkAllocationCallbacks* alloc, VkPipelineLayout* out) {
    if (!channelUp(c) || info->setLayoutCount > kFrameSet) return c->fn.CreatePipelineLayout(device, info, alloc, out);
    VkDescriptorSetLayout sets[kFrameSet + 1];
    for (uint32_t i = 0; i < kFrameSet; i++) sets[i] = i < info->setLayoutCount ? info->pSetLayouts[i] : c->emptyLayout;
    sets[kFrameSet] = c->setLayout;
    VkPipelineLayoutCreateInfo extended = *info;
    extended.setLayoutCount = kFrameSet + 1;
    extended.pSetLayouts = sets;
    VkResult r = c->fn.CreatePipelineLayout(device, &extended, alloc, out);
    if (r != VK_SUCCESS) {
        c->layoutsRefused++;
        return c->fn.CreatePipelineLayout(device, info, alloc, out);
    }
    {
        std::lock_guard<std::mutex> lock(c->layoutsLock);
        c->extended.insert(*out);
    }
    c->layoutsExtended++;
    return r;
}

void cmdBindDescriptorSets(Channel* c, VkCommandBuffer cmd, VkPipelineBindPoint point, VkPipelineLayout layout,
                           uint32_t firstSet, uint32_t count, const VkDescriptorSet* sets, uint32_t dynamicCount,
                           const uint32_t* dynamicOffsets) {
    c->fn.CmdBindDescriptorSets(cmd, point, layout, firstSet, count, sets, dynamicCount, dynamicOffsets);
    if (point == VK_PIPELINE_BIND_POINT_GRAPHICS || !channelUp(c)) return;
    {
        std::lock_guard<std::mutex> lock(c->layoutsLock);
        if (!c->extended.count(layout)) return;
    }
    // Set 4 is above every set the runtime binds, so binding it disturbs none of theirs; a bind of theirs
    // with a layout incompatible below set 4 disturbs ours, so it is bound again after every bind.
    c->fn.CmdBindDescriptorSets(cmd, point, layout, kFrameSet, 1, &c->set, 0, nullptr);
    c->setBinds++;
}

VkResult queueSubmit(Channel* c, VkQueue queue, uint32_t count, const VkSubmitInfo* submits, VkFence fence) {
    if (channelUp(c) && familyOf(c, queue) == c->graphicsFamily && c->boundary.exchange(false)) {
        uint32_t k = c->cursor;
        VkResult wait = c->fn.WaitForFences(c->device, 1, &c->slots[k].fence, VK_TRUE, kFenceWaitNanos);
        if (wait == VK_TIMEOUT) {
            c->submitsSkipped++;
            c->boundary = true;
        } else if (wait != VK_SUCCESS) {
            c->state = 2;
        } else {
            uint8_t* slot = c->ringMapped + (size_t)k * kMaxFrameBytes;
            uint32_t generation = 0;
            if (g_mailbox.read(slot, generation)) {
                if (generation == c->lastGeneration) {
                    // A mod that stops sending must not leave its last frame on screen: zeros draw nothing.
                    if (++c->staleLatches > kStaleLatches) memset(slot, 0, kMaxFrameBytes);
                } else {
                    c->lastGeneration = generation;
                    c->staleLatches = 0;
                }
            }
            std::array<int64_t, kBindingCount> uploads = takeUploads(c, k);
            c->fn.ResetFences(c->device, 1, &c->slots[k].fence);
            c->fn.ResetCommandBuffer(c->slots[k].cmd, 0);
            record(c, k, uploads);
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &c->slots[k].cmd;
            // Inside the runtime's call, which holds the queue's external synchronization.
            if (c->fn.QueueSubmit(queue, 1, &submit, c->slots[k].fence) == VK_SUCCESS) {
                c->cursor = (k + 1) % kSlots;
                c->framesLatched++;
            } else {
                c->state = 2;
            }
        }
    }
    return c->fn.QueueSubmit(queue, count, submits, fence);
}

VkResult queuePresent(Channel* c, VkQueue queue, const VkPresentInfoKHR* info) {
    VkResult r = c->fn.QueuePresentKHR(queue, info);
    c->boundary.store(true);
    return r;
}

void getDeviceQueue(Channel* c, VkDevice device, uint32_t family, uint32_t index, VkQueue* out) {
    c->fn.GetDeviceQueue(device, family, index, out);
    std::lock_guard<std::mutex> lock(c->queuesLock);
    c->queues.emplace_back(*out, family);
}

void getDeviceQueue2(Channel* c, VkDevice device, const VkDeviceQueueInfo2* info, VkQueue* out) {
    c->fn.GetDeviceQueue2(device, info, out);
    std::lock_guard<std::mutex> lock(c->queuesLock);
    c->queues.emplace_back(*out, info->queueFamilyIndex);
}

void channelStatus(const Channel* c, uint32_t* out) {
    memset(out, 0, 8 * sizeof(uint32_t));
    if (c == nullptr) return;
    out[0] = (uint32_t)c->state.load();
    out[1] = c->layoutsExtended;
    out[2] = c->layoutsRefused;
    out[3] = c->setBinds;
    out[4] = c->framesLatched;
    out[5] = c->submitsSkipped;
    out[6] = c->uploads;
    out[7] = c->state.load() == 2 ? 1 : 0;
}

int32_t publishFrame(const void* bytes, uint32_t size) {
    if (g_live.load(std::memory_order_acquire) == nullptr) return -2;
    return g_mailbox.write(bytes, size);
}

int32_t upload(uint32_t binding, const void* bytes, uint64_t size) {
    uint64_t expected = binding == kNoise ? (uint64_t)kNoiseSize * kNoiseSize * kNoiseSize * 4
                        : binding == kCirrus ? (uint64_t)kCirrusSize * kCirrusSize
                                             : 0;
    if (expected == 0 || size != expected) return -1;
    if (g_live.load(std::memory_order_acquire) == nullptr) return -2;
    std::lock_guard<std::mutex> lock(g_uploadsLock);
    g_uploads[binding].assign((const uint8_t*)bytes, (const uint8_t*)bytes + size);
    return 0;
}

}  // namespace rrp
