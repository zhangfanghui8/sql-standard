package com.zhiyun.hospital;

import com.zhiyun.hospital.config.SqlStandardConfig;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author zhangfanghui
 * @Title:
 * @Description: 开启sql规范的开关
 * @date 2020/10/14 17:10
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(value = SqlStandardConfig.class)
public @interface EnableSqlStandard {
    /**
     * 具体需要规范的路径
     * @return
     */
    String[] value() default "";
}
