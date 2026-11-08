/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.HardcodedVertex;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cloud material comparison (Debug page, runtime-patch branch): four copies of one hand-made
 * cumulus shell in a row over the test site, each with a candidate material for the Unbound clouds
 * as world geometry. A is a thick Translucent with a diffuse layer of 60% opacity, B the same without
 * it, C A's shell over an Opaque core with subsurface scattering, D the Opaque subsurface material the
 * Procedural style uses, E A with a 90% diffuse layer. The translucent ones' measurement distance puts them in the shadow-absorption patch's cloud
 * class. Render thread only.
 */
public final class CloudMaterialSpike {

    /** Where the row starts and how far apart the clouds stand, in world blocks. */
    private static final double[] X = {20.0, 55.0, 90.0, 125.0, 160.0};
    private static final double Y = -32.0;
    private static final double Z = 110.0;
    private static final float RADIUS = 12.0f;
    /** The shell's light extinction: transmittance over the measurement distance, which marks a cloud. */
    private static final float TRANSMITTANCE = 0.3f;
    private static final float MEASUREMENT = 32.0f;
    /** The diffuse layers' opacities, the alpha of their one-texel textures. */
    private static final int DIFFUSE_OPACITY = 153;
    private static final int DENSE_OPACITY = 230;
    private static final int CATEGORIES = InstanceCategoryBit.IGNORE_BAKED_LIGHTING;

    private static boolean enabled;
    private static final List<RemixTexture> textures = new ArrayList<>();
    private static final List<RemixMaterial> materials = new ArrayList<>();
    private static final List<RemixMesh[]> meshes = new ArrayList<>();
    private static final float[] matrix = new float[12];

    private CloudMaterialSpike() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Draws the row this frame when the switch is on; frees it when the switch is off. */
    public static void draw(RemixAPI api) {
        if (!enabled) {
            release();
            return;
        }
        if (meshes.isEmpty()) {
            build();
        }
        for (int i = 0; i < meshes.size(); i++) {
            for (RemixMesh mesh : meshes.get(i)) {
                matrix[0] = 1.0f;
                matrix[3] = SceneSpace.x(X[i]);
                matrix[5] = 1.0f;
                matrix[7] = SceneSpace.y(Y);
                matrix[10] = 1.0f;
                matrix[11] = SceneSpace.z(Z);
                api.drawInstance(mesh.getHandle(), matrix, CATEGORIES, 0, 0L);
            }
        }
    }

    public static void release() {
        for (RemixMesh[] row : meshes) {
            for (RemixMesh mesh : row) {
                if (!mesh.isDeleted()) {
                    mesh.destroy();
                }
            }
        }
        meshes.clear();
        for (RemixMaterial material : materials) {
            if (!material.isDeleted()) {
                material.destroy();
            }
        }
        materials.clear();
        for (RemixTexture texture : textures) {
            if (!texture.isDeleted()) {
                texture.destroy();
            }
        }
        textures.clear();
    }

    private static void build() {
        RemixMaterial layered = material(translucent(texel(DIFFUSE_OPACITY)));
        RemixMaterial clear = material(translucent(null));
        RemixMaterial core = material(subsurface());
        RemixMaterial dense = material(translucent(texel(DENSE_OPACITY)));
        Shell shell = Shell.cumulus(RADIUS);
        Shell inner = Shell.cumulus(RADIUS * 0.7f);
        meshes.add(new RemixMesh[] {mesh(shell, layered)});
        meshes.add(new RemixMesh[] {mesh(shell, clear)});
        meshes.add(new RemixMesh[] {mesh(shell, layered), mesh(inner, core)});
        meshes.add(new RemixMesh[] {mesh(shell, core)});
        meshes.add(new RemixMesh[] {mesh(shell, dense)});
    }

