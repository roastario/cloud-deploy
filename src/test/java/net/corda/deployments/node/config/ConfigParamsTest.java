package net.corda.deployments.node.config;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import kotlin.text.Charsets;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigParamsTest {

    ClassGraph classGraph = new ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages("net.corda");
    ClassInfoList classesImplementing = classGraph
            .scan()
            .getClassesImplementing(SubstitutableSource.class.getCanonicalName());


    @Test
    public void allSubsitutionSourcesShouldBeAnnotated() {
        classesImplementing.forEach(ci -> {
            SubstitutableSource.SubstitutionTarget annotation = ci.loadClass().getAnnotation(SubstitutableSource.SubstitutionTarget.class);
            if (annotation == null) {
                Assert.fail("class: " + ci.getName() + " implements: " + SubstitutableSource.class
                        .getCanonicalName() + " but is missing " + SubstitutableSource.SubstitutionTarget.class
                        .getCanonicalName() + " annotation");
            }
        });

    }

    @Test
    public void configFilesAndConfigPojosMustBeInSync() {
        classesImplementing.forEach(ci -> {
            Class<?> sourceClass = ci.loadClass();
            String targetConfig = sourceClass.getAnnotation(SubstitutableSource.SubstitutionTarget.class).targetConfig();
            InputStream configAsStream = this.getClass().getClassLoader().getResourceAsStream(targetConfig);
            try {
                String configAsString = IOUtils.toString(configAsStream, Charsets.UTF_8);
                SubstitutableSource o = (SubstitutableSource) getUnsafe().allocateInstance(sourceClass);
                Map<String, String> substitutionMap = o.toSubstitutionMap();
                Pattern pattern = Pattern.compile("#\\{([a-zA-Z0-9]+)\\}");
                Matcher matcher = pattern.matcher(configAsString);
                List<String> replacements = new LinkedList<>();
                while (matcher.find()) {
                    String toReplaceEntry = (matcher.group(1));
                    replacements.add(toReplaceEntry);
                }
                Set<String> uniqueReplacements = new HashSet<>(replacements);
                if (uniqueReplacements.size() != replacements.size()) {
                    throw new IllegalStateException("there are duplicate replacement keys in " + targetConfig);
                }
                boolean configFileContainsAllFields = uniqueReplacements.containsAll(substitutionMap.keySet());
                boolean classContainsAllConfigFileKeys = substitutionMap.keySet().containsAll(uniqueReplacements);
                if (!configFileContainsAllFields) {
                    HashSet<String> clonedSet = new HashSet<>(substitutionMap.keySet());
                    clonedSet.removeAll(uniqueReplacements);
                    throw new IllegalStateException("Class: " + ci.getName() + " contains extra fields: "
                            + clonedSet + " compared to config file: " + targetConfig);
                }
                if (!classContainsAllConfigFileKeys) {
                    HashSet<String> clonedSet = new HashSet<>(uniqueReplacements);
                    clonedSet.removeAll(substitutionMap.keySet());
                    throw new IllegalStateException(
                            "Config: " + targetConfig + " contains extra fields: " + clonedSet + " compared to class file: " + ci.getName()
                    );
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }


        });
    }

    private Unsafe getUnsafe() {
        Field f = null;
        try {
            f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }
}