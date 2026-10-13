final class AutoJumpWindow {
    private long armedAt = -1;
    private long lastTouchAt = -1;
    private boolean taskMode = false;

    /** 报名/弹窗场景布防：拦截无触摸的自动外跳，用户触摸后视为手动放行。 */
    synchronized void arm(long now) {
        armedAt = now;
        lastTouchAt = -1;
        taskMode = false;
    }

    /** 任务场景布防（抽奖红包任务拦截外跳）：不受用户触摸影响，始终拦截。 */
    synchronized void armTask(long now) {
        armedAt = now;
        lastTouchAt = -1;
        taskMode = true;
    }

    synchronized void onTouch(long now) { lastTouchAt = now; }

    synchronized void onUserInput() {}

    synchronized void onManualStoreRequest() { armedAt = -1; }

    synchronized boolean isActive(long now) {
        return armedAt >= 0 && now >= armedAt && now - armedAt < 30000;
    }

    /**
     * 布防窗口内且布防后有过用户触摸：视为手动操作（如「小程序进店」、
     * 下单流程领红包按钮），放行。自动外跳（报名成功弹窗倒计时）发生时
     * 用户无触摸，仍被拦截。任务模式例外，始终拦截。
     */
    synchronized boolean manualSinceArm(long now) {
        return !taskMode && armedAt >= 0 && lastTouchAt >= armedAt;
    }
}
