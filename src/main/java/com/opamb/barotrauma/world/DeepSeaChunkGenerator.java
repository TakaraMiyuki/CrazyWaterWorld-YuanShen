package com.opamb.barotrauma.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.opamb.barotrauma.BarotraumaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 深海维度生成器：一整根 1000 米高的实心岩柱，隧道和洞窟是从岩里"挖"出来的水腔。
 *
 * <p>为什么是实心而不是"水里漂岩石"：实心岩挖出来的才是真正的隧道——四面都是岩壁、不漏光、
 * 走进去有压迫感，和《潜渊症》的关卡一致；水漂岩石只会变成开放峡谷。</p>
 *
 * <p>挖法（全部由种子哈希，无存档数据）：</p>
 * <ol>
 *   <li><b>隧道网</b>：两个三维噪声的等值面相交处（{@code |a|<w && |b|<w}）天然是一条三维管道网络，
 *       宽约 8 格，随便怎么走都能通；</li>
 *   <li><b>洞窟</b>：一层更低频的噪声，{@code |c| < w} 的地方挖成 15~20 格的大空腔，用来破开纯隧道的单调；</li>
 *   <li><b>主干道</b>：{@link TrunkNetwork} 挖出 17×25 的长走廊，8 层，带竖井；</li>
 *   <li><b>哨站水池</b>：哨站外面挖一个椭球空腔，让气闸门外面有水。</li>
 * </ol>
 */
public class DeepSeaChunkGenerator extends ChunkGenerator {

