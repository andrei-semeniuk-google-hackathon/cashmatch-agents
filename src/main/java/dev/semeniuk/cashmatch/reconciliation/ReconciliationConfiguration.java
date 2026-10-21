package dev.semeniuk.cashmatch.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(Tolerances.class)
class ReconciliationConfiguration {}
