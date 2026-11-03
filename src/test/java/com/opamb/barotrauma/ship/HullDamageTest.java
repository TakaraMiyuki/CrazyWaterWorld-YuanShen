package com.opamb.barotrauma.ship;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HullDamageTest {

    private static final float PLATING = HullDamage.PLATING_MAX_HEALTH;

    @Test
    void damageAndRepairTrackRatio() {
        HullDamage damage = new HullDamage();
        BlockPos pos = new BlockPos(1, 2, 3);

        assertEquals(1.0F, damage.health(pos), 1.0E-4F);
        assertEquals(0.75F, damage.applyDamage(pos, 50.0F, PLATING), 1.0E-4F);
        assertEquals(0.75F, damage.health(pos), 1.0E-4F);
        assertTrue(damage.isDamaged(pos));

        assertEquals(1.0F, damage.repair(pos, 50.0F, PLATING), 1.0E-4F);
        assertTrue(damage.isEmpty());
        assertFalse(damage.isDamaged(pos));
    }

    @Test
    void overkillStoresZeroBecauseBlockStaysAsBreached() {
        HullDamage damage = new HullDamage();
        BlockPos pos = new BlockPos(4, 5, 6);

        assertEquals(0.0F, damage.applyDamage(pos, 999.0F, PLATING), 1.0E-4F);
        // 0% 方块不删除（切成 breached），记录必须保留，血条和焊接都靠它。
        assertEquals(0.0F, damage.health(pos), 1.0E-4F);
        assertFalse(damage.isEmpty());
        assertTrue(damage.isDamaged(pos));
        assertEquals(1.0F, damage.repair(pos, 999.0F, PLATING), 1.0E-4F);
        assertTrue(damage.isEmpty());
    }

    @Test
    void repairStopsAtFullAndRemovesEntry() {
        HullDamage damage = new HullDamage();
        BlockPos pos = new BlockPos(7, 8, 9);

        damage.applyDamage(pos, 30.0F, PLATING);
        assertEquals(1.0F, damage.repair(pos, 999.0F, PLATING), 1.0E-4F);
        assertEquals(1.0F, damage.health(pos), 1.0E-4F);
        assertTrue(damage.isEmpty());
    }

    @Test
    void shiftMovesEveryEntry() {
        HullDamage damage = new HullDamage();
        BlockPos pos = new BlockPos(2, 3, 4);
        damage.applyDamage(pos, 100.0F, PLATING);

        damage.shift(10, -2, 7);

        assertEquals(1.0F, damage.health(pos), 1.0E-4F);
        assertEquals(0.5F, damage.health(pos.offset(10, -2, 7)), 1.0E-4F);
    }

    @Test
    void copyAndRetainPruneDestroyedBlocks() {
        HullDamage source = new HullDamage();
        BlockPos keep = new BlockPos(0, 0, 0);
        BlockPos drop = new BlockPos(9, 9, 9);
        source.applyDamage(keep, 40.0F, PLATING);
        source.applyDamage(drop, 80.0F, PLATING);

        HullDamage copy = new HullDamage();
        copy.copyFrom(source);
        copy.retain(pos -> !pos.equals(drop));

        assertTrue(copy.isDamaged(keep));
        assertFalse(copy.isDamaged(drop));
        assertEquals(0.8F, copy.health(keep), 1.0E-4F);
    }

    @Test
    void setHealthClampsAndRemovesFull() {
        HullDamage damage = new HullDamage();
        BlockPos pos = new BlockPos(3, 3, 3);

        damage.setHealth(pos, 0.4F);
        assertEquals(0.4F, damage.health(pos), 1.0E-4F);
        damage.setHealth(pos, 2.0F);
        assertTrue(damage.isEmpty());
        damage.setHealth(pos, -1.0F);
        assertEquals(0.0F, damage.health(pos), 1.0E-4F);
    }
}
