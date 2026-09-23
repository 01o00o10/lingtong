/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpointConfig;

/** 在注册阶段校验注解端点的方法签名与消息处理规则。 */
final class JsrEndpointValidator {
  private JsrEndpointValidator() {}

  static void validate(ServerEndpointConfig config) throws DeploymentException {
    validate(config.getEndpointClass(), config);
  }

  static void validateClient(Class<?> endpoint, ClientEndpointConfig config)
      throws DeploymentException {
    validate(endpoint, config);
  }

  private static void validate(Class<?> endpoint, EndpointConfig config)
      throws DeploymentException {
    validateCodecs(config);
    if (Endpoint.class.isAssignableFrom(endpoint)) return;
    validateNonPublicAnnotations(endpoint);
    int opens = 0;
    int closes = 0;
    int errors = 0;
    Set<String> messageTypes = new LinkedHashSet<String>();
    for (Method method : endpoint.getMethods()) {
      if (method.isBridge() || method.isSynthetic()) continue;
      if (method.isAnnotationPresent(OnOpen.class)) {
        if (++opens > 1) fail(endpoint, "multiple @OnOpen methods");
        validateLifecycle(method, OnOpen.class);
      }
      if (method.isAnnotationPresent(OnClose.class)) {
        if (++closes > 1) fail(endpoint, "multiple @OnClose methods");
        validateLifecycle(method, OnClose.class);
      }
      if (method.isAnnotationPresent(OnError.class)) {
        if (++errors > 1) fail(endpoint, "multiple @OnError methods");
        validateLifecycle(method, OnError.class);
      }
      if (method.isAnnotationPresent(OnMessage.class)) {
        String nativeType = validateMessage(method, config);
        if (!messageTypes.add(nativeType)) {
          fail(endpoint, "multiple @OnMessage methods for " + nativeType + " messages");
        }
      }
    }
  }

  private static void validateCodecs(EndpointConfig config) throws DeploymentException {
    for (Class<? extends Decoder> type : config.getDecoders()) validateCodec(type, "Decoder");
    for (Class<? extends Encoder> type : config.getEncoders()) validateCodec(type, "Encoder");
  }

