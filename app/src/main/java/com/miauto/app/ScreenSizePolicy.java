// Adapted from guo-zhc/MiAuto (AGPL-3.0), which builds on this project's 0.1.1.
// See the "Credits and license" section of README.md for the full acknowledgement.
package com.miauto.app;

/**
 * 「这块屏的物理尺寸该以什么顺序报给 ICCOA SDK」的纯判定。
 *
 * <p>历史口径是无条件长边在前（{@code MainActivity.startIcocaLink} 的注释原话：车机就是
 * 横屏、SDK 只要横屏画布）。竖屏车机 / 竖屏平板上这个假设是错的：手机端按横屏画布协商
 * 编码，竖屏 Surface 上画面被拉变形；而 UIBC 触摸按 Surface <em>实际</em>尺寸映射
 * （{@code startCast(surface, w, h)} 的 w/h 只做除数），于是点击与所见内容整体错位。
 *
 * <p>口径与 {@code com.andrerinas.openheadunit.utils.Settings.ScreenOrientation} 对齐
 * （value 见 utils/Settings.kt 的枚举定义）：
 * <ul>
 *   <li>{@code PORTRAIT(4)} / {@code PORTRAIT_REVERSE(5)} → <b>短边在前</b>（竖屏画布）；</li>
 *   <li>{@code LANDSCAPE(2)} / {@code LANDSCAPE_REVERSE(3)} → <b>长边在前</b>（横屏画布，原行为）；</li>
 *   <li>{@code SYSTEM(0)} / {@code AUTO(1)} → <b>原样透传</b>当前窗口的长短边关系 —— 窗口方向
 *       就是用户此刻看到的方向，让协商画布跟设备形态解耦（竖屏平板 + SYSTEM 不再是隐性横屏）。</li>
 * </ul>
 *
 * <p>调用方拿到的就是「上报序」，直接喂 {@code setScreenSize}；无效尺寸原样返回 {@code {0, 0}}，
 * 由调用方的重试机制接手（见 {@code MainActivity.startIcocaLink} 的说明）。
 */
public final class ScreenSizePolicy {

    private ScreenSizePolicy() { }

    /** {@code Settings.ScreenOrientation.PORTRAIT}。 */
    public static final int PORTRAIT = 4;
    /** {@code Settings.ScreenOrientation.PORTRAIT_REVERSE}。 */
    public static final int PORTRAIT_REVERSE = 5;
    /** {@code Settings.ScreenOrientation.LANDSCAPE}。 */
    public static final int LANDSCAPE = 2;
    /** {@code Settings.ScreenOrientation.LANDSCAPE_REVERSE}。 */
    public static final int LANDSCAPE_REVERSE = 3;

    /**
     * @param rawW             {@code DeviceDisplay.realSizePx} 的宽（未归一的当前窗口/物理尺寸）
     * @param rawH             高
     * @param orientationValue {@code Settings.screenOrientation.value}（0..5）
     * @return {@code int[]{w, h}} 上报序；任一边非正时 {@code {0, 0}}
     */
    public static int[] reported(int rawW, int rawH, int orientationValue) {
        if (rawW <= 0 || rawH <= 0) return new int[]{0, 0};
        if (orientationValue == PORTRAIT || orientationValue == PORTRAIT_REVERSE) {
            return new int[]{Math.min(rawW, rawH), Math.max(rawW, rawH)};
        }
        if (orientationValue == LANDSCAPE || orientationValue == LANDSCAPE_REVERSE) {
            return new int[]{Math.max(rawW, rawH), Math.min(rawW, rawH)};
        }
        // SYSTEM / AUTO：窗口此刻是哪种方向就报哪种，不猜。
        return new int[]{rawW, rawH};
    }

