package com.kellen.storageconfig;

/** 可安全对外投影的固定故障；不保留 SDK、凭据或网络异常 cause。 */
public final class StorageFailure extends IllegalStateException {
    private final String code;
    private final int status;
    public StorageFailure(String code, int status) { super("文件存储操作未完成"); this.code=code; this.status=status; }
    public String code() { return code; }
    public int status() { return status; }
}
