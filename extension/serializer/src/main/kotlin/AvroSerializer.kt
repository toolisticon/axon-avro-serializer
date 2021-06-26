package io.holixon.axon.avro.serializer

import io.holixon.avro.adapter.api.AvroAdapterApi.schemaResolver
import io.holixon.avro.adapter.api.AvroSchemaReadOnlyRegistry
import io.holixon.avro.adapter.api.AvroSingleObjectEncoded
import io.holixon.avro.adapter.api.converter.SpecificRecordToGenericDataRecordConverter
import io.holixon.avro.adapter.api.converter.SpecificRecordToSingleObjectConverter
import io.holixon.avro.adapter.api.ext.ByteArrayExt.toHexString
import io.holixon.avro.adapter.common.AvroAdapterDefault
import io.holixon.avro.adapter.common.converter.DefaultSpecificRecordToGenericDataRecordChangingSchemaConverter
import io.holixon.avro.adapter.common.converter.DefaultSpecificRecordToSingleObjectSchemaChangingConverter
import io.holixon.axon.avro.serializer.converter.AvroSingleObjectEncodedToGenericDataRecordTypeConverter
import io.holixon.axon.avro.serializer.converter.GenericDataRecordToAvroSingleObjectEncodedConverter
import io.holixon.axon.avro.serializer.ext.TypeExt.isUnknown
import io.holixon.axon.avro.serializer.fn.SchemaTypeSerializer
import io.holixon.axon.avro.serializer.revision.SchemaBasedRevisionResolver
import org.apache.avro.generic.GenericData
import org.apache.avro.specific.SpecificRecordBase
import org.apache.avro.util.ClassUtils
import org.axonframework.messaging.MetaData
import org.axonframework.serialization.*
import org.axonframework.serialization.json.JacksonSerializer
import org.axonframework.serialization.upcasting.event.IntermediateEventRepresentation
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Serializer implementation that uses Avro Single Object format for serialization.
 */
