/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.initialization;

import org.elasticsearch.core.internal.provider.ProviderLocator;
import org.elasticsearch.entitlement.bootstrap.EntitlementBootstrap;
import org.elasticsearch.entitlement.bridge.EntitlementChecker;
import org.elasticsearch.entitlement.instrumentation.CheckMethod;
import org.elasticsearch.entitlement.instrumentation.InstrumentationService;
import org.elasticsearch.entitlement.instrumentation.Instrumenter;
import org.elasticsearch.entitlement.instrumentation.MethodKey;
import org.elasticsearch.entitlement.instrumentation.Transformer;
import org.elasticsearch.entitlement.runtime.api.ElasticsearchEntitlementChecker;
import org.elasticsearch.entitlement.runtime.policy.Policy;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager;
import org.elasticsearch.entitlement.runtime.policy.Scope;
import org.elasticsearch.entitlement.runtime.policy.entitlements.CreateClassLoaderEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.Entitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ExitVMEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.FilesEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.FilesEntitlement.FileData;
import org.elasticsearch.entitlement.runtime.policy.entitlements.InboundNetworkEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.LoadNativeLibrariesEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ManageThreadsEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.OutboundNetworkEntitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.ReadStoreAttributesEntitlement;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.elasticsearch.entitlement.runtime.policy.entitlements.FilesEntitlement.Mode.READ_WRITE;

/**
 * Called by the agent during {@code agentmain} to configure the entitlement system,
 * instantiate and configure an {@link EntitlementChecker},
 * make it available to the bootstrap library via {@link #checker()},
 * and then install the {@link org.elasticsearch.entitlement.instrumentation.Instrumenter}
 * to begin injecting our instrumentation.
 */
public class EntitlementInitialization {

    private static final String AGENTS_PACKAGE_NAME = "co.elastic.apm.agent";
    private static final Module ENTITLEMENTS_MODULE = PolicyManager.class.getModule();

    private static ElasticsearchEntitlementChecker manager;

    interface InstrumentationInfoFactory {
        InstrumentationService.InstrumentationInfo of(String methodName, Class<?>... parameterTypes) throws ClassNotFoundException,
            NoSuchMethodException;
    }

    // Note: referenced by bridge reflectively
    public static EntitlementChecker checker() {
        return manager;
    }

    // Note: referenced by agent reflectively
    public static void initialize(Instrumentation inst) throws Exception {
        manager = initChecker();

        var latestCheckerInterface = getVersionSpecificCheckerClass(EntitlementChecker.class, Runtime.version().feature());

        Map<MethodKey, CheckMethod> checkMethods = new HashMap<>(INSTRUMENTATION_SERVICE.lookupMethods(latestCheckerInterface));
        Stream.of(
            fileSystemProviderChecks(),
            fileStoreChecks(),
            Stream.of(
                INSTRUMENTATION_SERVICE.lookupImplementationMethod(
                    SelectorProvider.class,
                    "inheritedChannel",
                    SelectorProvider.provider().getClass(),
                    EntitlementChecker.class,
                    "checkSelectorProviderInheritedChannel"
                )
            )
        )
            .flatMap(Function.identity())
            .forEach(instrumentation -> checkMethods.put(instrumentation.targetMethod(), instrumentation.checkMethod()));

        var classesToTransform = checkMethods.keySet().stream().map(MethodKey::className).collect(Collectors.toSet());

        Instrumenter instrumenter = INSTRUMENTATION_SERVICE.newInstrumenter(latestCheckerInterface, checkMethods);
        inst.addTransformer(new Transformer(instrumenter, classesToTransform), true);
        inst.retransformClasses(findClassesToRetransform(inst.getAllLoadedClasses(), classesToTransform));
    }

    private static Class<?>[] findClassesToRetransform(Class<?>[] loadedClasses, Set<String> classesToTransform) {
        List<Class<?>> retransform = new ArrayList<>();
        for (Class<?> loadedClass : loadedClasses) {
            if (classesToTransform.contains(loadedClass.getName().replace(".", "/"))) {
                retransform.add(loadedClass);
            }
        }
        return retransform.toArray(new Class<?>[0]);
    }

