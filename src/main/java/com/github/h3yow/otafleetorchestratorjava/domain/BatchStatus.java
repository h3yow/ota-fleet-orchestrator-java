package com.github.h3yow.otafleetorchestratorjava.domain;

/**
 * 升级批次生命周期状态。
 *
 * <pre>
 * DRAFT --start--> ROLLING --pause--> PAUSED --resume--> ROLLING
 *                    |                    |
 *                    +------> ROLLING_BACK ------> ROLLED_BACK
 * ROLLING --(全部设备上报且失败率达标)--> COMPLETED
 * </pre>
 */
public enum BatchStatus {
    /** 已创建、未开始下发。 */
    DRAFT,
    /** 正在分批下发，当前阶段激活。 */
    ROLLING,
    /** 手动暂停或失败阈值触发的自动暂停；终态上报仍会计数。 */
    PAUSED,
    /** 回滚执行中，拒绝所有下发与普通上报。 */
    ROLLING_BACK,
    /** 全部设备成功，批次完成（终态）。 */
    COMPLETED,
    /** 回滚完成（终态）。 */
    ROLLED_BACK
}