    /** A one-texel white texture whose alpha is the diffuse layer's opacity. */
    private static RemixTexture texel(int opacity) {
        ByteBuffer data = DirectBufferPool.acquire(4);
        data.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) opacity).flip();
        RemixTexture texture = new RemixTexture(1, 1, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, data);
        RemixTextureStore.get().create(texture);
        textures.add(texture);
        return texture;
    }

    private static RemixMaterial material(MaterialInfo info) {
        RemixMaterial material = new RemixMaterial(info);
        RemixMaterialStore.get().create(material);
        materials.add(material);
        return material;
    }

    /**
     * The runtime takes a translucent material's diffuse layer from its transmittance texture slot
     * (one index serves both), not from the albedo texture.
     */
    private static MaterialInfo translucent(RemixTexture diffuseLayer) {
        MaterialInfoTranslucentEXT.Builder ext = MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(1.0f)
                .transmittanceColor(TRANSMITTANCE, TRANSMITTANCE, TRANSMITTANCE)
                .transmittanceMeasurementDistance(MEASUREMENT)
                .useDiffuseLayer(diffuseLayer != null);
        if (diffuseLayer != null) {
            ext.transmittanceTexture(diffuseLayer.getHandle());
        }
        return MaterialInfo.builder().translucentExt(ext.build()).build();
    }

    private static MaterialInfo subsurface() {
        return MaterialInfo.builder().opaqueExt(MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(0.9f, 0.9f, 0.9f)
                        .roughnessConstant(1.0f)
                        .metallicConstant(0.0f)
                        .subsurfaceExt(MaterialInfoOpaqueSubsurfaceEXT.builder()
                                .diffusionProfile(true)
                                .transmittanceColor(0.999f, 0.999f, 0.999f)
                                .volumetricAnisotropy(0.0f)
                                .radius(0.98f, 0.98f, 0.98f)
                                .radiusScale(5000.0f)
                                .maxSampleRadius(1.0f)
                                .build())
                        .build())
                .build();
    }

    private static RemixMesh mesh(Shell shell, RemixMaterial material) {
        int count = shell.positions.length / 3;
        ByteBuffer vertices = DirectBufferPool.acquire(count * HardcodedVertex.STRIDE);
        for (int i = 0; i < count; i++) {
            RemixMeshSurface.putVertex(vertices,
                    shell.positions[i * 3], shell.positions[i * 3 + 1], shell.positions[i * 3 + 2],
                    shell.normals[i * 3], shell.normals[i * 3 + 1], shell.normals[i * 3 + 2],
                    0.0f, 0.0f, 0xffff_ffff, 0, 0);
        }
        vertices.flip();
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertexData(vertices, count)
                .setIndexDataFrom(shell.indices, shell.indices.length);
        RemixMesh mesh = new RemixMesh(new RemixMeshSurface[] {surface});
        RemixMeshStore.get().create(mesh);
        return mesh;
    }

    /**
     * A closed mesh wound clockwise seen from outside, the runtime's (Direct3D's) front face: wound
     * the other way every material drew black.
     */
    record Shell(float[] positions, float[] normals, int[] indices) {

        /** An icosphere of radius r, puffed by a few bumps, squashed and flat underneath. */
        static Shell cumulus(float r) {
            List<float[]> points = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();
            double t = (1.0 + Math.sqrt(5.0)) / 2.0;
            double[][] seed = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t},
                    {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
            for (double[] p : seed) {
                double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                points.add(new float[] {(float) (p[0] / l), (float) (p[1] / l), (float) (p[2] / l)});
            }
            int[][] base = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4},
                    {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                    {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
            for (int[] f : base) {
                faces.add(f);
            }
            for (int level = 0; level < 4; level++) {
                Map<Long, Integer> mid = new HashMap<>();
                List<int[]> next = new ArrayList<>();
                for (int[] f : faces) {
                    int a = midpoint(points, mid, f[0], f[1]);
                    int b = midpoint(points, mid, f[1], f[2]);
                    int c = midpoint(points, mid, f[2], f[0]);
                    next.add(new int[] {f[0], a, c});
                    next.add(new int[] {f[1], b, a});
                    next.add(new int[] {f[2], c, b});
                    next.add(new int[] {a, b, c});
                }
                faces = next;
            }
            float[] positions = new float[points.size() * 3];
            for (int i = 0; i < points.size(); i++) {
                float[] d = points.get(i);
                double bumps = 0.18 * Math.sin(3.1 * d[0] + 1.3) * Math.sin(2.7 * d[2] + 0.4)
                        + 0.12 * Math.sin(5.3 * d[1] + 4.0 * d[0]) + 0.08 * Math.sin(7.9 * d[2] - 3.7 * d[1]);
                double scale = r * (1.0 + bumps);
                positions[i * 3] = (float) (d[0] * scale * 1.3);
                positions[i * 3 + 1] = (float) Math.max(d[1] * scale * 0.8, -0.3 * r);
                positions[i * 3 + 2] = (float) (d[2] * scale * 1.3);
            }
            int[] indices = new int[faces.size() * 3];
            for (int i = 0; i < faces.size(); i++) {
                // The icosahedron's faces run counter-clockwise seen from outside: reversed.
                indices[i * 3] = faces.get(i)[0];
                indices[i * 3 + 1] = faces.get(i)[2];
                indices[i * 3 + 2] = faces.get(i)[1];
            }
            return new Shell(positions, normals(positions, indices), indices);
        }

        private static int midpoint(List<float[]> points, Map<Long, Integer> mid, int i, int j) {
            long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
            Integer found = mid.get(key);
            if (found != null) {
                return found;
            }
            float[] a = points.get(i);
            float[] b = points.get(j);
            float x = a[0] + b[0];
            float y = a[1] + b[1];
            float z = a[2] + b[2];
            float l = (float) Math.sqrt(x * x + y * y + z * z);
            points.add(new float[] {x / l, y / l, z / l});
            mid.put(key, points.size() - 1);
            return points.size() - 1;
        }

        /** Area-weighted vertex normals of the clockwise faces, outward. */
        static float[] normals(float[] p, int[] idx) {
            float[] n = new float[p.length];
            for (int f = 0; f < idx.length; f += 3) {
                int a = idx[f] * 3;
                int b = idx[f + 1] * 3;
                int c = idx[f + 2] * 3;
                float ux = p[b] - p[a];
                float uy = p[b + 1] - p[a + 1];
                float uz = p[b + 2] - p[a + 2];
                float vx = p[c] - p[a];
                float vy = p[c + 1] - p[a + 1];
                float vz = p[c + 2] - p[a + 2];
                float nx = uz * vy - uy * vz;
                float ny = ux * vz - uz * vx;
                float nz = uy * vx - ux * vy;
                for (int k : new int[] {a, b, c}) {
                    n[k] += nx;
                    n[k + 1] += ny;
                    n[k + 2] += nz;
                }
            }
            for (int i = 0; i < n.length; i += 3) {
                float l = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
                if (l > 0.0f) {
                    n[i] /= l;
                    n[i + 1] /= l;
                    n[i + 2] /= l;
                }
            }
            return n;
        }
    }
}