    private static PolicyManager createPolicyManager() {
        Map<String, Policy> pluginPolicies = EntitlementBootstrap.bootstrapArgs().pluginPolicies();
        Path[] dataDirs = EntitlementBootstrap.bootstrapArgs().dataDirs();

        // TODO(ES-10031): Decide what goes in the elasticsearch default policy and extend it
        var serverPolicy = new Policy(
            "server",
            List.of(
                new Scope("org.elasticsearch.base", List.of(new CreateClassLoaderEntitlement())),
                new Scope("org.elasticsearch.xcontent", List.of(new CreateClassLoaderEntitlement())),
                new Scope(
                    "org.elasticsearch.server",
                    List.of(
                        new ExitVMEntitlement(),
                        new ReadStoreAttributesEntitlement(),
                        new CreateClassLoaderEntitlement(),
                        new InboundNetworkEntitlement(),
                        new OutboundNetworkEntitlement(),
                        new LoadNativeLibrariesEntitlement(),
                        new ManageThreadsEntitlement(),
                        new FilesEntitlement(
                            List.of(new FilesEntitlement.FileData(EntitlementBootstrap.bootstrapArgs().tempDir().toString(), READ_WRITE))
                        )
                    )
                ),
                new Scope("org.apache.httpcomponents.httpclient", List.of(new OutboundNetworkEntitlement())),
                new Scope("io.netty.transport", List.of(new InboundNetworkEntitlement(), new OutboundNetworkEntitlement())),
                new Scope("org.apache.lucene.core", List.of(new LoadNativeLibrariesEntitlement(), new ManageThreadsEntitlement())),
                new Scope("org.apache.logging.log4j.core", List.of(new ManageThreadsEntitlement())),
                new Scope(
                    "org.elasticsearch.nativeaccess",
                    List.of(
                        new LoadNativeLibrariesEntitlement(),
                        new FilesEntitlement(Arrays.stream(dataDirs).map(d -> new FileData(d.toString(), READ_WRITE)).toList())
                    )
                )
            )
        );
        // agents run without a module, so this is a special hack for the apm agent
        // this should be removed once https://github.com/elastic/elasticsearch/issues/109335 is completed
        List<Entitlement> agentEntitlements = List.of(new CreateClassLoaderEntitlement(), new ManageThreadsEntitlement());
        var resolver = EntitlementBootstrap.bootstrapArgs().pluginResolver();
        return new PolicyManager(serverPolicy, agentEntitlements, pluginPolicies, resolver, AGENTS_PACKAGE_NAME, ENTITLEMENTS_MODULE);
    }

    private static Stream<InstrumentationService.InstrumentationInfo> fileSystemProviderChecks() throws ClassNotFoundException,
        NoSuchMethodException {
        var fileSystemProviderClass = FileSystems.getDefault().provider().getClass();

        var instrumentation = new InstrumentationInfoFactory() {
            @Override
            public InstrumentationService.InstrumentationInfo of(String methodName, Class<?>... parameterTypes)
                throws ClassNotFoundException, NoSuchMethodException {
                return INSTRUMENTATION_SERVICE.lookupImplementationMethod(
                    FileSystemProvider.class,
                    methodName,
                    fileSystemProviderClass,
                    EntitlementChecker.class,
                    "check" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1),
                    parameterTypes
                );
            }
        };

        var allVersionsMethods = Stream.of(
            instrumentation.of("newFileSystem", URI.class, Map.class),
            instrumentation.of("newFileSystem", Path.class, Map.class),
            instrumentation.of("newInputStream", Path.class, OpenOption[].class),
            instrumentation.of("newOutputStream", Path.class, OpenOption[].class),
            instrumentation.of("newFileChannel", Path.class, Set.class, FileAttribute[].class),
            instrumentation.of("newAsynchronousFileChannel", Path.class, Set.class, ExecutorService.class, FileAttribute[].class),
            instrumentation.of("newByteChannel", Path.class, Set.class, FileAttribute[].class),
            instrumentation.of("newDirectoryStream", Path.class, DirectoryStream.Filter.class),
            instrumentation.of("createDirectory", Path.class, FileAttribute[].class),
            instrumentation.of("createSymbolicLink", Path.class, Path.class, FileAttribute[].class),
            instrumentation.of("createLink", Path.class, Path.class),
            instrumentation.of("delete", Path.class),
            instrumentation.of("deleteIfExists", Path.class),
            instrumentation.of("readSymbolicLink", Path.class),
            instrumentation.of("copy", Path.class, Path.class, CopyOption[].class),
            instrumentation.of("move", Path.class, Path.class, CopyOption[].class),
            instrumentation.of("isSameFile", Path.class, Path.class),
            instrumentation.of("isHidden", Path.class),
            instrumentation.of("getFileStore", Path.class),
            instrumentation.of("checkAccess", Path.class, AccessMode[].class),
            instrumentation.of("getFileAttributeView", Path.class, Class.class, LinkOption[].class),
            instrumentation.of("readAttributes", Path.class, Class.class, LinkOption[].class),
            instrumentation.of("readAttributes", Path.class, String.class, LinkOption[].class),
            instrumentation.of("setAttribute", Path.class, String.class, Object.class, LinkOption[].class)
        );

