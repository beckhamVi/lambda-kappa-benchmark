package com.bi.ingestionlambda.speed;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "ingestion.speed.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic eventsTopic(@Value("${ingestion.topic:events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}