    /**
     * 「这次连接要不要重新协商画布」的纯判定 —— 重连时拿「现在量到的画布」与「SDK 初始化时
     * 灌进 {@code UCarConfig} 的那份」比一比。
     *
     * <p>为什么需要它：协商画布在 {@code init()} 那一刻定死、运行期没有任何 API 能改，而
     * {@code start()} 在 {@code sdkInitialized} 为真时默认只走 reAdvertise（拿旧 config
     * 重播）。横竖互换、分辨率变化（换屏 / 改显示大小）都必须拆 SDK 重 init，否则手机端
     * 仍按旧画布编码 —— SYSTEM 档转过屏幕再连却还是老方向，就是这个漏洞。
     *
     * @param nowW  现在量到的画布宽（{@link #reported} 的产物）；未量到传 0
     * @param nowH  现在量到的画布高
     * @param initW SDK 初始化时灌进 config 的画布宽；从未记下传 0
     * @param initH SDK 初始化时灌进 config 的画布高
     * @return true = 需要拆 SDK 重 init（见 {@code IccoaManager#resumeLink}）
     */
    public static boolean needsRenegotiation(int nowW, int nowH, int initW, int initH) {
        // 没量到有效尺寸时不敢下结论：拆了重建只会拿到 buildConfig 的 1x1 兜底画布，更糟，
        // 宁可拿旧 config 重播。
        if (nowW <= 0 || nowH <= 0) return false;
        // init 时没记下画布（那次 init 的尺寸就是无效的，config 用了 Math.max(1, …) 的残缺值）
        // → 那份 config 不能被信任，重谈。
        if (initW <= 0 || initH <= 0) return true;
        return nowW != initW || nowH != initH;
    }

    /**
     * H.264 Level 4.1 的硬上限：单边 1920，像素 1920×1080。
     *
     * <p>来自实测：这台车机的 AVC 解码器（{@code c2.qti.avc.decoder}）按 1080×2400 协商时
     * 拿不到可解码的帧，画面全黑；同一台手机、同一条链路降到 540×1200 立刻出画。1080×2400
     * 同时越过了「单边 1920」和「4.1 的像素预算」两条线，而超限在握手阶段不会报错 —— 只有
     * 解码器配置失败、永远等不到首帧。所以两条限制一起卡，谁先到谁生效。
     */
    public static final int MAX_SIDE = 1920;
    /** 见 {@link #MAX_SIDE}。 */
    public static final int MAX_PIXELS = 1920 * 1080;

    /**
     * 把实测画布收拢到「这台车机一定能解」的上限，并尽量保留清晰度。
     *
     * <p>只缩不放、保持宽高比：先在单边上限上算一个比例，再在像素预算上算一个，取小的那个；
     * 结果向下取偶数（H.264 的 4:2:0 要求宽高都是偶数）。屏幕本来就在上限内时原样返回，
     * 不会为了「统一」白白降低一档清晰度。
     *
     * @param w 实测画布宽（{@link #reported} 的产物）
     * @param h 实测画布高
     * @return {@code int[]{w, h}} 上报序；入参无效时 {@code {0, 0}}
     */
    public static int[] fitForEncoder(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{0, 0};
        double scale = Math.min(
                (double) MAX_SIDE / (double) Math.max(w, h),
                Math.sqrt((double) MAX_PIXELS / ((double) w * (double) h)));
        if (scale >= 1.0) {
            return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
        }
        int outW = Math.max(2, ((int) Math.floor(w * scale)) & ~1);
        int outH = Math.max(2, ((int) Math.floor(h * scale)) & ~1);
        return new int[]{outW, outH};
    }

    /**
     * 在给定屏幕和选定的显示比例下，算出最终上报的画布。
     *
     * <p>{@code ratioNum/ratioDen <= 0} 表示「跟随屏幕」（设置里的「自动」）：
     * 直接用屏幕的比例，行为与 {@link #fitForEncoder} 一致。指定比例时，先在这块屏里取<b>最大的
     * 同比例矩形</b>（整幅画面都能显示、只留黑边），再交给 {@link #fitForEncoder} 收进解码预算。
     *
     * <p>注意两个方向都要算：竖屏屏上选 16:9，得到的是「宽受限」的矩形；横屏屏上选 9:16，
     * 得到的是「高受限」的矩形。
     *
     * @param panelW 屏幕宽
     * @param panelH 屏幕高
     * @param ratioNum 比例分子（宽），0 表示跟随屏幕
     * @param ratioDen 比例分母（高），0 表示跟随屏幕
     * @return {@code int[]{w, h}}；屏幕尺寸无效时 {@code {0, 0}}
     */
    public static int[] canvasFor(int panelW, int panelH, int ratioNum, int ratioDen) {
        if (panelW <= 0 || panelH <= 0) return new int[]{0, 0};
        if (ratioNum <= 0 || ratioDen <= 0) return fitForEncoder(panelW, panelH);
        double target = (double) ratioNum / (double) ratioDen;
        int w = panelW;
        int h = (int) Math.round(w / target);
        if (h > panelH) {
            h = panelH;
            w = (int) Math.round(h * target);
        }
        w = Math.max(2, w & ~1);
        h = Math.max(2, h & ~1);
        return fitForEncoder(w, h);
    }
}
