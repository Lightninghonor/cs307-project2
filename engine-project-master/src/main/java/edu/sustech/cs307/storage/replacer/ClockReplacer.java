package edu.sustech.cs307.storage.replacer;

/**
 * Clock 替换算法（二次机会算法）。
 *
 * 每个槽位有四种状态：
 *   EMPTY   - 槽位未被使用
 *   PINNED  - 帧被固定，不可驱逐
 *   UNUSED  - 可驱逐，引用位 = 0
 *   USED    - 可驱逐，引用位 = 1（有一次二次机会）
 *
 * 时钟指针循环扫描：
 *   遇到 USED  → 改为 UNUSED，继续
 *   遇到 UNUSED → 驱逐，返回 frameId
 *   遇到 PINNED/EMPTY → 跳过
 */
public class ClockReplacer implements PageReplacer {

    private static final int EMPTY  = 0;
    private static final int PINNED = 1;
    private static final int UNUSED = 2;
    private static final int USED   = 3;

    private final int maxSize;
    private final int[] status;   // 每个槽位的状态
    private final int[] frameIds; // 每个槽位存储的 frameId（-1 表示空）
    private int clockHand;        // 时钟指针（指向下一个待检查槽位）
    private int count;            // 当前已占用槽位数（PINNED + UNUSED + USED）

    public ClockReplacer(int numPages) {
        this.maxSize = numPages;
        this.status = new int[numPages];
        this.frameIds = new int[numPages];
        this.clockHand = 0;
        this.count = 0;
        for (int i = 0; i < numPages; i++) {
            status[i] = EMPTY;
            frameIds[i] = -1;
        }
    }

    /**
     * 找到 frameId 对应的槽位索引，若不存在返回 -1。
     */
    private int findSlot(int frameId) {
        for (int i = 0; i < maxSize; i++) {
            if (status[i] != EMPTY && frameIds[i] == frameId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找到一个 EMPTY 槽位，若不存在返回 -1。
     */
    private int findEmptySlot() {
        for (int i = 0; i < maxSize; i++) {
            if (status[i] == EMPTY) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 驱逐一个可替换的帧（UNUSED 优先，USED 给一次二次机会）。
     * 若无可驱逐帧返回 -1。
     */
    @Override
    public int Victim() {
        if (count == 0) return -1;

        // 检查是否有可驱逐帧（UNUSED 或 USED）
        boolean hasEvictable = false;
        for (int i = 0; i < maxSize; i++) {
            if (status[i] == UNUSED || status[i] == USED) {
                hasEvictable = true;
                break;
            }
        }
        if (!hasEvictable) return -1;

        // 时钟扫描，最多扫描两圈（第一圈给 USED 帧降级机会，第二圈驱逐 UNUSED）
        int scanned = 0;
        int limit = maxSize * 2;
        while (scanned < limit) {
            int slot = clockHand % maxSize;
            if (status[slot] == USED) {
                // 给一次二次机会：降级为 UNUSED
                status[slot] = UNUSED;
            } else if (status[slot] == UNUSED) {
                // 驱逐
                int victimId = frameIds[slot];
                status[slot] = EMPTY;
                frameIds[slot] = -1;
                count--;
                clockHand = (slot + 1) % maxSize;
                return victimId;
            }
            clockHand = (slot + 1) % maxSize;
            scanned++;
        }
        return -1;
    }

    /**
     * 固定一个帧：
     * - 若已在 USED/UNUSED 状态，改为 PINNED
     * - 若已是 PINNED，忽略（重复 pin）
     * - 若是全新帧，找空槽插入为 PINNED
     * - 若容量已满且是全新帧，抛出异常
     */
    @Override
    public void Pin(int frameId) {
        int slot = findSlot(frameId);
        if (slot != -1) {
            // 已存在，改为 PINNED（无论之前是什么状态）
            status[slot] = PINNED;
            return;
        }
        // 全新帧
        if (count >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        int emptySlot = findEmptySlot();
        status[emptySlot] = PINNED;
        frameIds[emptySlot] = frameId;
        count++;
    }

    /**
     * 释放一个帧：将其从 PINNED 改为 USED（引用位=1）。
     * 若帧不存在或帧不处于 PINNED 状态（已被 Unpin 过），抛出异常。
     */
    @Override
    public void Unpin(int frameId) {
        int slot = findSlot(frameId);
        if (slot == -1 || status[slot] != PINNED) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND: frameId=" + frameId);
        }
        status[slot] = USED;
    }

    /**
     * 返回当前已占用槽位数（PINNED + UNUSED + USED）。
     */
    @Override
    public int size() {
        return count;
    }
}
