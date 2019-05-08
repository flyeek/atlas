package com.taobao.android.builder.insant;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.api.AppVariantContext;
import com.android.build.gradle.internal.api.AppVariantOutputContext;
import com.android.build.gradle.internal.api.AwbTransform;
import com.android.build.gradle.internal.incremental.IncrementalVisitor;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.incremental.InstantRunBuildMode;
import com.android.build.gradle.internal.incremental.InstantRunVerifierStatus;
import com.android.build.gradle.internal.incremental.TBIncrementalChangeVisitor;
import com.android.build.gradle.internal.incremental.TBIncrementalSupportVisitor;
import com.android.build.gradle.internal.incremental.TBIncrementalVisitor;
import com.android.build.gradle.internal.incremental.TBIncrementalVisitor.ErrorType;
import com.android.build.gradle.internal.pipeline.ExtendedContentType;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.InstantRunVariantScope;
import com.android.build.gradle.options.DeploymentDevice;
import com.android.ide.common.internal.WaitableExecutor;
import com.android.sdklib.AndroidVersion;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.taobao.android.builder.AtlasBuildContext;
import com.taobao.android.builder.dependency.model.AwbBundle;
import com.taobao.android.builder.insant.matcher.MatcherCreator;
import com.taobao.android.builder.insant.visitor.ModifyClassVisitor;
import com.taobao.android.builder.tools.Profiler;
import com.taobao.android.builder.tools.multidex.mutli.MappingReaderProcess;
import org.gradle.api.logging.Logging;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static com.android.builder.model.AndroidProject.FD_OUTPUTS;

/**
 * TaobaoInstantRunTransform
 *
 * @author zhayu.ll
 * @date 18/10/12
 */
public class TaobaoInstantRunTransform extends Transform {
    private final File injectSuccessFile;
    private InstantRunVariantScope transformScope;
    protected static final ILogger LOGGER =
            new LoggerWrapper(Logging.getLogger(TaobaoInstantRunTransform.class));
    private final WaitableExecutor executor;
    private AppVariantContext variantContext;
    private AppVariantOutputContext variantOutputContext;
    private final ImmutableList.Builder<String> generatedClasses3Names = ImmutableList.builder();
    private final AndroidVersion targetPlatformApi;
    private File injectFailedFile;
    private List<String> errors = new ArrayList<>();
    private List<String> success = new ArrayList<>();

    MappingReaderProcess mappingReaderProcess = new MappingReaderProcess();

    private Map<String, String> modifyClasses = new HashMap<>();


    public TaobaoInstantRunTransform(AppVariantContext variantContext, AppVariantOutputContext variantOutputContext, WaitableExecutor executor, InstantRunVariantScope transformScope) {
        this.variantContext = variantContext;
        this.variantOutputContext = variantOutputContext;
        this.executor = executor;
        this.transformScope = transformScope;
        this.targetPlatformApi =
                DeploymentDevice.getDeploymentDeviceAndroidVersion(
                        transformScope.getGlobalScope().getProjectOptions());

        injectFailedFile = new File(variantContext.getProject().getBuildDir(), "outputs/warning-instrument-inject-error.properties");
        injectSuccessFile = new File(variantContext.getProject().getBuildDir(), "outputs/instrument.properties");

    }

