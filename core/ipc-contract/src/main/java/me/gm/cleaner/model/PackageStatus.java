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
    // P1-A：维度 A 互斥终态（每个 PID 仅占其一）。
    // PARTIALLY_MOUNTED：已管理、采样完整、部分目标命中；
    // NOT_MOUNTED：已管理、采样完整、目标明确、零命中（检查失败归 UNKNOWN）；
    // UNMANAGED：已观测但该 (package,user) 无挂载目标，不进 managed 分母，
    //   不得与 MOUNTED/PARTIALLY/NOT_MOUNTED/UNKNOWN/DELETED/OVERRIDE 共存。
    public static final int PID_FLAG_PARTIALLY_MOUNTED = 1 << 7;
    public static final int PID_FLAG_NOT_MOUNTED = 1 << 8;
    public static final int PID_FLAG_UNMANAGED = 1 << 9;
    // 维度 B 正交证据：MOUNT_FAILED 仅表示发生过明确挂载失败的操作历史，
    // 不参与终态互斥；STARTUP_AWARE/MKDIR_FAILED 同理保持正交。

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