        if (Runtime.version().feature() >= 20) {
            var java20EntitlementCheckerClass = getVersionSpecificCheckerClass(EntitlementChecker.class, 20);
            var java20Methods = Stream.of(
                INSTRUMENTATION_SERVICE.lookupImplementationMethod(
                    FileSystemProvider.class,
                    "readAttributesIfExists",
                    fileSystemProviderClass,
                    java20EntitlementCheckerClass,
                    "checkReadAttributesIfExists",
                    Path.class,
                    Class.class,
                    LinkOption[].class
                ),
                INSTRUMENTATION_SERVICE.lookupImplementationMethod(
                    FileSystemProvider.class,
                    "exists",
                    fileSystemProviderClass,
                    java20EntitlementCheckerClass,
                    "checkExists",
                    Path.class,
                    LinkOption[].class
                )
            );
            return Stream.concat(allVersionsMethods, java20Methods);
        }
        return allVersionsMethods;
    }

    private static Stream<InstrumentationService.InstrumentationInfo> fileStoreChecks() {
        var fileStoreClasses = StreamSupport.stream(FileSystems.getDefault().getFileStores().spliterator(), false)
            .map(FileStore::getClass)
            .distinct();
        return fileStoreClasses.flatMap(fileStoreClass -> {
            var instrumentation = new InstrumentationInfoFactory() {
                @Override
                public InstrumentationService.InstrumentationInfo of(String methodName, Class<?>... parameterTypes)
                    throws ClassNotFoundException, NoSuchMethodException {
                    return INSTRUMENTATION_SERVICE.lookupImplementationMethod(
                        FileStore.class,
                        methodName,
                        fileStoreClass,
                        EntitlementChecker.class,
                        "check" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1),
                        parameterTypes
                    );
                }
            };

            try {
                return Stream.of(
                    instrumentation.of("getFileStoreAttributeView", Class.class),
                    instrumentation.of("getAttribute", String.class),
                    instrumentation.of("getBlockSize"),
                    instrumentation.of("getTotalSpace"),
                    instrumentation.of("getUnallocatedSpace"),
                    instrumentation.of("getUsableSpace"),
                    instrumentation.of("isReadOnly"),
                    instrumentation.of("name"),
                    instrumentation.of("type")

                );
            } catch (NoSuchMethodException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Returns the "most recent" checker class compatible with the current runtime Java version.
     * For checkers, we have (optionally) version specific classes, each with a prefix (e.g. Java23).
     * The mapping cannot be automatic, as it depends on the actual presence of these classes in the final Jar (see
     * the various mainXX source sets).
     */
    private static Class<?> getVersionSpecificCheckerClass(Class<?> baseClass, int javaVersion) {
        String packageName = baseClass.getPackageName();
        String baseClassName = baseClass.getSimpleName();

        final String classNamePrefix;
        if (javaVersion < 19) {
            // For older Java versions, the basic EntitlementChecker interface and implementation contains all the supported checks
            classNamePrefix = "";
        } else if (javaVersion < 23) {
            classNamePrefix = "Java" + javaVersion;
        } else {
            // All Java version from 23 onwards will be able to use che checks in the Java23EntitlementChecker interface and implementation
            classNamePrefix = "Java23";
        }

        final String className = packageName + "." + classNamePrefix + baseClassName;
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("entitlement lib cannot find entitlement class " + className, e);
        }
        return clazz;
    }

    private static ElasticsearchEntitlementChecker initChecker() {
        final PolicyManager policyManager = createPolicyManager();

        final Class<?> clazz = getVersionSpecificCheckerClass(ElasticsearchEntitlementChecker.class, Runtime.version().feature());

        Constructor<?> constructor;
        try {
            constructor = clazz.getConstructor(PolicyManager.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("entitlement impl is missing no arg constructor", e);
        }
        try {
            return (ElasticsearchEntitlementChecker) constructor.newInstance(policyManager);
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    private static final InstrumentationService INSTRUMENTATION_SERVICE = new ProviderLocator<>(
        "entitlement",
        InstrumentationService.class,
        "org.elasticsearch.entitlement.instrumentation",
        Set.of()
    ).get();
}
