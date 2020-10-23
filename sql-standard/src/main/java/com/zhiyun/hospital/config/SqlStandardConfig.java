package com.zhiyun.hospital.config;

import com.zhiyun.hospital.EnableSqlStandard;
import com.zhiyun.hospital.interceptor.BlockAttackInnerBoostInterceptor;
import com.zhiyun.hospital.interceptor.CustomerIllegalSQLInterceptor;
import com.zhiyun.hospital.interceptor.PerformanceInterceptor;
import com.zhiyun.hospital.util.ArrayUtils;
import com.zhiyun.hospital.util.InterceptorIgnoreHelper;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Method;
import java.util.*;
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
public class SqlStandardConfig implements InitializingBean, ApplicationContextAware {
    private List<String> paths = null;
    private ApplicationContext applicationContext;
    @Autowired
    SqlSessionFactory sqlSessionFactory;
    @Autowired
    MybatisProperties mybatisProperties;

    /**
     * 增加过滤器
     */
    public void addInterceptor() {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        if (Objects.isNull(configuration)) {
            configuration = new Configuration();
        }
        configuration.addInterceptor(new BlockAttackInnerBoostInterceptor());
        configuration.addInterceptor(new CustomerIllegalSQLInterceptor(paths));
        Properties configurationProperties = mybatisProperties.getConfigurationProperties();
        PerformanceInterceptor performanceInterceptor = new PerformanceInterceptor();
        performanceInterceptor.setProperties(configurationProperties);
        configuration.addInterceptor(performanceInterceptor);

    }

    @Override
    public void afterPropertiesSet() throws Exception {
        List<Method> methodList = null;
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(EnableSqlStandard.class);
        for (String key : beansWithAnnotation.keySet()) {
            EnableSqlStandard annotation =
                AnnotationUtils.findAnnotation(beansWithAnnotation.get(key).getClass(), EnableSqlStandard.class);
            if (null != annotation && ArrayUtils.isNotEmpty(annotation.value())) {
                paths = Stream.of(annotation.value()).collect(Collectors.toList());
            }
        }
        addInterceptor();
        // 获取InterceptorIgnore的类信息
        MapperRegistry mapperRegistry = sqlSessionFactory.getConfiguration().getMapperRegistry();
        Collection<Class<?>> classList = mapperRegistry.getMappers();
        if (!CollectionUtils.isEmpty(classList)) {
            for (Class cl : classList) {
                Method[] methods = cl.getMethods();
                methodList = Arrays.asList(methods);
                if (!CollectionUtils.isEmpty(methodList)) {
                    for (Method method : methodList) {
                        InterceptorIgnoreHelper.initSqlParserInfoCache(cl.getName(), method);
                    }
                }
            }
        }

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

}
