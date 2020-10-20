package com.zhiyun.hospital.config;

import com.baomidou.mybatisplus.core.toolkit.ArrayUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zhiyun.hospital.EnableSqlStandard;
import com.zhiyun.hospital.interceptor.BlockAttackInnerBoostInterceptor;
import com.zhiyun.hospital.interceptor.CustomerIllegalSQLInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author zhangfanghui
 * @Title:
 * @Description: 全局配置
 * @date 2020/10/14 17:28
 */
@Configurable
@ConditionalOnClass({SqlSessionFactory.class})
public class MybatisPlusGlobalConfig implements InitializingBean, ApplicationContextAware {
    private List<String> paths = null;
    private ApplicationContext applicationContext;

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new CustomerIllegalSQLInterceptor(paths));
        interceptor.addInnerInterceptor(new BlockAttackInnerBoostInterceptor());
        return interceptor;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(EnableSqlStandard.class);
        for (String key : beansWithAnnotation.keySet()) {
            EnableSqlStandard annotation =
                AnnotationUtils.findAnnotation(beansWithAnnotation.get(key).getClass(), EnableSqlStandard.class);
            if (null != annotation && ArrayUtils.isNotEmpty(annotation.value())) {
                paths = Stream.of(annotation.value()).collect(Collectors.toList());


            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
