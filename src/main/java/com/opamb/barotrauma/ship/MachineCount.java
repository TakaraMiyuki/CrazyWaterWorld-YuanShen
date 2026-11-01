package com.opamb.barotrauma.ship;

/**
 * 船上各类机器的数量统计（扫描船体包围盒得到）。
 */
public record MachineCount(int reactor, int oxygen, int engine, int pump, int ballast,
                           int airlock, int sonar, int helm) {

    public static final MachineCount EMPTY = new MachineCount(0, 0, 0, 0, 0, 0, 0, 0);

    public int total() {
        return reactor + oxygen + engine + pump + ballast + airlock + sonar + helm;
    }

    public String describe() {
        return "反应堆×" + reactor + " 制氧机×" + oxygen + " 引擎×" + engine
                + " 水泵×" + pump + " 压载舱×" + ballast + " 气闸×" + airlock
                + " 声纳×" + sonar + " 驾驶台×" + helm;
    }
}
