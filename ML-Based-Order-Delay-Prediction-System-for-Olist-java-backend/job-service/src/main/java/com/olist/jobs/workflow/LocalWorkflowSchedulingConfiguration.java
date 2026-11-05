package com.olist.jobs.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jobs.runner", havingValue = "local")
public class LocalWorkflowSchedulingConfiguration { }
