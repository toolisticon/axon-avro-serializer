package io.holixon.axon.avro.serializer.spring

import io.holixon.axon.avro.serializer.AvroSerializer
import io.holixon.avro.adapter.api.AvroSchemaRegistry
import io.holixon.avro.adapter.common.AvroAdapterDefault
import org.axonframework.serialization.Serializer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

open class AxonAvroSerializerConfiguration {
  companion object {
    const val EVENT_SERIALIZER = "eventSerializer"
  }

  @Bean
  @ConditionalOnMissingBean(AvroSerializer.Builder::class)
  fun defaultAxonSerializerBuilder(schemaRegistry: AvroSchemaRegistry): AvroSerializer.Builder = AvroSerializer.builder()
    .schemaRegistry(schemaRegistry)

  @Bean
  @Qualifier(EVENT_SERIALIZER)
  fun avroSerializer(builder: AvroSerializer.Builder): Serializer = AvroSerializer(builder)

}
