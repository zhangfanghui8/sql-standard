package com.zhiyun.hospital.config;

import com.zhiyun.hospital.EnableSqlStandard;
import com.zhiyun.hospital.interceptor.BlockAttackInnerBoostInterceptor;
import com.zhiyun.hospital.interceptor.CustomerIllegalSQLInterceptor;
import org.apache.ibatis.annotations.Mapper;
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
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * @author zhangfanghui
 * @Title:
 * @Description: 全局配置
 * @date 2020/10/14 17:28
 */
@Configurable
@ConditionalOnClass({SqlSessionFactory.class})
public class MybatisPlusGlobleConfig implements InitializingBean, ApplicationContextAware {
    private String path = null;
    private ApplicationContext applicationContext;

    @Bean
    public CustomerIllegalSQLInterceptor customerIllegalSQLInterceptor() {
        return new CustomerIllegalSQLInterceptor(path);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(EnableSqlStandard.class);
        for (String key : beansWithAnnotation.keySet()) {
            EnableSqlStandard annotation =
                AnnotationUtils.findAnnotation(beansWithAnnotation.get(key).getClass(), EnableSqlStandard.class);
            if (null != annotation && !StringUtils.isEmpty(annotation.value())) {
                path = annotation.value();
            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
