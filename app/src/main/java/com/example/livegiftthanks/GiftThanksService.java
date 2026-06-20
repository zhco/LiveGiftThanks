package com.example.livegiftthanks;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播间礼物监听 + 自动发文字感谢 无障碍服务
 *
 * 工作流程：
 * 1. 监听屏幕内容变化事件
 * 2. 遍历当前窗口所有节点文字，匹配礼物提示（如 "XXX 送出 嘉年华"）
 * 3. 防抖：同一礼物 3 秒内只触发一次
 * 4. 定位聊天输入框 → 模拟点击 → 输入感谢文字 → 模拟点击发送按钮
 */
public class GiftThanksService extends AccessibilityService {

    /** 供 MainActivity 查询服务状态 */
    public static volatile boolean isRunning = false;

    private static final String TAG = "GiftThanks";

    // ====== 可调参数 ======
    /** 防抖窗口（毫秒），同一送礼者在窗口内只感谢一次 */
    private static final long DEBOUNCE_MS = 3000;
    /** 每次操作之间的间隔（毫秒），给UI留反应时间 */
    private static final long ACTION_DELAY_MS = 200;

    // ====== 礼物匹配规则 ======
    // 按优先级排列的正则列表，覆盖主流平台的礼物提示格式
    private static final Pattern[] GIFT_PATTERNS = {
            // 抖音风格：XXX 送出 嘉年华
            Pattern.compile("(.+?)\\s*[送出赠送给了]+\\s*(.+?)(?:，|。|$|\\s)"),
            // 快手风格：XXX 送了 YYY
            Pattern.compile("(.+?)\\s*送了\\s*(.+?)(?:，|。|$|\\s)"),
            // 通用：XXX → YYY（礼物）
            Pattern.compile("(.+?)\\s*[→>]\\s*(.+?)(?:礼物|$)"),
            // 系统通知：恭喜 XXX 获得 XXX 送出的 XXX
            Pattern.compile("恭喜\\s*(.+?)\\s*获得\\s*(.+?)\\s*送出的\\s*(.+)"),
    };

    /** 已处理的礼物 key 集合（用户名+礼物名），用于防抖 */
    private final Set<String> processedKeys = new HashSet<>();
    /** 防抖清理 Handler */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 上一次处理的文字快照，用于去重（避免同一事件重复触发） */
    private String lastSnapshot = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 过滤自身 App，避免匹配到配置界面的提示文字
        CharSequence pkg = event.getPackageName();
        if (pkg != null && "com.example.livegiftthanks".equals(pkg.toString())) {
            return;
        }

        // 只处理内容变化和窗口状态变化
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // 收集当前窗口所有可见文字
            String snapshot = collectAllText(root);

            // 与上次快照相同则跳过
            if (snapshot.isEmpty() || snapshot.equals(lastSnapshot)) return;
            lastSnapshot = snapshot;