    @Override
    public String getName() {
        return "taobaoInstantRun";
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return com.android.build.gradle.internal.pipeline.TransformManager.CONTENT_CLASS;
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getOutputTypes() {
        return ImmutableSet.of(
                QualifiedContent.DefaultContentType.CLASSES,
                ExtendedContentType.CLASSES_ENHANCED);
    }

    @NonNull
    @Override
    public Set<QualifiedContent.Scope> getScopes() {
        return Sets.immutableEnumSet(QualifiedContent.Scope.PROJECT, QualifiedContent.Scope.SUB_PROJECTS);
    }

    @NonNull
    @Override
    public Set<QualifiedContent.Scope> getReferencedScopes() {
        return Sets.immutableEnumSet(QualifiedContent.Scope.EXTERNAL_LIBRARIES,
                QualifiedContent.Scope.PROVIDED_ONLY);
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void transform(TransformInvocation invocation) throws IOException, TransformException, InterruptedException {
        Profiler.start("InstantRun Profile");
        Profiler.enter("load progaurd info");
        File mappingFile = loadProguardFile();
        boolean isMinifyEnabled = variantContext.getVariantConfiguration().getBuildType().isMinifyEnabled();

        if (mappingFile.exists() && isMinifyEnabled) {
            proguard.obfuscate.MappingReader mappingReader = new proguard.obfuscate.MappingReader(mappingFile);
            mappingReader.pump(mappingReaderProcess);
        }
        Profiler.release();
        if (null != variantContext.apContext.getApExploredFolder() && variantContext.apContext
                .getApExploredFolder().exists()) {
            File errorFile = new File(variantContext.apContext.getApExploredFolder(), "warning-instrument-inject-error.properties");
            if (errorFile.exists()) {
                org.apache.commons.io.FileUtils.readLines(errorFile).forEach(new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        if (s.split(":").length > 2) {
                            errors.add(s.split(":")[1]);
                        }
                    }
                });
            }
        }
        Profiler.enter("check jar inputs");
        List<JarInput> jarInputs =
                invocation
                        .getInputs()
                        .stream()
                        .flatMap(input -> input.getJarInputs().stream())
                        .collect(Collectors.toList());

        Preconditions.checkState(
                jarInputs.isEmpty(), "Unexpected inputs: " + Joiner.on(", ").join(jarInputs));
        Profiler.release();
        InstantRunBuildContext buildContext = transformScope.getInstantRunBuildContext();
        Profiler.enter("do Transform");
        buildContext.startRecording(InstantRunBuildContext.TaskType.INSTANT_RUN_TRANSFORM);
        try {
            doTransform(invocation);
        } finally {
            buildContext.stopRecording(InstantRunBuildContext.TaskType.INSTANT_RUN_TRANSFORM);
        }
        Profiler.release();
        Profiler.enter("dump enhanced result");
        org.apache.commons.io.FileUtils.writeLines(injectFailedFile, errors);
        org.apache.commons.io.FileUtils.writeLines(injectSuccessFile, success);
        Profiler.release();
        Profiler.release();
        LOGGER.warning(Profiler.dump());
    }


