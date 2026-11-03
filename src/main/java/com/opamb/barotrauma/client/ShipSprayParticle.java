package com.opamb.barotrauma.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 破口喷淋水珠：空气舱里水柱打进空气时，从洞口呈扇形喷出的细小水滴。
 *
 * <p>比原版 {@code SPLASH} 更小、更密，带重力；只负责空气舱里的喷淋视觉，
 * 不会在水面转成气泡。贴图复用 {@code ship_bubble} 的白蓝色水珠纹理。</p>
 */
public final class ShipSprayParticle extends TextureSheetParticle {

    private ShipSprayParticle(ClientLevel level, double x, double y, double z,
                              double vx, double vy, double vz) {
        super(level, x, y, z);
        this.setSize(0.03F, 0.03F);
        this.quadSize = 0.04F + this.random.nextFloat() * 0.04F;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.lifetime = 8 + this.random.nextInt(8);
        // 喷淋水珠不跟方块碰撞，否则会贴在船壳上停住。
        this.hasPhysics = false;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.yd -= 0.05D;
        this.move(this.xd, this.yd, this.zd);
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
            ShipSprayParticle particle = new ShipSprayParticle(level, x, y, z, vx, vy, vz);
            particle.pickSprite(this.sprites);
            return particle;
        }
    }
}
