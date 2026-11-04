package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HullDamage 的 NBT round-trip：世界重开后耐久不清零的保证。 */
class HullDamageNbtTest {

    @Test
    void roundTripPreservesRatiosAndPositions() {
        HullDamage source = new HullDamage();
        source.setHealth(new BlockPos(1, 2, 3), 0.62F);
        source.setHealth(new BlockPos(-4, -70, 20), 0.0F);
        source.setHealth(new BlockPos(5, 5, 5), 1.0F); // 满血记录应被丢弃

        ListTag tag = source.saveNbt();
        HullDamage restored = new HullDamage();
        restored.loadNbt(tag);

        assertEquals(0.62F, restored.health(new BlockPos(1, 2, 3)), 1.0E-5F);
        assertEquals(0.0F, restored.health(new BlockPos(-4, -70, 20)), 1.0E-5F);
        assertEquals(1.0F, restored.health(new BlockPos(5, 5, 5)), 1.0E-5F);
        assertEquals(2, restored.entries().size());
    }

    @Test
    void loadReplacesPreviousEntries() {
        HullDamage source = new HullDamage();
        source.setHealth(new BlockPos(0, 0, 0), 0.5F);
        HullDamage target = new HullDamage();
        target.setHealth(new BlockPos(9, 9, 9), 0.25F);

        target.loadNbt(source.saveNbt());

        assertEquals(1, target.entries().size());
        assertEquals(0.5F, target.health(new BlockPos(0, 0, 0)), 1.0E-5F);
        assertTrue(target.health(new BlockPos(9, 9, 9)) > 0.999F);
    }

    @Test
    void emptyListClearsAll() {
        HullDamage target = new HullDamage();
        target.setHealth(new BlockPos(2, 2, 2), 0.1F);
        target.loadNbt(new ListTag());
        assertTrue(target.isEmpty());
    }
}
