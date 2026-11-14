// SPDX-License-Identifier: Apache-2.0
//
// The presentation survey (the Debug page's Presentation Survey, read at launch): how the runtime presents.
// It records the swapchain the runtime makes and the formats and colour spaces the window offers, the images it
// shares with the mod (made for exportable memory) and the swapchain's images, and each kind of command or
// descriptor that touches them, with how often. Off, it installs no hook and changes nothing. On, it changes one
// thing: it enables VK_EXT_swapchain_colorspace when the runtime leaves it out, so HDR colour spaces are listed.
#pragma once
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>

#include <string>

namespace rrp::survey {

// Turns the survey on for the process; call before the runtime creates its instance. colorSpaceOffered: whether the
// driver lists VK_EXT_swapchain_colorspace, which patch_layer.cpp enables for the survey and for HDR output.
void enable(bool colorSpaceOffered);
bool enabled();

// After vkCreateInstance (info as the runtime asked; colorSpace says what came of the colour-space extension): logs
// the instance and sets up the instance's own hooks.
void instanceCreated(const VkInstanceCreateInfo* info, const char* colorSpace, VkResult result, VkInstance instance,
                     PFN_vkGetInstanceProcAddr gipa);
void deviceCreated(VkPhysicalDevice gpu, VkDevice device, const VkDeviceCreateInfo* info, PFN_vkGetDeviceProcAddr gdpa,
                   PFN_vkGetInstanceProcAddr gipa, VkInstance instance);
void deviceDestroyed(VkDevice device);

// The survey's hook for name, or nullptr when the survey is off, does not watch name, or the next layer has no
// such function for this device or instance.
PFN_vkVoidFunction deviceHook(VkDevice device, const char* name);
PFN_vkVoidFunction instanceHook(VkInstance instance, const char* name);

// The lines recorded since the last call, then, with counts, how often each kind has happened so far.
std::string take(bool counts);

}  // namespace rrp::survey
