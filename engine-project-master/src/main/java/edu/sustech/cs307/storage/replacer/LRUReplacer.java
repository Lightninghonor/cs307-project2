package edu.sustech.cs307.storage.replacer;

import java.util.*;

public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    /**
     * 从 LRUList 尾部取出最久未使用的帧（最近最少使用）。
     * 若无可驱逐帧则返回 -1。
     */
    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int frameId = LRUList.removeFirst();
        LRUHash.remove(frameId);
        return frameId;
    }

    /**
     * 固定一个帧：将其从 LRUList 移除（若存在），加入 pinnedFrames。
     * 若该帧已在 pinnedFrames 中，则忽略（重复 pin 不报错）。
     * 若总容量已满且该帧是全新帧，则抛出异常。
     */
    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            // 重复 pin 同一帧，忽略
            return;
        }
        if (LRUHash.contains(frameId)) {
            // 从 LRU 列表移回 pinned 状态
            LRUList.remove((Integer) frameId);
            LRUHash.remove(frameId);
            pinnedFrames.add(frameId);
            return;
        }
        // 全新帧，检查容量
        if (size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        pinnedFrames.add(frameId);
    }

    /**
     * 释放一个帧：将其从 pinnedFrames 移除，加入 LRUList 头部（最近使用端）。
     * 若该帧不在 pinnedFrames 中，则抛出异常。
     */
    public void Unpin(int frameId) {
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND: frameId=" + frameId);
        }
        pinnedFrames.remove(frameId);
        // 加入 LRUList 尾部（最近使用端），Victim 从头部取（最久未用端）
        LRUList.addLast(frameId);
        LRUHash.add(frameId);
    }

    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }

    @Override
    public void Reset() {
        pinnedFrames.clear();
        LRUHash.clear();
        LRUList.clear();
    }
}
