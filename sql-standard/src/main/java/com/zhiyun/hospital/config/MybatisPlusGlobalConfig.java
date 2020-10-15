package com.zhiyun.hospital.config;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
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

/**
 * @author zhangfanghui
 * @Title:
 * @Description: 全局配置
 * @date 2020/10/14 17:28
 */
@Configurable
@ConditionalOnClass({SqlSessionFactory.class})
public class MybatisPlusGlobalConfig implements InitializingBean, ApplicationContextAware {
    private String path = null;
    private ApplicationContext applicationContext;

    /**
     * 注册所有的Mybatis-Plus的InnerInterceptor
     * @param list
     * @return
     */
    @Bean
    @ConditionalOnMissingBean
    MybatisPlusInterceptor mybatisPlusInterceptor(List<InnerInterceptor> list){
        MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();
        mybatisPlusInterceptor.setInterceptors(list);
        return mybatisPlusInterceptor;
    }
    /**
     * lllegal sql interceptor
     *
     * @return
     */
    @Bean
    public CustomerIllegalSQLInterceptor customerIllegalSQLInterceptor() {
        return new CustomerIllegalSQLInterceptor(path);
    }

    @Bean
    public BlockAttackInnerBoostInterceptor blockAttackInnerBoostInterceptor(){
        return new BlockAttackInnerBoostInterceptor();
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(EnableSqlStandard.class);
        for (String key : beansWithAnnotation.keySet()) {
            EnableSqlStandard annotation =
                AnnotationUtils.findAnnotation(beansWithAnnotation.get(key).getClass(), EnableSqlStandard.class);
            if (null != annotation && StringUtils.isNotBlank(annotation.value())) {
                path = annotation.value();
            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
