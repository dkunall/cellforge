package com.cellforge.cell.tenancy.adapter.persistence;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new RlsTransactionManager(dataSource);
    }
}
