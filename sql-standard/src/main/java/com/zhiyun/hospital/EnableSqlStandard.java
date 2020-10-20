package com.zhiyun.hospital;

import com.zhiyun.hospital.config.MybatisPlusGlobalConfig;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * @author zhangfanghui
 * @Title:
 * @Description: 开启sql规范的开关
 * @date 2020/10/14 17:10
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(value = MybatisPlusGlobalConfig.class)
public @interface EnableSqlStandard {

    String[] value() default "";
}
