// SPDX-License-Identifier: Apache-2.0
//
// The frame channel: what the mod hands the patched runtime shaders, as descriptor set 4. The layer owns every
// resource behind it on the runtime's own VkDevice and never interprets the frame's bytes; the shaders
// (runtime-patch/shaders/rrp_frame.glsl) define them. Four facts make set 4 safe inside the pinned runtime
// (minecraft-rtx2-a00da12a): its modules use sets 0-3 only, it binds only through vkCmdBindDescriptorSets (no
// push descriptors, no descriptor buffers, no vkCmdBindDescriptorSets2), it submits through vkQueueSubmit, and
// every pipeline layout is created after vkCreateDevice.
#pragma once
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>
#include <vector>

namespace rrp {

constexpr uint32_t kFrameSet = 4;          // the first set no runtime module uses
constexpr uint32_t kMaxFrameBytes = 4096;  // the frame block, zero-padded to this
constexpr uint32_t kSlots = 4;             // the layer's per-frame command buffers in flight

// Set 4's bindings. The runtime's pipelines read 0-4; 5 and 6 are storage views for the layer's own pass.
enum Binding : uint32_t {
    kFrame = 0,        // uniform buffer, the frame block
    kNoise = 1,        // 3D RGBA8, 128^3, repeat, linear (the GL clouds' noise, uploaded once by the mod)
    kCirrus = 2,       // 2D R8, 2048^2, clamp, linear
    kSkyCache = 3,     // 2D RGBA16F, octahedral sky of clouds (the layer's pass)
    kShadow = 4,       // 2D RGBA16F, cloud optical depth toward the light (the layer's pass)
    kSkyCacheStorage = 5,
    kShadowStorage = 6,
    kBindingCount = 7,
};

constexpr uint32_t kNoiseSize = 128, kCirrusSize = 2048, kSkyCacheSize = 1024, kShadowSize = 2048;

struct Channel;

// Builds the channel on a freshly created device; never fails the device. On any failure the channel is Down
// (no layout is extended, nothing is bound) and report says why.
Channel* createChannel(VkPhysicalDevice gpu, VkDevice device, const VkDeviceCreateInfo* info,
                       PFN_vkGetDeviceProcAddr gdpa, PFN_vkGetInstanceProcAddr gipa, VkInstance instance,
                       PFN_vkSetDeviceLoaderData setLoaderData, const std::vector<uint32_t>& program,
                       std::string& report);
void destroyChannel(Channel* channel);
bool channelUp(const Channel* channel);

// The hooks, for the device's channel. Each forwards to the next layer.
VkResult createPipelineLayout(Channel* c, VkDevice device, const VkPipelineLayoutCreateInfo* info,
                              const VkAllocationCallbacks* alloc, VkPipelineLayout* out);
void cmdBindDescriptorSets(Channel* c, VkCommandBuffer cmd, VkPipelineBindPoint point, VkPipelineLayout layout,
                           uint32_t firstSet, uint32_t count, const VkDescriptorSet* sets, uint32_t dynamicCount,
                           const uint32_t* dynamicOffsets);
VkResult queueSubmit(Channel* c, VkQueue queue, uint32_t count, const VkSubmitInfo* submits, VkFence fence);
VkResult queuePresent(Channel* c, VkQueue queue, const VkPresentInfoKHR* info);
void getDeviceQueue(Channel* c, VkDevice device, uint32_t family, uint32_t index, VkQueue* out);
void getDeviceQueue2(Channel* c, VkDevice device, const VkDeviceQueueInfo2* info, VkQueue* out);

// Counters for rrp_channel_status: [up, layoutsExtended, layoutsRefused, setBinds, framesLatched,
// submitsSkipped, uploads, lost].
void channelStatus(const Channel* c, uint32_t* out);

// The mod's side (render thread). 0 accepted; -1 wrong size or binding; -2 no channel yet.
int32_t publishFrame(const void* bytes, uint32_t size);
int32_t upload(uint32_t binding, const void* bytes, uint64_t size);

}  // namespace rrp
