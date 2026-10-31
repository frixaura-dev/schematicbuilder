package dev.schembuilder.render;

import dev.schembuilder.SchemBuilderClient;
import dev.schembuilder.build.Builder;
import dev.schembuilder.build.Target;
import dev.schembuilder.config.SBConfig;
import dev.schembuilder.schematic.Placement;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Outlines in the world: schematic bounds, wrong blocks (red), missing blocks (blue), current target (pulsing). */
public final class OverlayRenderer {
	private OverlayRenderer() {}

	public static void render(WorldRenderContext ctx) {
		if (!SBConfig.get().overlay) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		Builder builder = SchemBuilderClient.BUILDER;
		Placement placement = builder.active() ? builder.placement() : SchemBuilderClient.placement;
		if (placement == null) return;
		MatrixStack ms = ctx.matrixStack();
		if (ms == null) return;

		Vec3d cam = ctx.camera().getPos();
		VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = immediate.getBuffer(RenderLayer.getLines());
		MatrixStack.Entry entry = ms.peek();
		float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 180.0));

		box(entry, vc, placement.box(), cam, 0.37f, 0.92f, 0.83f, 0.9f);

		if (builder.active()) {
			int drawn = 0;
			double maxSq = 30 * 30;
			for (Target t : builder.targets()) {
				if (t.done) continue;
				if (cam.squaredDistanceTo(Vec3d.ofCenter(t.pos)) > maxSq) continue;
				if (t.failed) {
					box(entry, vc, shrink(t.pos, 0.02), cam, 0.98f, 0.75f, 0.14f, 0.7f);
				} else if (t.wrong) {
					box(entry, vc, shrink(t.pos, 0.01), cam, 0.97f, 0.44f, 0.44f, 0.95f);
				} else {
					box(entry, vc, shrink(t.pos, 0.3), cam, 0.51f, 0.55f, 0.97f, 0.45f);
				}
				if (++drawn > 700) break;
			}
			Target cur = builder.current();
			if (cur != null) {
				box(entry, vc, shrink(cur.pos, -0.01), cam, 1f, 1f, 1f, pulse);
			}
		}
		immediate.draw(RenderLayer.getLines());
	}

	private static Box shrink(BlockPos p, double inset) {
		return new Box(p.getX() + inset, p.getY() + inset, p.getZ() + inset,
			p.getX() + 1 - inset, p.getY() + 1 - inset, p.getZ() + 1 - inset);
	}

	private static void box(MatrixStack.Entry e, VertexConsumer vc, Box b, Vec3d cam, float r, float g, float bl, float a) {
		float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
		float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
		line(e, vc, x1, y1, z1, x2, y1, z1, r, g, bl, a);
		line(e, vc, x1, y1, z2, x2, y1, z2, r, g, bl, a);
		line(e, vc, x1, y2, z1, x2, y2, z1, r, g, bl, a);
		line(e, vc, x1, y2, z2, x2, y2, z2, r, g, bl, a);
		line(e, vc, x1, y1, z1, x1, y2, z1, r, g, bl, a);
		line(e, vc, x2, y1, z1, x2, y2, z1, r, g, bl, a);
		line(e, vc, x1, y1, z2, x1, y2, z2, r, g, bl, a);
		line(e, vc, x2, y1, z2, x2, y2, z2, r, g, bl, a);
		line(e, vc, x1, y1, z1, x1, y1, z2, r, g, bl, a);
		line(e, vc, x2, y1, z1, x2, y1, z2, r, g, bl, a);
		line(e, vc, x1, y2, z1, x1, y2, z2, r, g, bl, a);
		line(e, vc, x2, y2, z1, x2, y2, z2, r, g, bl, a);
	}

	private static void line(MatrixStack.Entry e, VertexConsumer vc, float x1, float y1, float z1,
							 float x2, float y2, float z2, float r, float g, float b, float a) {
		float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		vc.vertex(e, x1, y1, z1).color(r, g, b, a).normal(e, nx, ny, nz);
		vc.vertex(e, x2, y2, z2).color(r, g, b, a).normal(e, nx, ny, nz);
	}
}