    public void doTransform(TransformInvocation invocation) throws IOException, TransformException, InterruptedException {
        InstantRunBuildContext buildContext = transformScope.getInstantRunBuildContext();
        // if we do not run in incremental mode, we should automatically switch to COLD swap.
        buildContext.setVerifierStatus(
                InstantRunVerifierStatus.BUILD_NOT_INCREMENTAL);

        // If this is not a HOT_WARM build, clean up the enhanced classes and don't generate new
        // ones during this build.
        boolean inHotSwapMode =
                buildContext.getBuildMode() == InstantRunBuildMode.HOT_WARM;

        TransformOutputProvider outputProvider = invocation.getOutputProvider();
        if (outputProvider == null) {
            throw new IllegalStateException("InstantRunTransform called with null output");
        }

        File classesTwoOutput =
                outputProvider.getContentLocation(
                        "classes", com.android.build.gradle.internal.pipeline.TransformManager.CONTENT_CLASS, getScopes(), Format.DIRECTORY);
        FileUtils.cleanOutputDir(classesTwoOutput);

        File classesThreeOutput =
                outputProvider.getContentLocation(
                        "enhanced_classes",
                        ImmutableSet.of(ExtendedContentType.CLASSES_ENHANCED),
                        getScopes(),
                        Format.DIRECTORY);

        AtlasBuildContext.atlasMainDexHelperMap.get(variantContext.getVariantName()).getInputDirs().clear();
        AtlasBuildContext.atlasMainDexHelperMap.get(variantContext.getVariantName()).getInputDirs().add(classesTwoOutput);
        AtlasBuildContext.atlasMainDexHelperMap.get(variantContext.getVariantName()).getInputDirs().add(classesThreeOutput);


        // first get all referenced input to construct a class loader capable of loading those
        // classes. This is useful for ASM as it needs to load classes
        List<URL> referencedInputUrls = getAllClassesLocations(
            invocation.getInputs(), invocation.getReferencedInputs());
        // This class loader could be optimized a bit, first we could create a parent class loader
        // with the android.jar only that could be stored in the GlobalScope for reuse. This
        // class loader could also be store in the VariantScope for potential reuse if some
        // other transform need to load project's classes.
        URLClassLoader urlClassLoader = new NonDelegatingUrlClassloader(referencedInputUrls);

        List<TransformException> exceptions = new ArrayList<>();
        List<WorkItem> workItems = new ArrayList<>();
        Profiler.enter("main collect");
        for (TransformInput input : invocation.getInputs()) {
            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                File inputDir = directoryInput.getFile();
                int inputDirPathLength = inputDir.getPath().length() + 1;
                //LOGGER.warning("inputDir:", inputDir.getAbsolutePath());

                java.nio.file.Files.walkFileTree(Paths.get(inputDir.getPath()), new AbstractClassFileVisitor() {
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) {
                        File file = path.toFile();
                        if (file.getPath().endsWith(SdkConstants.DOT_CLASS)) {
                            executor.execute(() -> {
                                String relativePath = file.getPath().substring(inputDirPathLength);
                                // For R class, just copy.
                                if(isRClass(file)) {
                                    errors.add(ErrorType.R_CLASS.name() + ":" + relativePath);
                                    File outputFile = new File(classesTwoOutput, relativePath);
                                    Files.createParentDirs(outputFile);
                                    FileUtils.copyFile(file, outputFile);
                                } else {
                                    filterClassToWorkItem(urlClassLoader, file, relativePath, classesTwoOutput, classesThreeOutput, exceptions);
                                }
                                return null;
                            });
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

        Profiler.release();
        Profiler.enter("awb collect");
        Map<AwbBundle, File> awbBundleFileMap = new HashMap<>();
        variantOutputContext.getAwbTransformMap().values().forEach(awbTransform -> {
            File awbClassesTwoOutout = variantOutputContext.getAwbClassesInstantOut(awbTransform.getAwbBundle());
            //LOGGER.warning("InstantAwbclassOut[" + awbTransform.getAwbBundle().getPackageName() + "]---------------------" + awbClassesTwoOutout.getAbsolutePath());
            FileUtils.mkdirs(awbClassesTwoOutout);
            try {
                FileUtils.cleanOutputDir(awbClassesTwoOutout);
            } catch (IOException e) {
                e.printStackTrace();
            }


            // Just copy remote bundle classes.
            if (awbTransform.getAwbBundle().isRemote) {
                awbTransform.getInputDirs().forEach(dir -> {
                    //LOGGER.warning("InstantAwbclassDir[" + awbTransform.getAwbBundle().getPackageName() + "]---------------------" + dir.getAbsolutePath());
                    executor.execute(() -> copyClasses(dir, awbClassesTwoOutout));
                });
            } else {
                awbTransform.getInputDirs().forEach(dir -> {
                    //LOGGER.warning("InstantAwbclassDir[" + awbTransform.getAwbBundle().getPackageName() + "]---------------------" + dir.getAbsolutePath());
                    if (dir.getParentFile().getParentFile().getName().equals("awb-classes")) {
                        // Just copy R classes.
                        executor.execute(() -> copyClasses(dir, awbClassesTwoOutout));
                    } else {
                        try {
                            java.nio.file.Files.walkFileTree(Paths.get(dir.getPath()), new AbstractClassFileVisitor() {
                                @Override
                                public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes)
                                    throws IOException {
                                    File file = path.toFile();
                                    if (file.getPath().endsWith(SdkConstants.DOT_CLASS)) {
                                        executor.execute(() -> {
                                            String relativePath = file.getPath().substring(dir.getPath().length() + 1);
                                            filterClassToWorkItem(urlClassLoader, file, relativePath, awbClassesTwoOutout, classesThreeOutput, exceptions);
                                            return null;
                                        });
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException("Instant Run Transform awb traverse file tree failed. " + e.getMessage());
                        }
                    }
                });
            }

            awbBundleFileMap.put(awbTransform.getAwbBundle(), awbClassesTwoOutout);
        });
        Profiler.release();
        Profiler.enter("enhance classes");

        if (exceptions.size() > 0) {
            throw exceptions.get(0);
        }

        try {
            // wait for all work items completion.
            executor.waitForTasksWithQuickFail(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransformException(e);
        } catch (Exception e) {
            throw new TransformException(e);
        }
        Profiler.release();
        variantOutputContext.getAwbTransformMap().values().parallelStream().forEach(awbTransform -> {
            awbTransform.getInputLibraries().clear();
            awbTransform.getInputFiles().clear();
            awbTransform.getInputDirs().clear();
            awbTransform.getInputDirs().add(awbBundleFileMap.get(awbTransform.getAwbBundle()));
        });

        // If our classes.2 transformations indicated that a cold swap was necessary,
        // clean up the classes.3 output folder as some new files may have been generated.
        if (generatedClasses3Names.build().size() == 0) {
            FileUtils.cleanOutputDir(classesThreeOutput);
        }

        wrapUpOutputs(classesTwoOutput, classesThreeOutput);
    }

    private static abstract class AbstractClassFileVisitor implements FileVisitor<Path> {

        @Override
        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public abstract FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException;

        @Override
        public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
            return FileVisitResult.CONTINUE;
        }
    }

    private boolean isRClass(@NonNull File inputFile) {

        if (inputFile.getPath().endsWith(SdkConstants.DOT_CLASS)) {
            String fileName = inputFile.getName();
            return fileName.equals("R" + SdkConstants.DOT_CLASS) || fileName.startsWith("R$");
        } else {
            return false;
        }
    }

    private Void copyClasses(File originDir, File targetDir) {
        try {
            org.apache.commons.io.FileUtils.copyDirectory(originDir, targetDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private void filterClassToWorkItem(URLClassLoader urlClassLoader, File classFile, String relativePath, File classesTwoOutput,
                                       File classesThreeOutput, List<TransformException> exceptions) {
        if (!variantContext.getBuildType().getPatchConfig().isCreateIPatch()) {
            doClassEnhance(urlClassLoader, true,relativePath, classFile, classesTwoOutput);
            return;
        }

        PatchPolicy patchPolicy = parseClassPolicy(classFile);
        String className = convertPathToDotClassName(relativePath);

        switch (patchPolicy) {
            case ADD:
                modifyClasses.put(className, PatchPolicy.ADD.name());
                doClassEnhance(urlClassLoader, true, relativePath, classFile, classesThreeOutput);
                break;
            case MODIFY:
                if (errors.contains(relativePath)) {
                    exceptions.add(new TransformException(relativePath + " is not support modify because inject error in base build!"));
                }
                modifyClasses.put(className, PatchPolicy.MODIFY.name());
                doClassEnhance(urlClassLoader, false, relativePath, classFile, classesThreeOutput);
                doClassEnhance(urlClassLoader, true,relativePath, classFile, classesTwoOutput);
                break;
            default:
                break;
        }
    }

    private PatchPolicy parseClassPolicy(File file) {
        if (!variantContext.getBuildType().getPatchConfig().isCreateIPatch()) {
            return PatchPolicy.NONE;
        }


        final PatchPolicy[] patchPolicy = {PatchPolicy.NONE};
        BufferedInputStream inputStream = null;
        try {
            inputStream = new BufferedInputStream(new FileInputStream(file));
            ClassReader classReader = new ClassReader(inputStream);
            classReader.accept(new ModifyClassVisitor(Opcodes.ASM5, patchPolicy), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            e.printStackTrace();
//            throw new RuntimeException(e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return patchPolicy[0];
    }

    private interface WorkItem {

        Void doWork() throws IOException;
    }

    @NonNull
    private List<URL> getAllClassesLocations(
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedInputs) throws MalformedURLException {

        List<URL> referencedInputUrls = new ArrayList<>();

        // add the bootstrap classpath for jars like android.jar
        for (File file : transformScope.getInstantRunBootClasspath()) {
            referencedInputUrls.add(file.toURI().toURL());
        }

        // now add the project dependencies.
        for (TransformInput referencedInput : referencedInputs) {
            addAllClassLocations(referencedInput, referencedInputUrls);
        }

        // and finally add input folders.
        for (TransformInput input : inputs) {
            addAllClassLocations(input, referencedInputUrls);
        }


        variantOutputContext.getAwbTransformMap().values().forEach(new Consumer<AwbTransform>() {
            @Override
            public void accept(AwbTransform awbTransform) {
                try {
                    addAllClassLocations(awbTransform.getInputFiles(), referencedInputUrls);
                    addAllClassLocations(awbTransform.getInputDirs(), referencedInputUrls);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            }
        });

        return referencedInputUrls;
    }

    private static void addAllClassLocations(TransformInput transformInput, List<URL> into)
            throws MalformedURLException {

        for (DirectoryInput directoryInput : transformInput.getDirectoryInputs()) {
            into.add(directoryInput.getFile().toURI().toURL());
        }
        for (JarInput jarInput : transformInput.getJarInputs()) {
            into.add(jarInput.getFile().toURI().toURL());
        }
    }

    private static void addAllClassLocations(Collection<File> classFiles, List<URL> into)
            throws MalformedURLException {
        for (File classFile : classFiles) {
            into.add(classFile.toURI().toURL());
        }

    }


    private static void deleteOutputFile(
            @NonNull IncrementalVisitor.VisitorBuilder visitorBuilder,
            @NonNull File inputDir, @NonNull File inputFile, @NonNull File outputDir) {
        String inputPath = FileUtils.relativePossiblyNonExistingPath(inputFile, inputDir);
        String outputPath =
                visitorBuilder.getMangledRelativeClassFilePath(inputPath);
        File outputFile = new File(outputDir, outputPath);
        if (outputFile.exists()) {
            try {
                FileUtils.delete(outputFile);
            } catch (IOException e) {
                // it's not a big deal if the file cannot be deleted, hopefully
                // no code is still referencing it, yet we should notify.
                LOGGER.warning("Cannot delete %1$s file.\nCause: %2$s",
                        outputFile, Throwables.getStackTraceAsString(e));
            }
        }
    }


    private static class NonDelegatingUrlClassloader extends URLClassLoader {

        public NonDelegatingUrlClassloader(@NonNull List<URL> urls) {
            super(urls.toArray(new URL[urls.size()]), null);
        }

        @Override
        public URL getResource(String name) {
            // Never delegate to bootstrap classes.
            return findResource(name);
        }
    }

    private void doClassEnhance(URLClassLoader urlClassLoader, boolean isClassTwo, String relativePath, File inputFile, File outputDir) {
        ClassLoader currentThreadClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(urlClassLoader);
        try {
            if (isClassTwo) {
                transformToClasses2Format(relativePath, inputFile, outputDir, Status.ADDED);
            } else {
                transformToClasses3Format(relativePath, inputFile, outputDir);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(currentThreadClassLoader);
        }
    }

    @Nullable
    protected Void transformToClasses3Format(String relativePath, File inputFile, File outputDir) {


        File outputFile = null;
        try {
            outputFile= TBIncrementalVisitor.instrumentClass(
                targetPlatformApi.getFeatureLevel(),
                relativePath,
                inputFile,
                outputDir,
                TBIncrementalChangeVisitor.VISITOR_BUILDER,
                LOGGER,
                null,
                false,
                variantContext.getAtlasExtension().getTBuildConfig().isPatchConstructors(),
                variantContext.getAtlasExtension().getTBuildConfig().isPatchEachMethod(),
                variantContext.getAtlasExtension().getTBuildConfig().isSupportAddCallSuper(),
                variantContext.getAtlasExtension().getTBuildConfig().getPatchSuperMethodCount());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // if the visitor returned null, that means the class cannot be hot swapped or more likely
        // that it was disabled for InstantRun, we don't add it to our collection of generated
        // classes and it will not be part of the Patch class that apply changes.

        if (outputFile == null) {
            transformScope
                    .getInstantRunBuildContext()
                    .setVerifierStatus(InstantRunVerifierStatus.INSTANT_RUN_DISABLED);
            LOGGER.info("Class %s cannot be hot swapped.", inputFile);
            return null;
        }
        generatedClasses3Names.add(convertPathToDotClassName(relativePath));
        return null;
    }

    /**
     *
     * @param relativePath relativePath of class.
     * @param inputFile Must be a class file.
     * @param outputDir
     * @param change
     * @return Void
     */
    @Nullable
    protected Void transformToClasses2Format(
            @NonNull final String relativePath,
            @NonNull final File inputFile,
            @NonNull final File outputDir,
            @NonNull final Status change) {
        try {
            String originalPath = originalPath(relativePath);

            Set<String> excludePkgs = variantContext.getAtlasExtension().getTBuildConfig().getInjectExcludePkgs();
            Set<String> includePkgs = variantContext.getAtlasExtension().getTBuildConfig().getInjectPkgs();

            if (includePkgs.size() > 0) {
                for (String s : includePkgs) {
                    boolean matched = MatcherCreator.create(s).match(originalPath);
                    if (matched) {
                        File file = TBIncrementalVisitor.instrumentClass(
                            targetPlatformApi.getFeatureLevel(),
                            relativePath,
                            inputFile,
                            outputDir,
                            TBIncrementalSupportVisitor.VISITOR_BUILDER,
                            LOGGER,
                            errorType -> {
                                errors.add(errorType.name() + ":" + relativePath);
                            },
                            variantContext.getAtlasExtension().getTBuildConfig().isInjectSerialVersionUID(), variantContext.getAtlasExtension().getTBuildConfig().isPatchConstructors(), variantContext.getAtlasExtension().getTBuildConfig().isPatchEachMethod(), variantContext.getAtlasExtension().getTBuildConfig().isSupportAddCallSuper(), variantContext.getAtlasExtension().getTBuildConfig().getPatchSuperMethodCount());
                        if (file.length() == inputFile.length()) {
                            errors.add("NO INJECT:" + relativePath);
                        } else {
                            success.add("SUCCESS INJECT:" + relativePath);
                        }

                        return null;
                    }
                }

                File outputFile = new File(outputDir, relativePath);
                try {
                    Files.createParentDirs(outputFile);
                    Files.copy(inputFile, outputFile);
                    errors.add("NO INJECT:" + relativePath);
                    return null;
                } catch (IOException e1) {
                    e1.printStackTrace();
                }


            } else {
                for (String s : excludePkgs) {
                    boolean matched = MatcherCreator.create(s).match(originalPath);
                    if (matched) {
                        File outputFile = new File(outputDir, relativePath);
                        try {
                            Files.createParentDirs(outputFile);
                            Files.copy(inputFile, outputFile);
                            errors.add("NO INJECT:" + relativePath);
                            return null;
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                    }

                }

                File file = TBIncrementalVisitor.instrumentClass(
                    targetPlatformApi.getFeatureLevel(),
                    relativePath,
                    inputFile,
                    outputDir,
                    TBIncrementalSupportVisitor.VISITOR_BUILDER,
                    LOGGER,
                    errorType -> {
                        errors.add(errorType.name() + ":" + relativePath);
                    },
                    variantContext.getAtlasExtension().getTBuildConfig().isInjectSerialVersionUID(), variantContext.getAtlasExtension().getTBuildConfig().isPatchConstructors(), variantContext.getAtlasExtension().getTBuildConfig().isPatchEachMethod(), variantContext.getAtlasExtension().getTBuildConfig().isSupportAddCallSuper(), variantContext.getAtlasExtension().getTBuildConfig().getPatchSuperMethodCount());
                if (file.length() == inputFile.length()) {
                    errors.add("NO INJECT:" + relativePath);
                } else {
                    success.add("SUCCESS INJECT:" + relativePath);
                }

            }

        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.warning("exception instrumentClass:" + inputFile.getPath());
            errors.add("EXCEPTION:" + relativePath);
            File outputFile = new File(outputDir, relativePath);
            try {
                Files.createParentDirs(outputFile);
                Files.copy(inputFile, outputFile);
            } catch (IOException e1) {
                e1.printStackTrace();
            }

        }

        return null;
    }

    private String originalPath(String path) {
        if (mappingReaderProcess.classMapping.size() == 0) {
            return path;
        } else {
            String className = convertPathToDotClassName(path);
            String orign = mappingReaderProcess.classMapping.get(className);

            if (orign == null || orign.equals(className)) {
                return path;
            }
            return orign.replace(".", "/") + ".class";
        }
    }

    private String convertPathToDotClassName(String classPath) {
        // ".class" length is 6.
        return classPath.substring(0, classPath.length() - 6).replace("/", ".");
    }

    private File loadProguardFile() {
        GlobalScope globalScope = variantContext.getScope().getGlobalScope();
        File proguardOut = new File(Joiner.on(File.separatorChar).join(
                String.valueOf(globalScope.getBuildDir()),
                FD_OUTPUTS,
                "mapping",
                variantContext.getScope().getVariantConfiguration().getDirName()));

        File printMapping = new File(proguardOut, "mapping.txt");

        return printMapping;
    }


    protected void wrapUpOutputs(File classes2Folder, File classes3Folder)
            throws IOException {

        // the transform can set the verifier status to failure in some corner cases, in that
        // case, make sure we delete our classes.3
//        if (!transformScope.getInstantRunBuildContext().hasPassedVerification()) {
//            FileUtils.cleanOutputDir(classes3Folder);
//            return;
//        }
        // otherwise, generate the patch file and add it to the list of files to process next.
        ImmutableList<String> generatedClassNames = generatedClasses3Names.build();
        if (!generatedClassNames.isEmpty()) {
            File patchClassInfo = new File(variantContext.getProject().getBuildDir(), "outputs/patchClassInfo.json");
            org.apache.commons.io.FileUtils.writeStringToFile(patchClassInfo, JSON.toJSONString(modifyClasses));
            modifyClasses.entrySet().forEach(stringStringEntry -> LOGGER.warning(stringStringEntry.getKey() + ":" + stringStringEntry.getValue()));
            writePatchFileContents(
                    generatedClassNames,
                    classes3Folder,
                    transformScope.getInstantRunBuildContext().getBuildId());
        }
    }


    private static void writePatchFileContents(
            @NonNull ImmutableList<String> patchFileContents, @NonNull File outputDir, long buildId) {

        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                TBIncrementalVisitor.ALI_APP_PATCHES_LOADER_IMPL, null,
                TBIncrementalVisitor.ALI_ABSTRACT_PATCHES_LOADER_IMPL, null);

        // Add the build ID to force the patch file to be repackaged.
        cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
                "BUILD_ID", "J", null, buildId);

        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    TBIncrementalVisitor.ALI_ABSTRACT_PATCHES_LOADER_IMPL,
                    "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                    "getPatchedClasses", "()[Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitIntInsn(Opcodes.BIPUSH, patchFileContents.size());
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
            for (int index = 0; index < patchFileContents.size(); index++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, index);
                mv.visitLdcInsn(patchFileContents.get(index));
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(4, 1);
            mv.visitEnd();
        }
        cw.visitEnd();

        byte[] classBytes = cw.toByteArray();
        File outputFile = new File(outputDir, TBIncrementalVisitor.ALI_APP_PATCHES_LOADER_IMPL + ".class");
        try {
            Files.createParentDirs(outputFile);
            Files.write(classBytes, outputFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public enum PatchPolicy {

        ADD, MODIFY, NONE
    }


}
