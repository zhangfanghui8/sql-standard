package com.zhiyun.hospital.exception;

/**
 * 全局异常类
 *
 * @author zhangfanghui
 * @Title:
 * @Description:
 * @date 2020/10/15 20:44
 */
public class SqlStandardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SqlStandardException(String message) {
        super(message);
    }

    public SqlStandardException(Throwable throwable) {
        super(throwable);
    }

    public SqlStandardException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