    public static final Codec<DeepSeaChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource))
                    .apply(instance, DeepSeaChunkGenerator::new));

    private static final int STEP = 4;
    private static final int GRID_XZ = 16 / STEP + 1;
    private static final int GRID_Y = ModDimensions.HEIGHT / STEP + 1;

    /**
     * 层高（格）：每隔这么多米有一层"岩层"，其余是开阔水域。
     *
     * <p>原版《潜渊症》的地形不是"实心岩里挖小洞"，而是<b>上下是岩壁、中间一大片开阔海域</b>：
     * 潜艇在上百米高的水域里横着开，偶尔要绕开垂下来的岩柱。之前用 3D 噪声挖洞，
     * 洞径最多十几格，潜艇 15 米长根本转不开——用户反馈"洞太小""中间要有大片海域"已经是第二次，
     * 所以这一版直接换成"分层海域"结构。</p>
     */
    private static final int LAYER_HEIGHT = 120;
    /** 第一层岩层的顶部（y）。往上到水面全是水。 */
    private static final int BAND_TOP_Y = -30;
    /** 岩层厚度占层高的基准比例与波动。 */
    private static final double ROCK_FRACTION = 0.21D;
    private static final double ROCK_WOBBLE = 0.09D;
    /** 岩柱最大伸出比例（相对层高）。 */
    private static final double PILLAR_REACH = 0.38D;

    private static final long SALT_LAYER = 0x51A7E001L;
    private static final long SALT_PILLAR = 0x9B2C4407L;

    private long cachedWorldSeed = Long.MIN_VALUE;

    public DeepSeaChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        long seed = worldSeed(randomState);
        return CompletableFuture.supplyAsync(() -> {
            generate(chunk, seed);
            return chunk;
        }, executor);
    }

    private void generate(ChunkAccess chunk, long seed) {
        ChunkPos chunkPos = chunk.getPos();
        int originX = chunkPos.getMinBlockX();
        int originZ = chunkPos.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();

        // 海域用空气而不是真实水：水下效果由客户端雾/滤镜表现，进水走数值模拟。
        BlockState ocean = Blocks.AIR.defaultBlockState();
        BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();

        // 1) 整根岩柱：上冰盖、中石头、深处深板岩
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minY + i * 16;
            BlockState fill;
            if (sectionY >= ModDimensions.CEILING_BOTTOM_Y) {
                fill = ice;
            } else if (sectionY <= -704) {
                fill = deepslate;
            } else {
                fill = stone;
            }
            fillSection(sections[i], fill);
        }

        // 2) 海床表面掺杂凝灰岩
        for (int y = minY; y <= ModDimensions.SEABED_TOP_Y; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    boolean patch = ((lx * 31 + lz * 17 + y * 13) & 7) == 0;
                    if (patch) {
                        setBlock(chunk, originX + lx, y, originZ + lz, tuff);
                    }
                }
            }
        }

        // 3) 分层海域：上下是起伏的岩层，中间一大片开阔空气（视觉上由水下滤镜表现）
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = originX + lx;
                int z = originZ + lz;
                // 这一列岩层有多厚（占层高的比例），随水平位置起伏，所以岩壁是波浪形的；
                // 哨站上方压平成最薄值，让哨站顶部与顶板底面严丝合缝（见 OutpostBuilder）
                boolean flatRoof = OutpostBuilder.inRoofFlattenZone(x, z);
                double rock = flatRoof
                        ? OutpostBuilder.FLAT_ROOF_FRACTION
                        : ROCK_FRACTION + ROCK_WOBBLE
                                * DeepSeaNoise.fbm3(seed ^ SALT_LAYER, x / 90.0D, 0.0D, z / 90.0D, 2);
                // 上下交错的岩柱：噪声高的地方从天花板垂下来，低的地方从地面升起来（压平区无岩柱）
                double pillar = DeepSeaNoise.fbm3(seed ^ SALT_PILLAR, x / 42.0D, 0.0D, z / 42.0D, 2);
                double hanging = flatRoof ? 0.0D : Math.max(0.0D, pillar - 0.30D) * PILLAR_REACH / 0.70D;
                double rising = Math.max(0.0D, -pillar - 0.30D) * PILLAR_REACH / 0.70D;
                double roof = rock + hanging;
                double floor = 1.0D - rock - rising;

                for (int y = ModDimensions.WATER_TOP_Y; y >= ModDimensions.WATER_BOTTOM_Y; y--) {
                    if (y > BAND_TOP_Y) {
                        setBlock(chunk, x, y, z, ocean);
                        continue;
                    }
                    double k = (BAND_TOP_Y - y) / (double) LAYER_HEIGHT;
                    double frac = k - Math.floor(k);
                    if (frac >= roof && frac <= floor) {
                        setBlock(chunk, x, y, z, ocean);
                    }
                }
            }
        }

        // 4) 主干道 / 竖井（层与层之间的通道）
        TrunkNetwork.carveInto(chunk, seed);

        // 6) 哨站：先挖出外面的水池，再放建筑
        if (OutpostBuilder.intersectsCavern(chunkPos)) {
            OutpostBuilder.carveCavern(chunk);
        }
        if (OutpostBuilder.intersects(chunkPos)) {
            OutpostBuilder.build(chunk);
        }

        // 6b) 任务 POI：沉船/侦察点/B 中继站（确定性，幂等）
        PoiNetwork.buildInto(chunk);

        // 7) 高度图
        Heightmap.primeHeightmaps(chunk,
                EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
    }

    private static void fillSection(LevelChunkSection section, BlockState state) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    section.setBlockState(x, y, z, state, false);
                }
            }
        }
    }

    private static void setBlock(ChunkAccess chunk, int x, int y, int z, BlockState state) {
        int sectionIndex = (y - chunk.getMinBuildHeight()) >> 4;
        chunk.getSection(sectionIndex).setBlockState(x & 15, y & 15, z & 15, state, false);
    }

    private long worldSeed(RandomState randomState) {
        if (cachedWorldSeed == Long.MIN_VALUE) {
            RandomSource random = randomState
                    .getOrCreateRandomFactory(new ResourceLocation(BarotraumaMod.MOD_ID, "deep_sea"))
                    .fromHashOf("seed");
            cachedWorldSeed = random.nextLong();
        }
        return cachedWorldSeed;
    }

    // ------------------------------------------------------------------
    // ChunkGenerator 必须实现的部分
    // ------------------------------------------------------------------

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState,
                             ChunkAccess chunk) {
        // 地形在 fillFromNoise 里一次写完。
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        // 隧道由本生成器自己雕刻。
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // 深海生物后续单独实现。
    }

    @Override
    public int getGenDepth() {
        return ModDimensions.HEIGHT;
    }

    @Override
    public int getMinY() {
        return ModDimensions.MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return ModDimensions.CEILING_BOTTOM_Y;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        return type == Heightmap.Types.OCEAN_FLOOR_WG
                ? ModDimensions.WATER_BOTTOM_Y
                : ModDimensions.CEILING_BOTTOM_Y + 15;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] states = new BlockState[ModDimensions.HEIGHT];
        BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int i = 0; i < states.length; i++) {
            int y = ModDimensions.MIN_Y + i;
            states[i] = y >= ModDimensions.CEILING_BOTTOM_Y ? ice : stone;
        }
        return new NoiseColumn(ModDimensions.MIN_Y, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Barotrauma depth: " + ModDimensions.toMeters(pos.getY()) + " m");
    }
}
