package com.zhiyun.hospital;

import java.lang.annotation.*;

/**
 * 内置插件的一些过滤规则
 * <p>
 * 支持注解在 mapper 上以及 mapper.method 上
 * 同时存在则 mapper.method 比 mapper 优先级高
 * <p>
 * 支持:
 * true 和 false , 1 和 0 , on 和 off
 * <p>
 * 各属性返回 true 表示不走插件(在配置了插件的情况下,不填则默认表示 false)
 *
 * @author miemie
 * @since 2020-07-31
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface InterceptorIgnore {

    /**
     * 攻击 SQL 阻断解析器,防止全表更新与删除
     */
    String blockAttack() default "";

    /**
     * 垃圾SQL拦截 {
     */
    String illegalSql() default "";
}
