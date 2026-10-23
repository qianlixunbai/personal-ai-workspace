package com.financeos.configuration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.account.service.AccountQueryService;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/** Opt-in wiring; the application must supply a dedicated Finance PostgreSQL DataSource. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workspace.finance.enabled", havingValue = "true", matchIfMissing = false)
@Import(AccountQueryService.class)
@MapperScan(basePackageClasses = {AccountMapper.class,
        com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper.class,
        com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper.class}, annotationClass = Mapper.class,
        sqlSessionTemplateRef = "financeSqlSessionTemplate")
public class FinanceAccountQueryConfiguration {

    @Bean(name = "financeSqlSessionFactory", defaultCandidate = false)
    SqlSessionFactory financeSqlSessionFactory(
            @Qualifier("financeDataSource") DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(NoLoggingImpl.class);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        return factory.getObject();
    }

    @Bean(name = "financeSqlSessionTemplate", defaultCandidate = false)
    SqlSessionTemplate financeSqlSessionTemplate(
            @Qualifier("financeSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }

    @Bean(name = "financeTransactionManager", defaultCandidate = false)
    JdbcTransactionManager financeTransactionManager(
            @Qualifier("financeDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
