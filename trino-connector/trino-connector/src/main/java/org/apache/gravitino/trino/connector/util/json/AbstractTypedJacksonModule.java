/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gravitino.trino.connector.util.json;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.jsontype.impl.AsPropertyTypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.impl.AsPropertyTypeSerializer;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerFactory;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.IOException;
import java.util.function.Function;

/**
 * This class is reference to Trino source code io.trino.metadata.AbstractTypedJacksonModule, It
 * used to preform XXXHandle serialization
 */
public abstract class AbstractTypedJacksonModule<T> extends SimpleModule {
  private static final String TYPE_PROPERTY = "@gravitino-connector-type";

  /**
   * Constructs a new AbstractTypedJacksonModule with the specified base class, name resolver, and
   * class resolver.
   *
   * @param baseClass the base class
   * @param nameResolver the name resolver
   * @param classResolver the class resolver
   */
  protected AbstractTypedJacksonModule(
      Class<T> baseClass,
      Function<Object, String> nameResolver,
      Function<String, Class<?>> classResolver) {
    super(baseClass.getSimpleName() + "Module", Version.unknownVersion());

    TypeIdResolver typeResolver = new InternalTypeResolver(nameResolver, classResolver);

    addSerializer(baseClass, new InternalTypeSerializer<>(baseClass, typeResolver));
    addDeserializer(baseClass, new InternalTypeDeserializer<>(baseClass, typeResolver));
  }

  private static class InternalTypeDeserializer<T> extends StdDeserializer<T> {
    private final TypeDeserializer typeDeserializer;

    public InternalTypeDeserializer(Class<T> baseClass, TypeIdResolver typeIdResolver) {
      super(baseClass);
      this.typeDeserializer =
          new AsPropertyTypeDeserializer(
              TypeFactory.defaultInstance().constructType(baseClass),
              typeIdResolver,
              TYPE_PROPERTY,
              false,
              null,
              PROPERTY,
              true);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
        throws IOException {
      return (T) typeDeserializer.deserializeTypedFromAny(jsonParser, deserializationContext);
    }
  }

  private static class InternalTypeSerializer<T> extends StdSerializer<T> {
    private final TypeSerializer typeSerializer;

    public InternalTypeSerializer(Class<T> baseClass, TypeIdResolver typeIdResolver) {
      super(baseClass);
      this.typeSerializer = new AsPropertyTypeSerializer(typeIdResolver, null, TYPE_PROPERTY);
    }

    @Override
    public void serialize(T value, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      if (value == null) {
        provider.defaultSerializeNull(generator);
        return;
      }

      try {
        Class<?> type = value.getClass();
        JsonSerializer<T> serializer = createSerializer(provider, type);
        serializer.serializeWithType(value, generator, provider, typeSerializer);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonSerializer<T> createSerializer(
        SerializerProvider provider, Class<?> type) throws JsonMappingException {
      JavaType javaType = provider.constructType(type);
      return (JsonSerializer<T>)
          BeanSerializerFactory.instance.createSerializer(provider, javaType);
    }
  }

  private static class InternalTypeResolver extends TypeIdResolverBase {
    private final Function<Object, String> nameResolver;
    private final Function<String, Class<?>> classResolver;

    public InternalTypeResolver(
        Function<Object, String> nameResolver, Function<String, Class<?>> classResolver) {
      this.nameResolver = requireNonNull(nameResolver, "nameResolver is null");
      this.classResolver = requireNonNull(classResolver, "classResolver is null");
    }

    @Override
    public String idFromValue(Object value) {
      return idFromValueAndType(value, value.getClass());
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
      requireNonNull(value, "value is null");
      String type = nameResolver.apply(value);
      checkArgument(type != null, "Unknown class: %s", value.getClass().getName());
      return type;
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
      requireNonNull(id, "id is null");
      Class<?> typeClass = classResolver.apply(id);
      checkArgument(typeClass != null, "Unknown type ID: %s", id);
      return context.getTypeFactory().constructType(typeClass);
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
      return JsonTypeInfo.Id.NAME;
    }
  }
}
