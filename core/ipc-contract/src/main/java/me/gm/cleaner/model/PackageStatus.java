package me.gm.cleaner.model;

import android.os.Parcel;
import android.os.Parcelable;

public class PackageStatus implements Parcelable {
    public int[] pids;
    public int[] pidFlags;
    public int[] userIds;

    public static final int GET_FROM_ALL_PROCESS = 0;
    public static final int GET_FROM_RECORDS = 1;

    public static final int PID_FLAG_MOUNTED = 1 << 0;
    public static final int PID_FLAG_STARTUP_AWARE = 1 << 1;
    public static final int PID_FLAG_DELETED = 1 << 2;
    public static final int PID_FLAG_OVERRIDE = 1 << 3;
    public static final int PID_FLAG_MKDIR_FAILED = 1 << 4;
    public static final int PID_FLAG_UNKNOWN = 1 << 5;
    public static final int PID_FLAG_MOUNT_FAILED = 1 << 6;
    // 维度 A 互斥终态：PARTIALLY_MOUNTED / NOT_MOUNTED / UNMANAGED（各自 KDoc 见下），
    // 每个 PID 至多占其一，且不得与 MOUNTED/UNKNOWN/DELETED/OVERRIDE 共存。
    // 维度 B 正交证据：MOUNT_FAILED 仅表示发生过明确挂载失败的操作历史，不参与终态互斥；
    // STARTUP_AWARE / MKDIR_FAILED 同理保持正交。两个维度不可混为一谈。
    // （本适配分支与上游在同一组 bit 上各自独立引入了这三面旗，合并时去重，语义一致。）

    /**
     * 目标里存在已生效的挂载项，但没有覆盖全部 target（0 &lt; 命中数 &lt; target 数）。
     *
     * <p>引入前这一状况落到 base flag = 0（既非 MOUNTED 也非 UNKNOWN），
     * 与"压根没挂上"在 UI 上无法区分，只能靠调用侧的兜底分支猜。
     */
    public static final int PID_FLAG_PARTIALLY_MOUNTED = 1 << 7;

    /** target 一个都没命中（{@code check_mounts} 返回空数组）。 */
    public static final int PID_FLAG_NOT_MOUNTED = 1 << 8;

    /**
     * 该 pid 从未被 Mounter 接管（不在 {@code Mounter.pidRecords} 里）。
     *
     * <p>它可能与 {@link #PID_FLAG_STARTUP_AWARE} 互斥存在。语义是"这不是我们的
     * 重定向对象"，例如宿主了他人组件的 OEM 推送进程。调用侧不应把它计入分母。
     */
    public static final int PID_FLAG_UNMANAGED = 1 << 9;

    public static final Creator<PackageStatus> CREATOR = new Creator<>() {
        @Override
        public PackageStatus createFromParcel(Parcel source) {
            return new PackageStatus(source);
        }

        @Override
        public PackageStatus[] newArray(int size) {
            return new PackageStatus[size];
        }
    };

    public PackageStatus() {
    }

    private PackageStatus(Parcel source) {
        pids = source.createIntArray();
        pidFlags = source.createIntArray();
        userIds = source.createIntArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeIntArray(pids);
        dest.writeIntArray(pidFlags);
        dest.writeIntArray(userIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