  private static void validateCodec(Class<?> type, String kind) throws DeploymentException {
    int modifiers = type.getModifiers();
    if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || type.isInterface()) {
      throw new DeploymentException(kind + " is not instantiable: " + type.getName());
    }
    try {
      if (!Modifier.isPublic(type.getConstructor().getModifiers())) {
        throw new NoSuchMethodException();
      }
    } catch (NoSuchMethodException e) {
      throw new DeploymentException(
          kind + " requires a public no-argument constructor: " + type.getName(), e);
    }
  }

  private static void validateNonPublicAnnotations(Class<?> endpoint) throws DeploymentException {
    for (Class<?> current = endpoint;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (hasEndpointAnnotation(method) && !Modifier.isPublic(method.getModifiers())) {
          fail(endpoint, "annotated method is not public: " + method.getName());
        }
      }
    }
  }

  private static boolean hasEndpointAnnotation(Method method) {
    return method.isAnnotationPresent(OnOpen.class)
        || method.isAnnotationPresent(OnClose.class)
        || method.isAnnotationPresent(OnError.class)
        || method.isAnnotationPresent(OnMessage.class);
  }

  private static void validateLifecycle(Method method, Class<? extends Annotation> annotation)
      throws DeploymentException {
    if (method.getReturnType() != void.class) invalid(method, "must return void");
    boolean requiredSeen = annotation != OnError.class;
    Annotation[][] annotations = method.getParameterAnnotations();
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (pathParam(annotations[i])) {
        if (!pathParameterType(parameters[i])) invalid(method, "invalid @PathParam type");
        continue;
      }
      Class<?> type = parameters[i];
      boolean valid =
          type == Session.class
              || annotation == OnOpen.class && type == EndpointConfig.class
              || annotation == OnClose.class && type == CloseReason.class
              || annotation == OnError.class && Throwable.class.isAssignableFrom(type);
      if (!valid) invalid(method, "unsupported lifecycle parameter " + type.getName());
      if (annotation == OnError.class && Throwable.class.isAssignableFrom(type)) {
        requiredSeen = true;
      }
    }
    if (!requiredSeen) invalid(method, "@OnError requires a Throwable parameter");
  }

  private static String validateMessage(Method method, EndpointConfig config)
      throws DeploymentException {
    Annotation[][] annotations = method.getParameterAnnotations();
    Class<?>[] parameters = method.getParameterTypes();
    Class<?> messageType = null;
    boolean partial = false;
    for (int i = 0; i < parameters.length; i++) {
      Class<?> type = parameters[i];
      if (pathParam(annotations[i])) {
        if (!pathParameterType(type)) invalid(method, "invalid @PathParam type");
      } else if (type == Session.class) {
        // Session is optional.
      } else if ((type == boolean.class || type == Boolean.class) && messageType != null) {
        if (partial) invalid(method, "multiple partial-message flags");
        partial = true;
      } else if (messageType == null) {
        messageType = type;
      } else if ((messageType == boolean.class || messageType == Boolean.class) && !partial) {
        partial = true;
        messageType = type;
      } else {
        invalid(method, "multiple message parameters");
      }
    }
    if (messageType == null) invalid(method, "missing message parameter");
    String nativeType = nativeType(messageType, config);
    if (nativeType == null)
      invalid(method, "message type has no Decoder: " + messageType.getName());
    if (partial
        && !(messageType == String.class
            || messageType == byte[].class
            || messageType == ByteBuffer.class)) {
      invalid(method, "partial messages require String, byte[] or ByteBuffer");
    }
    return nativeType;
  }

  private static String nativeType(Class<?> type, EndpointConfig config) {
    if (type == byte[].class || type == ByteBuffer.class || type == InputStream.class) {
      return "binary";
    }
    if (type == PongMessage.class) return "pong";
    if (type == String.class || type == Reader.class || primitiveTextType(type)) return "text";
    for (Class<? extends Decoder> decoder : config.getDecoders()) {
      if (decoderTarget(decoder, Decoder.Text.class) == type
          || decoderTarget(decoder, Decoder.TextStream.class) == type) return "text";
      if (decoderTarget(decoder, Decoder.Binary.class) == type
          || decoderTarget(decoder, Decoder.BinaryStream.class) == type) return "binary";
    }
    return null;
  }

  private static Class<?> decoderTarget(Class<?> type, Class<?> decoderInterface) {
    for (Type value : type.getGenericInterfaces()) {
      if (value instanceof ParameterizedType) {
        ParameterizedType parameterized = (ParameterizedType) value;
        if (parameterized.getRawType() == decoderInterface) {
          Type target = parameterized.getActualTypeArguments()[0];
          return target instanceof Class<?> ? (Class<?>) target : null;
        }
        if (parameterized.getRawType() instanceof Class<?>) {
          Class<?> target = decoderTarget((Class<?>) parameterized.getRawType(), decoderInterface);
          if (target != null) return target;
        }
      } else if (value instanceof Class<?>) {
        Class<?> target = decoderTarget((Class<?>) value, decoderInterface);
        if (target != null) return target;
      }
    }
    Class<?> parent = type.getSuperclass();
    return parent == null ? null : decoderTarget(parent, decoderInterface);
  }

  private static boolean primitiveTextType(Class<?> type) {
    return type == boolean.class
        || type == Boolean.class
        || type == byte.class
        || type == Byte.class
        || type == short.class
        || type == Short.class
        || type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class
        || type == float.class
        || type == Float.class
        || type == double.class
        || type == Double.class
        || type == char.class
        || type == Character.class;
  }

  private static boolean pathParameterType(Class<?> type) {
    return type == String.class || primitiveTextType(type);
  }

  private static boolean pathParam(Annotation[] annotations) {
    for (Annotation annotation : annotations) {
      if (annotation instanceof PathParam) return true;
    }
    return false;
  }

  private static void invalid(Method method, String reason) throws DeploymentException {
    throw new DeploymentException(
        "invalid endpoint method "
            + method.getDeclaringClass().getName()
            + "."
            + method.getName()
            + ": "
            + reason);
  }

  private static void fail(Class<?> endpoint, String reason) throws DeploymentException {
    throw new DeploymentException("invalid endpoint " + endpoint.getName() + ": " + reason);
  }
}
