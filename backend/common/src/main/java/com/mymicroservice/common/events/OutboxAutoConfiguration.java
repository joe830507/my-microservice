package com.mymicroservice.common.events;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(OutboxEventWriter.class)
public class OutboxAutoConfiguration {
}
