/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleMaterialTextures;
import com.nvidia.rtxremix.minecraft.util.CleanupSteps;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;

/** Render-thread batching for extracted quad sources, independent of live billboard slots. */
public final class ParticleGeometryRenderer implements AutoCloseable {
    private final ParticleAtlasManager atlases;
    private final HashMap<SingleQuadParticle.Layer, Layer> layers = new HashMap<>();
    private final float[] transform = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private final InstanceInfoBlendEXT cutout = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(25);
    private final InstanceInfoBlendEXT blended = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(25);
    private long cutoutPointer, blendedPointer;
    private boolean ready;
    private ParticleGeometryVertexConsumer vertexConsumer;
    private int capturedQuads, lastQuads, lastDraws;
    private int sourceCheckpoint;
    private boolean capturingSource;

    public ParticleGeometryRenderer(ParticleAtlasManager atlases) { this.atlases = atlases; }

    public void beginFrame(Vec3 camera) {
        ready = false;
        capturedQuads = 0;
        transform[3] = (float) camera.x;
        transform[7] = (float) camera.y;
        transform[11] = (float) camera.z;
        for (Layer layer : layers.values()) layer.reset();
    }

    public ParticleGeometrySink layer(SingleQuadParticle.Layer source) {
        Layer found = layers.get(source);
        if (found == null) {
            found = new Layer(source);
            layers.put(source, found);
        }
        return found;
    }

    public void finishFrame() { ready = true; }
    /** A failed producer may not remove a healthy producer's prefix or leave an open quad. */
    public void beginSource() {
        sourceCheckpoint = capturedQuads;
        capturingSource = true;
        for (Layer layer : layers.values()) {
            for (Bucket bucket : layer.buckets) if (bucket != null) {
                bucket.checkpoint = bucket.current;
                for (Page page : bucket.pages) page.buffer.checkpoint();
            }
        }
    }
    public void finishSource() {
        for (Layer layer : layers.values()) {
            if (layer.active != null) throw new IllegalStateException("Unfinished extracted particle quad");
        }
        capturingSource = false;
    }
    public void abortSource() {
        if (!capturingSource) return;
        if (vertexConsumer != null) vertexConsumer.abort();
        for (Layer layer : layers.values()) {
            layer.active = null;
            for (Bucket bucket : layer.buckets) if (bucket != null) {
                bucket.current = bucket.checkpoint;
                for (Page page : bucket.pages) page.buffer.rollback();
            }
        }
        capturedQuads = sourceCheckpoint;
        capturingSource = false;
    }
    public ParticleGeometryVertexConsumer vertexConsumer(SingleQuadParticle.Layer source) {
        if (vertexConsumer == null) vertexConsumer = new ParticleGeometryVertexConsumer();
        vertexConsumer.begin(layer(source), atlases.geometryBounds(source.textureAtlasLocation()));
        return vertexConsumer;
    }
    public void discardFrame() {
        ready = capturingSource = false;
        capturedQuads = lastQuads = lastDraws = 0;
        if (vertexConsumer != null) vertexConsumer.abort();
        for (Layer layer : layers.values()) {
            layer.active = null;
            for (Bucket bucket : layer.buckets) if (bucket != null) {
                bucket.current = bucket.checkpoint = 0;
                for (Page page : bucket.pages) page.buffer.discard();
            }
        }
    }
    public int quadCount() { return lastQuads; }
    public int drawCount() { return lastDraws; }

    public void submit(RemixAPI api) {
        lastQuads = lastDraws = 0;
        if (!ready) return;
        ready = false;
        int expected = 0;
        for (Layer layer : layers.values()) for (Bucket bucket : layer.buckets) {
            if (bucket != null) for (Page page : bucket.pages) expected += page.buffer.quadCount();
        }
        if (expected != capturedQuads) throw new IllegalStateException("Extracted particle quad counts disagree before submission");
        if (cutoutPointer == 0) cutoutPointer = cutout.allocNative(0);
        if (blendedPointer == 0) blendedPointer = blended.allocNative(0);
        for (Layer layer : layers.values()) {
            long blend = layer.source.translucent() ? blendedPointer : cutoutPointer;
            // The runtime's particle radiance path is for blended sprites. Cutout
            // cards need ordinary surface lighting, including their geometric normal.
            int category = layer.source.translucent()
                    && !TextureAtlas.LOCATION_BLOCKS.equals(layer.source.textureAtlasLocation())
                    && !TextureAtlas.LOCATION_ITEMS.equals(layer.source.textureAtlasLocation())
                    ? InstanceCategoryBit.PARTICLE : 0;
            for (Bucket bucket : layer.buckets) {
                if (bucket == null) continue;
                for (Page page : bucket.pages) {
                    int count = page.buffer.quadCount();
                    if (count == 0) {
                        page.retire();
                        if (++page.idleFrames == 120) page.buffer.close();
                        continue;
                    }
                    page.idleFrames = 0;
                    page.replace(bucket.material);
                    api.drawInstance(page.mesh.getHandle(), transform, category,
                            layer.source.pipeline().isCull() ? 0 : 1, blend);
                    lastQuads += count;
                    lastDraws++;
                }
            }
        }
        if (lastQuads != capturedQuads) throw new IllegalStateException("Extracted particle geometry was not fully submitted");
    }

