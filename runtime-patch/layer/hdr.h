// SPDX-License-Identifier: Apache-2.0
//
// HDR output: the swapchain as scRGB (R16G16B16A16_SFLOAT, EXTENDED_SRGB_LINEAR, 1.0 = 80 nits), with fp16 on the
// image's whole way to it. The mod asks before rrp_arm (rrp_hdr); the layer accepts only when Windows HDR is on for
// the window's monitor. Not accepted, nothing here runs, no hook is handed out and the #hdr groups are vetoed, so the
// runtime runs exactly as without it.
//
// Accepted, the layer answers the runtime's surface-format queries with the scRGB pair alone, so the runtime builds
// its swapchain, views, render pass and presenter for that format itself. It widens the three 8-bit images on the
// way (the interop colour image the mod imports, made for external memory, and the two D3D9 back buffers) to fp16 at
// vkCreateImage and gives their views the same format. The patched final combine and sRGB pass (#hdr group
// hdr-output) then write scRGB through them. The layer never decides brightness: the shaders read it from the frame
// block, the mod's GL side from HdrOutput.
#pragma once
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <windows.h>

#include <cstdint>
#include <string>

namespace rrp::hdr {

enum State : uint32_t {
    kOff = 0,       // not asked
    kWaiting = 1,   // accepted, no swapchain yet
    kActive = 2,    // the latest swapchain is scRGB
    kRefused = 3,   // the latest swapchain is not scRGB (the runtime took another format, or the window offers none)
    kDeclined = 4,  // asked, but Windows HDR is off for the window's monitor
};

// rrp_hdr: asks for HDR output for the window's monitor, before rrp_arm. Accepts when Windows HDR is on there; the
// decision is final for the process. Appends one sentence to report.
bool request(HWND window, std::string& report);
bool accepted();
// Takes an acceptance back before any swapchain exists: the patch group that writes scRGB cannot run (it does not
// match the runtime, or the device has no frame channel). The surface-format answer then goes through unchanged.
void withdraw(const std::string& why);

// After vkCreateInstance and vkCreateDevice (and after the survey's own), while accepted.
void instanceCreated(VkInstance instance, PFN_vkGetInstanceProcAddr gipa);
void deviceCreated(VkDevice device, PFN_vkGetDeviceProcAddr gdpa);
void deviceDestroyed(VkDevice device);

// The hook for name while accepted, or nullptr. Asked before the survey's, whose hooks hdr's own calls go through,
// so the survey logs the create infos as rewritten.
PFN_vkVoidFunction deviceHook(VkDevice device, const char* name);
PFN_vkVoidFunction instanceHook(VkInstance instance, const char* name);

// rrp_hdr_status: [state, swapchains, images widened, interop image widened (0/1), Windows HDR (0/1), SDR white
// nits] into out, and the latest decision as a sentence into reason.
void status(uint32_t* out, std::string& reason);

}  // namespace rrp::hdr