class AvroSerializer private constructor(
  /**
   * Gets the revision
   */
  private val revisionResolver: SchemaBasedRevisionResolver,
  private val genericDataRecordSerializer: SchemaTypeSerializer<GenericData.Record>,
  private val specificRecordSerializer: SchemaTypeSerializer<SpecificRecordBase>,
  @Deprecated(message = "metaData should also be based on schema, this is just a temp. hack")
  private val metaDataSerializer: Serializer,
  /**
   * Axon converters, typically this is a [ChainingConverter] that contains SPI converters for base types and specific [GenericData.Record] converters for [IntermediateEventRepresentation] during upcasting.
   */
  private val converter: Converter,
  /**
   * Pass a logger instance to trace processing.
   */
  private val logger: Logger,
  @Deprecated("use encoder/decoder")
  private val specificRecordToSingleObjectConverter: SpecificRecordToSingleObjectConverter,
  @Deprecated("use encoder/decoder")
  private val specificRecordToGenericDataRecordConverter: SpecificRecordToGenericDataRecordConverter
) : Serializer {

  companion object {

    /**
     * Creates a [Builder] instance to configure the serializer.
     */
    @JvmStatic
    fun builder() = Builder()

    /**
     * Instantiate a default [AvroSerializer].
     *
     * The [RevisionResolver] is defaulted to a [SchemaBasedRevisionResolver], the [Converter] to a [ChainingConverter],
     * which is then by initialized by registering the converters for SpecificRecord, GenericData.Record and ByteArray.
     *
     * @return a [AvroSerializer]
     */
    @JvmStatic
    fun defaultSerializer(): AvroSerializer = builder().build()

    /**
     * Secondary constructor.
     */
    operator fun invoke(builder: Builder): AvroSerializer {
      val converter = if (builder.converter is ChainingConverter) {
        // use GenericData.Record as intermediate representation.
        // register ByteArray to GenericData.Record converter
        // register GenericData.Record to ByteArray converter
        (builder.converter as ChainingConverter).apply {
          this.registerConverter(AvroSingleObjectEncodedToGenericDataRecordTypeConverter(builder.schemaReadOnlyRegistry.schemaResolver()))
          this.registerConverter(GenericDataRecordToAvroSingleObjectEncodedConverter())
        }
      } else {
        builder.converter
      }

      return AvroSerializer(
        revisionResolver = builder.revisionResolver,
        genericDataRecordSerializer = SchemaTypeSerializer.genericRecordDataSerializer(converter),
        specificRecordSerializer = SchemaTypeSerializer.specificRecordDataSerializer(converter),
        metaDataSerializer = JacksonSerializer.defaultSerializer(),
        converter = converter,
        logger = LoggerFactory.getLogger(AvroSerializer::class.java),
        specificRecordToSingleObjectConverter = DefaultSpecificRecordToSingleObjectSchemaChangingConverter(
          schemaResolver = builder.schemaReadOnlyRegistry.schemaResolver()
        ),
        specificRecordToGenericDataRecordConverter = DefaultSpecificRecordToGenericDataRecordChangingSchemaConverter(
          schemaResolver = builder.schemaReadOnlyRegistry.schemaResolver()
        )
      )
    }
  }

  override fun classForType(type: SerializedType): Class<*> = if (SimpleSerializedType.emptyType() == type) {
    Void::class.java
  } else {
    try {
      // if the class can not be found, it is unknown

        // FIXME: here we do just class for name ... while in the converter we have a specific schema compatibility check ... is that correct?
      ClassUtils.forName(type.name)
    } catch (e: ClassNotFoundException) {
      UnknownSerializedType::class.java
    }
  }

  override fun typeForClass(type: Class<*>?): SerializedType {

    if (type == null || Void.TYPE == type || Void::class.java == type) {
      return SimpleSerializedType.emptyType()
    }
    // FIXME; checked with "is SpecificRecord" ... not against type
//    else if (type ==  SpecificRecordBase::class.java) {
//      val schema = type.schema.find(schemaReadOnlyRegistry = schemaReadOnlyRegistry)
//      return SchemaSerializedType(schema)
//    } else if (type is GenericData.Record) {
//      val schema = type.schema.find(schemaReadOnlyRegistry = schemaReadOnlyRegistry)
//      return SchemaSerializedType(schema)
//    }
    else {
      return SimpleSerializedType(type.name, revisionResolver.revisionOf(type))
    }
  }

  override fun getConverter(): Converter = converter

  /*
   * Support GenericDataRecord as intermediate format (see registered converters for them)
   */
  override fun <T : Any> canSerializeTo(expectedRepresentation: Class<T>): Boolean =
    GenericData.Record::class.java == expectedRepresentation ||
      converter.canConvert(AvroSingleObjectEncoded::class.java, expectedRepresentation)

  override fun <T : Any> serialize(data: Any, expectedRepresentation: Class<T>): SerializedObject<T> = when (data) {
    is MetaData -> metaDataSerializer.serialize(data, expectedRepresentation)
    is GenericData.Record -> genericDataRecordSerializer.serialize(data, expectedRepresentation)
    is SpecificRecordBase -> specificRecordSerializer.serialize(data, expectedRepresentation)
    else -> throw IllegalArgumentException("cannot serialize $data to $expectedRepresentation")
  }


  override fun <S : Any, T : Any> deserialize(serializedObject: SerializedObject<S>): T? {
    if (SerializedType.emptyType() == serializedObject.type) {
      return null
    }

    val type: Class<*> = classForType(serializedObject.type)
    if (type == MetaData::class.java) {
      return metaDataSerializer.deserialize(serializedObject)
    } else if (type.isUnknown()) {
      @Suppress("UNCHECKED_CAST")
      return UnknownSerializedType(this, serializedObject) as T
    }

    return try {
      @Suppress("UNCHECKED_CAST")
      when (serializedObject.contentType) {
        GenericData.Record::class.java -> {
          // we run into this branch if the byte array was converted and manipulated on the level of the intermediate representation
          // in this case the format is GenericData.Record
          (specificRecordToGenericDataRecordConverter.decode(serializedObject.data as GenericData.Record) as T)
            .apply {
              logger.debug("deserialized: ${(serializedObject.data as GenericData.Record)} to $this")
            }
        }
        else -> {
          val bytesSerialized = converter.convert(serializedObject, AvroSingleObjectEncoded::class.java)
          (specificRecordToSingleObjectConverter.decode(bytesSerialized.data) as T)
            .apply {
              logger.debug("deserialized: ${(bytesSerialized.data as ByteArray).toHexString()} to $this")
            }
        }
      }
    } catch (e: Exception) {
      throw SerializationException("Error while deserializing object", e)
    }
  }

  class Builder {
    var revisionResolver: SchemaBasedRevisionResolver = SchemaBasedRevisionResolver()
    var converter: Converter = ChainingConverter()
    var schemaReadOnlyRegistry: AvroSchemaReadOnlyRegistry = AvroAdapterDefault.inMemorySchemaRegistry()

    fun revisionResolver(revisionResolver: SchemaBasedRevisionResolver) = apply {
      this.revisionResolver = revisionResolver
    }

    fun converter(converter: Converter) = apply {
      this.converter = converter
    }

    fun schemaRegistry(schemaReadOnlyRegistry: AvroSchemaReadOnlyRegistry) = apply {
      this.schemaReadOnlyRegistry = schemaReadOnlyRegistry
    }

    fun build() = AvroSerializer(this)
  }
}
