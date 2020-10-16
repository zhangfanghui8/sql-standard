package com.zhiyun.hospital.util;

/**
 * @author zhangfanghui
 * @Title:
 * @Description:
 * @date 2020/10/15 21:13
 */

import com.zhiyun.hospital.exception.SqlStandardException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 加密工具类
 *
 * @author hubin
 * @since 2018-08-02
 */
public class EncryptUtils {

    /**
     * MD5 Base64 加密
     *
     * @param str 待加密的字符串
     * @return 加密后的字符串
     */
    public static String md5Base64(String str) {
        //确定计算方法
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            //加密后的字符串
            byte[] src = md5.digest(str.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(src);
        } catch (Exception e) {
            throw new SqlStandardException(e.getMessage());
        }
    }
}

