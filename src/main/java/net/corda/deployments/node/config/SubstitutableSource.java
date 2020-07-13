package net.corda.deployments.node.config;

import kotlin.Pair;

import java.beans.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public interface SubstitutableSource {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    @interface SubstitutionTarget {
        String targetConfig();
    }

    default Map<String, String> toSubstitutionMap() {
        try {
            Class<? extends SubstitutableSource> ourClass = this.getClass();
            return Arrays.stream(Introspector.getBeanInfo(ourClass).getPropertyDescriptors())
                    .map(input -> Internal.getKeyValue(input, this))
                    .filter(input -> !Internal.objectProperties.contains(input.getFirst()))
                    .collect(Collectors.toMap(Pair<String, String>::getFirst, Pair<String, String>::getSecond));
        } catch (IntrospectionException e) {
            throw new RuntimeException(e);
        }
    }


    class Internal {
        static BeanInfo objectBeanInfo;

        static {
            try {
                objectBeanInfo = Introspector.getBeanInfo(Object.class);
            } catch (IntrospectionException e) {
                throw new RuntimeException(e);
            }
        }

        static Set<String> objectProperties = Arrays.stream(objectBeanInfo.getPropertyDescriptors()).map(FeatureDescriptor::getName)
                .collect(Collectors.toSet());

        private static Pair<String, String> getKeyValue(PropertyDescriptor propertyDescriptor, Object object) {
            try {
                Optional<Object> value = Optional.ofNullable(propertyDescriptor.getReadMethod().invoke(object));
                return new Pair<String, String>(propertyDescriptor.getName(), value.orElse("null").toString());
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
