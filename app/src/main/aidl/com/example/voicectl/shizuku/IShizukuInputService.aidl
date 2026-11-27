package com.example.voicectl.shizuku;

/**
 * shell 进程内的输入桥接。
 *
 * 安全约束：只暴露强类型的 int 参数方法，没有 String / Bundle 命令槽位。
 * 命令名在服务端固定为 /system/bin/input 的子命令字面量，禁止外部传入。
 */
interface IShizukuInputService {
    /** Shizuku UserService 销毁约定（AIDL 事务码 16777114） */
    void destroy() = 16777114;

    int tap(int x, int y) = 1;
    int swipe(int x1, int y1, int x2, int y2, int durationMs) = 2;
    int keyEvent(int keyCode) = 3;
    int probe() = 4;
}
