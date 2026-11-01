package com.opamb.barotrauma.sonar;

/**
 * 客户端缓存的最近一次声纳扫描帧。
 *
 * <p>纯数据，不放任何客户端专用类型，这样网络包处理器可以直接调用。</p>
 */
public final class ClientSonarCache {

    private static SonarScan.Frame frame;
    private static String status = "";
    private static int power = 100;
    private static int headingX;
    private static int headingY;
    private static int headingZ;
    private static int mode = SonarScan.Mode.ACTIVE.id();
    private static int depth;
    private static int speedTenths;
    private static int diveTenths;
    private static boolean autopilot;
    private static boolean hasTarget;
    private static int targetX;
    private static int targetY;
    private static int targetZ;
    private static long stamp;

    private ClientSonarCache() {
    }

    public static void accept(int range, int[] points, int powerPercent, int hx, int hy, int hz,
                              int modeId, int depthMeters, int speed, int dive, int autopilotFlag,
                              boolean targetSet, int tx, int ty, int tz, String statusText) {
        frame = new SonarScan.Frame(range, points);
        power = powerPercent;
        headingX = hx;
        headingY = hy;
        headingZ = hz;
        mode = modeId;
        depth = depthMeters;
        speedTenths = speed;
        diveTenths = dive;
        autopilot = autopilotFlag != 0;
        hasTarget = targetSet;
        targetX = tx;
        targetY = ty;
        targetZ = tz;
        status = statusText;
        stamp = System.currentTimeMillis();
    }

    public static SonarScan.Frame frame() {
        return frame;
    }

    public static String status() {
        return status;
    }

    public static int power() {
        return power;
    }

    public static int headingX() {
        return headingX;
    }

    public static int headingY() {
        return headingY;
    }

    public static int headingZ() {
        return headingZ;
    }

    public static SonarScan.Mode mode() {
        return SonarScan.Mode.byOrdinal(mode);
    }

    public static int depth() {
        return depth;
    }

    public static int speedTenths() {
        return speedTenths;
    }

    public static int diveTenths() {
        return diveTenths;
    }

    public static boolean autopilot() {
        return autopilot;
    }

    public static boolean hasTarget() {
        return hasTarget;
    }

    public static int targetX() {
        return targetX;
    }

    public static int targetY() {
        return targetY;
    }

    public static int targetZ() {
        return targetZ;
    }

    /** 目标点相对玩家的水平距离（格）。 */
    public static double targetDistance() {
        return hasTarget ? Math.sqrt((double) targetX * targetX + (double) targetY * targetY
                + (double) targetZ * targetZ) : 0.0D;
    }

    public static long stamp() {
        return stamp;
    }

    /** 新数据到达后 1 秒内的亮度加成（0..1）。 */
    public static float flash() {
        if (stamp == 0L) {
            return 0.0F;
        }
        float age = (System.currentTimeMillis() - stamp) / 1000.0F;
        return Math.max(0.0F, 1.0F - age);
    }

    public static void clear() {
        frame = null;
        status = "";
        stamp = 0L;
    }
}