            // 匹配礼物
            GiftMatch match = findGift(snapshot);
            if (match != null) {
                Log.i(TAG, "检测到礼物: " + match.giver + " → " + match.gift);
                // 异步执行输入（避免阻塞无障碍回调）
                handler.post(() -> sendThanks(root, match));
            }
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
        Log.i(TAG, "服务已连接");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
    }

    // ==================== 文字收集 ====================

    /** 递归遍历节点树，收集所有可见文本 */
    private String collectAllText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        collectTextRecursive(node, sb);
        return sb.toString();
    }

    private void collectTextRecursive(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(text);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextRecursive(child, sb);
                child.recycle();
            }
        }
    }

    // ==================== 礼物匹配 ====================

    private GiftMatch findGift(String text) {
        for (Pattern p : GIFT_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String giver, gift;
                if (m.groupCount() == 3) {
                    // 恭喜 XXX 获得 YYY 送出的 ZZZ
                    giver = m.group(2).trim();
                    gift = m.group(3).trim();
                } else {
                    giver = m.group(1).trim();
                    gift = m.group(2).trim();
                }

                // 过滤明显不是礼物提示的匹配
                if (isInvalidMatch(giver, gift)) continue;

                String key = giver + "|" + gift;
                if (processedKeys.contains(key)) continue;

                processedKeys.add(key);
                // 延迟清除防抖
                handler.postDelayed(() -> processedKeys.remove(key), DEBOUNCE_MS);

                return new GiftMatch(giver, gift);
            }
        }
        return null;
    }

    /** 过滤误匹配 */
    private boolean isInvalidMatch(String giver, String gift) {
        // 送礼者名过长或过短
        if (giver.length() < 2 || giver.length() > 30) return true;
        // 礼物名过短
        if (gift.length() < 1) return true;
        // 排除非礼物关键词
        String[] blacklist = {"来了", "进入", "关注", "点赞", "分享", "直播间", "来了呀"};
        for (String w : blacklist) {
            if (gift.contains(w)) return true;
        }
        return false;
    }

    // ==================== 发送感谢 ====================

    private void sendThanks(AccessibilityNodeInfo root, GiftMatch match) {
        String thanksText = "感谢" + match.giver + "送来的" + match.gift + "！";

        // 0. 复制文本到剪贴板
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("thanks", thanksText);
        clipboard.setPrimaryClip(clip);

        // 1. 找到输入框并点击获取焦点
        AccessibilityNodeInfo inputField = findInputField(root);
        if (inputField == null) {
            inputField = tryFindInputByHeuristic(root);
        }
        if (inputField == null) {
            Log.e(TAG, "无法定位输入框");
            return;
        }
        clickNode(inputField);
        inputField.recycle();

        // 2. 等待焦点切换后重新查找输入框，执行粘贴
        handler.postDelayed(() -> {
            AccessibilityNodeInfo newRoot = getRootInActiveWindow();
            if (newRoot == null) return;

            AccessibilityNodeInfo focusedInput = findInputField(newRoot);
            if (focusedInput == null) {
                focusedInput = tryFindInputByHeuristic(newRoot);
            }
            if (focusedInput == null) {
                Log.e(TAG, "重新查找输入框失败");
                newRoot.recycle();
                return;
            }

            // 执行粘贴
            focusedInput.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            focusedInput.recycle();

            // 3. 点击发送按钮
            handler.postDelayed(() -> {
                AccessibilityNodeInfo sendRoot = getRootInActiveWindow();
                if (sendRoot == null) return;

                AccessibilityNodeInfo sendBtn = findSendButton(sendRoot);
                if (sendBtn != null) {
                    clickNode(sendBtn);
                    sendBtn.recycle();
                    Log.i(TAG, "已发送: " + thanksText);
                } else {
                    Log.w(TAG, "未找到发送按钮");
                }
                sendRoot.recycle();
            }, ACTION_DELAY_MS);

            newRoot.recycle();
        }, ACTION_DELAY_MS);
    }

    // ==================== 节点查找 ====================

    /** 查找输入框（EditText / 可编辑节点） */
    private AccessibilityNodeInfo findInputField(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();

        findInputFieldsRecursive(root, candidates);

        // 优先选择可见的、位于屏幕下半部分的（聊天输入框通常在底部）
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo node : candidates) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.bottom > bestY) {
                bestY = rect.bottom;
                if (best != null) best.recycle();
                best = node;
            } else {
                node.recycle();
            }
        }
        return best;
    }

    private void findInputFieldsRecursive(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isEditable() && node.isVisibleToUser()) {
            out.add(AccessibilityNodeInfo.obtain(node));
            return; // EditText 内部通常不需要继续深入
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findInputFieldsRecursive(child, out);
                child.recycle();
            }
        }
    }

    /** 启发式查找输入框：根据 hint 文字或 className */
    private AccessibilityNodeInfo tryFindInputByHeuristic(AccessibilityNodeInfo root) {
        if (root == null) return null;
        // 常见 hint
        String[] hints = {"说点什么", "发个弹幕", "聊天", "输入", "发言", "说两句"};
        for (String hint : hints) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(hint);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() && node.isVisibleToUser()) {
                    return node;
                }
                node.recycle();
            }
        }
        return null;
    }

    /** 查找发送按钮 */
    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root) {
        if (root == null) return null;
        String[] sendLabels = {"发送", "send", "发布", "→", "➤"};
        for (String label : sendLabels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() && node.isVisibleToUser()) {
                    return node;
                }
                node.recycle();
            }
        }
        return null;
    }

    // ==================== 节点操作 ====================

    /** 点击节点 */
    private void clickNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        // 优先 performAction
        if (node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        // 否则用手势点击坐标
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        clickCoordinate((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f);
    }

    /** 手势点击坐标 */
    private void clickCoordinate(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(builder.build(), null, null);
    }

    // ==================== 数据结构 ====================

    private static class GiftMatch {
        final String giver;
        final String gift;

        GiftMatch(String giver, String gift) {
            this.giver = giver;
            this.gift = gift;
        }
    }
}
