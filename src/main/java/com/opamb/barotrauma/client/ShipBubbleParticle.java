package com.opamb.barotrauma.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 破口气泡：运动逻辑照抄原版 {@code BubbleParticle}（缓慢上浮 + 阻尼 + 固定寿命），
 * 只把尺寸从原版的 0.004~0.016 格放大到 0.12~0.22 格。原版粒子在屏幕上基本是亚像素，
 * 进水的破口处几乎看不见，这里用同一个上浮行为做成能看清的气泡柱。
 */
public final class ShipBubbleParticle extends TextureSheetParticle {

    /** 由渲染器/水花粒子在 addParticle 之前设置；气泡到这个高度就破，不飞进空气。 */
    private static float pendingSurfaceY = Float.NaN;
    private float surfaceY = Float.NaN;
    private static boolean pendingSink;
    private boolean sink;
    /**
     * 船舶每 tick 的位移（格/tick）。气泡在船舱水里，应该跟着船一起动；
     * 否则船下沉时气泡留在原地，看起来就像“往上跑”。
     */
    private static float carryX;
    private static float carryY;
    private static float carryZ;
    private static boolean carryActive;
    /** 当前存活的气泡数：破口很多时（整片船壳被炸开）必须限流，否则粒子会把内存吃光。 */
    private static int liveCount;

    public static int liveCount() {
        return liveCount;
    }

    public static void setPendingSurface(float surfaceY) {
        pendingSurfaceY = surfaceY;
    }

    /** 顶部破口的气泡：先向下压进舱内，然后淡出，不再回升。 */
    public static void setPendingSink(boolean sink) {
        pendingSink = sink;
    }

    /** 渲染器每帧设置：船速度（格/秒）换算成每 tick 位移。无物理船时传 0。 */
    public static void setShipCarry(double vxPerSecond, double vyPerSecond, double vzPerSecond) {
        carryX = (float) (vxPerSecond / 20.0D);
        carryY = (float) (vyPerSecond / 20.0D);
        carryZ = (float) (vzPerSecond / 20.0D);
        carryActive = Math.abs(carryX) + Math.abs(carryY) + Math.abs(carryZ) > 1.0E-6F;
    }

    private void applySink(boolean sink) {
        this.sink = sink;
        if (sink) {
            this.lifetime = 35 + this.random.nextInt(20);
        }
    }

    private ShipBubbleParticle(ClientLevel level, double x, double y, double z,
                               double vx, double vy, double vz) {
        super(level, x, y, z);
        this.setSize(0.10F, 0.10F);
        this.quadSize = 0.12F + this.random.nextFloat() * 0.10F;
        // 速度直接采用调用方给的“进水方向 + 初速”，只加一点随机抖动。
        this.xd = vx + (this.random.nextDouble() * 2.0D - 1.0D) * 0.015D;
        this.yd = vy + (this.random.nextDouble() * 2.0D - 1.0D) * 0.015D;
        this.zd = vz + (this.random.nextDouble() * 2.0D - 1.0D) * 0.015D;
        // 原版气泡只有 0.4~2 秒；这里拉长到 9~13 秒，让顶部破口的气泡压到舱内后还能慢慢上浮。
        this.lifetime = 180 + this.random.nextInt(90);
        // 空气海域里没有真实水；不跟方块碰撞，气泡才会从洞口连续上浮。
        this.hasPhysics = false;
        liveCount++;
    }

    @Override
    public void remove() {
        if (!this.removed) {
            liveCount--;
        }
        super.remove();
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        // 船在水里下沉/移动时，舱内的水带着气泡一起走；水面也跟着船走。
        if (carryActive) {
            this.x += carryX;
            this.y += carryY;
            this.z += carryZ;
            if (!Float.isNaN(this.surfaceY)) {
                this.surfaceY += carryY;
            }
        }
        if (this.sink) {
            // 不再给浮力：向下压到舱内后自然淡出，不回升。
            this.yd *= 0.985D;
        } else {
            this.yd += 0.004D;
        }
        this.move(this.xd, this.yd, this.zd);
        this.xd *= 0.92D;
        this.yd *= 0.92D;
        this.zd *= 0.92D;
        // 到达水面就破，不允许升到空气里形成“气泡柱”。
        if (!Float.isNaN(this.surfaceY) && this.y >= this.surfaceY - 0.02D) {
            this.remove();
            return;
        }
        if (this.lifetime-- <= 0) {
            this.remove();
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            ShipBubbleParticle particle = new ShipBubbleParticle(level, x, y, z, vx, vy, vz);
            particle.surfaceY = pendingSurfaceY;
            particle.applySink(pendingSink);
            particle.pickSprite(this.sprites);
            return particle;
        }
    }
}
