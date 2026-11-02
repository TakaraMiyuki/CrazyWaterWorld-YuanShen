package com.opamb.barotrauma.ship;

/**
 * 船上各类设备的数量统计（扫描船体包围盒得到）。
 *
 * <p>2026-11-02：五台机器和舱门/舱口已按用户要求删除，只剩声纳台有统计意义。
 * 字段保留是为了不改 ShipSimulation / ShipState 的构造签名。</p>
 */
public record MachineCount(int reactor, int oxygen, int engine, int pump, int ballast,
                           int airlock, int sonar) {

    public static final MachineCount EMPTY = new MachineCount(0, 0, 0, 0, 0, 0, 0);

    public int total() {
        return reactor + oxygen + engine + pump + ballast + airlock + sonar;
    }

    public String describe() {
        return "声纳×" + sonar;
    }
}
