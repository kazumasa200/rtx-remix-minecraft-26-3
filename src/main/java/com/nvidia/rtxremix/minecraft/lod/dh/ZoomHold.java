/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

/**
 * When Distant Horizons' zoom reaches the LOD terrain Remix draws.
 *
 * <p>Zoomed in by half or more, Distant Horizons raises the detail of every section in a
 * cone about the view, and lowers it again the moment the zoom ends. Drawing for itself it
 * pays for that in the background. Here each refined section is a mesh and an acceleration
 * structure handed to Remix: aiming a pistol's sight across a city installed 1.8 GiB in
 * twelve seconds, a fifth off the frame rate while it lasted, and every aim began it again
 * (#29).
 *
 * <p>So a zoom reaches the terrain once it has been held for {@link #HOLD_NANOS}, long
 * enough that someone is looking at what lies far off, and the terrain keeps its detail
 * for {@link #RELEASE_NANOS} after the zoom ends, so aiming again finds it there. The
 * look is Distant Horizons' own; only a glance through a sight no longer rebuilds the
 * view.
 */
public final class ZoomHold {

    static final long HOLD_NANOS = 2_500_000_000L;
    static final long RELEASE_NANOS = 10_000_000_000L;

    private boolean zooming;
    private long zoomStart;
    /** Whether a zoom has reached the terrain and has not been released since. */
    private boolean refined;
    private long lastZoom;

    private double magnification = 1.0;
    private double coneTanHalfAngle;
    private double lookDirectionX;
    private double lookDirectionZ;

    /**
     * Takes the zoom Distant Horizons worked out this frame, a magnification of 1 meaning
     * none, and settles the one the terrain is to follow.
     */
    public void update(long now, double magnification, double coneTanHalfAngle,
                       double lookDirectionX, double lookDirectionZ) {
        if (magnification > 1.0) {
            if (!zooming) {
                zooming = true;
                zoomStart = now;
            }
            if (refined || now - zoomStart >= HOLD_NANOS) {
                refined = true;
                lastZoom = now;
                this.magnification = magnification;
                this.coneTanHalfAngle = coneTanHalfAngle;
                this.lookDirectionX = lookDirectionX;
                this.lookDirectionZ = lookDirectionZ;
                return;
            }
        } else {
            zooming = false;
            // The last zoom's cone stands until it is released.
            if (refined && now - lastZoom < RELEASE_NANOS) return;
            refined = false;
        }
        this.magnification = 1.0;
        this.coneTanHalfAngle = 0.0;
        this.lookDirectionX = 0.0;
        this.lookDirectionZ = 0.0;
    }

    public double magnification() {
        return magnification;
    }

    public double coneTanHalfAngle() {
        return coneTanHalfAngle;
    }

    public double lookDirectionX() {
        return lookDirectionX;
    }

    public double lookDirectionZ() {
        return lookDirectionZ;
    }
}