    @Override public void close() {
        CleanupSteps cleanup = new CleanupSteps();
        for (Layer layer : layers.values()) cleanup.run(layer::close);
        if (cutoutPointer != 0) { cutout.freeNative(); cutoutPointer = 0; }
        if (blendedPointer != 0) { blended.freeNative(); blendedPointer = 0; }
        discardFrame();
        cleanup.finish();
        layers.clear();
    }

    private final class Layer implements ParticleGeometrySink, AutoCloseable {
        final SingleQuadParticle.Layer source;
        final Bucket[] buckets = new Bucket[ParticleMaterialTextures.AUTHORED_EMISSION + 1];
        ParticleMaterialTextures textures;
        Page active;
        Layer(SingleQuadParticle.Layer source) { this.source = source; }
        void reset() {
            active = null;
            for (Bucket bucket : buckets) if (bucket != null) bucket.reset();
        }
        @Override public void beginQuad(int emission, int min, int max) {
            if (active != null) throw new IllegalStateException("Unfinished extracted particle quad");
            if (emission < 0 || emission > 15) throw new IllegalArgumentException("Particle emission must be in 0..15");
            if (textures == null) textures = atlases.geometryTextures(source.textureAtlasLocation());
            emission = textures.materialBucket(emission, min, max);
            Bucket bucket = buckets[emission];
            if (bucket == null) {
                bucket = new Bucket(textures, emission);
                buckets[emission] = bucket;
            }
            active = bucket.page();
            active.buffer.beginQuad(min, max);
        }
        @Override public void vertex(float x, float y, float z, float u, float v, int argb) {
            if (active == null) throw new IllegalStateException("Particle vertex has no quad");
            active.buffer.vertex(x, y, z, u, v, argb);
        }
        @Override public void endQuad() {
            if (active == null) throw new IllegalStateException("Particle quad has not begun");
            active.buffer.endQuad();
            active = null;
            capturedQuads++;
        }
        @Override public void close() {
            CleanupSteps cleanup = new CleanupSteps();
            for (Bucket bucket : buckets) if (bucket != null) cleanup.run(bucket::close);
            cleanup.finish();
        }
    }

    private static final class Bucket implements AutoCloseable {
        final RemixMaterial material;
        final ArrayList<Page> pages = new ArrayList<>();
        int current, checkpoint;
        Bucket(ParticleMaterialTextures textures, int emission) {
            material = new RemixMaterial(textures.material(emission));
            try { RemixMaterialStore.get().create(material); }
            catch (RemixException e) { throw new IllegalStateException("Could not create extracted particle material", e); }
        }
        void reset() { current = checkpoint = 0; for (Page page : pages) page.buffer.reset(); }
        Page page() {
            if (current < pages.size() && pages.get(current).buffer.full()) current++;
            if (current == pages.size()) pages.add(new Page());
            return pages.get(current);
        }
        @Override public void close() {
            CleanupSteps cleanup = new CleanupSteps();
            for (Page page : pages) cleanup.run(page::close);
            cleanup.finish();
            if (!material.isDeleted()) material.destroy();
        }
    }

    private static final class Page implements AutoCloseable {
        final ParticleGeometryBuffer buffer = new ParticleGeometryBuffer();
        RemixMesh mesh, retired;
        int idleFrames;
        void replace(RemixMaterial material) {
            if (retired != null) { retired.destroy(); retired = null; }
            ByteBuffer vertices = buffer.snapshotVertices(), indices = null;
            RemixMesh next;
            try {
                indices = buffer.snapshotIndices();
                RemixMeshSurface surface = new RemixMeshSurface(material)
                        .setVertexData(vertices, buffer.quadCount() * 4)
                        .setIndexData(indices, buffer.quadCount() * 6)
                        .setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
                next = new RemixMesh(new RemixMeshSurface[]{surface});
            } catch (RuntimeException | Error e) {
                DirectBufferPool.release(vertices);
                DirectBufferPool.release(indices);
                throw e;
            }
            try { RemixMeshStore.get().create(next); }
            catch (RemixException e) { next.disposeData(); throw new IllegalStateException("Could not upload extracted particle mesh", e); }
            catch (RuntimeException | Error e) { next.disposeData(); throw e; }
            retired = mesh;
            mesh = next;
            if (retired != null) { retired.destroy(); retired = null; }
        }
        void retire() {
            if (retired == null && mesh == null) return;
            CleanupSteps cleanup = new CleanupSteps();
            if (retired != null) cleanup.run(() -> { retired.destroy(); retired = null; });
            if (mesh != null) cleanup.run(() -> { mesh.destroy(); mesh = null; });
            cleanup.finish();
        }
        @Override public void close() {
            try { retire(); } finally { buffer.close(); }
        }
    }
}
