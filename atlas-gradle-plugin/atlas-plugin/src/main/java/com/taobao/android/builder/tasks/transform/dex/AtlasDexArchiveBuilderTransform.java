package com.taobao.android.builder.tasks.transform.dex;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.build.VariantOutput;
import com.android.build.api.transform.Context;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.api.BaseVariantOutput;
import com.android.build.gradle.internal.ApkDataUtils;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.api.AppVariantContext;
import com.android.build.gradle.internal.api.AwbTransform;
import com.android.build.gradle.internal.pipeline.AtlasIntermediateStreamHelper;
import com.android.build.gradle.internal.pipeline.ExtendedContentType;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform;
import com.android.build.gradle.tasks.PackageAndroidArtifact;
import com.android.builder.core.DefaultDexOptions;
import com.android.builder.core.DexOptions;
import com.android.builder.core.ErrorReporter;
import com.android.builder.dexing.ClassFileInput;
import com.android.builder.dexing.ClassFileInputs;
import com.android.builder.dexing.DexArchiveBuilder;
import com.android.builder.dexing.DexArchiveBuilderConfig;
import com.android.builder.dexing.DexArchiveBuilderException;
import com.android.builder.dexing.DexerTool;
import com.android.builder.utils.FileCache;
import com.android.dx.command.dexer.DxContext;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.ParsingProcessOutputHandler;
import com.android.ide.common.blame.parser.DexParser;
import com.android.ide.common.blame.parser.ToolOutputParser;
import com.android.ide.common.internal.WaitableExecutor;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessOutput;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.tools.r8.AtlasD8DexArchiveBuilder;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.taobao.android.builder.AtlasBuildContext;
import com.taobao.android.builder.dependency.model.AwbBundle;
import com.taobao.android.builder.tools.Profiler;
import com.taobao.android.builder.tools.TransformInputUtils;
import org.gradle.workers.IsolationMode;

/**
 * @author lilong
 * @create 2017-12-08 上午3:43
 */

public class AtlasDexArchiveBuilderTransform extends Transform {
    private static final LoggerWrapper logger =
            LoggerWrapper.getLogger(DexArchiveBuilderTransform.class);

    public static final int DEFAULT_BUFFER_SIZE_IN_KB = 100;

    public static int NUMBER_OF_BUCKETS = 10;

    private static final String CACHE_ID = "dex-archive";

    private static final String CACHE_VERSION="1.0";


    @NonNull
    private final DexOptions dexOptions;

    @NonNull
    private final ErrorReporter errorReporter;
    @VisibleForTesting
    @NonNull
    final WaitableExecutor executor;

    Multimap<QualifiedContent, File> cacheItems = HashMultimap.create();

    private final int minSdkVersion;
    @NonNull
    private final DexerTool dexer;
    @NonNull
    private final AtlasDexArchiveBuilderCacheHander cacheHandler;
    private final boolean useGradleWorkers;
    private final int inBufferSize;
    private final int outBufferSize;
    private final boolean isDebuggable;
    private final AppVariantContext variantContext;
    private final BaseVariantOutput variantOutput;
    private TransformTask transformTask;

    public AtlasDexArchiveBuilderTransform(AppVariantContext variantContext, VariantOutput variantOutput,
                                           @NonNull DexOptions dexOptions,
                                           @NonNull ErrorReporter errorReporter,
                                           @Nullable FileCache userLevelCache,
                                           int minSdkVersion,
                                           @NonNull DexerTool dexer,
                                           boolean useGradleWorkers,
                                           @Nullable Integer inBufferSize,
                                           @Nullable Integer outBufferSize,
                                           boolean isDebuggable) {

        this.variantContext = variantContext;
        this.variantOutput = (BaseVariantOutput) variantOutput;
        this.dexOptions = dexOptions;
        this.errorReporter = errorReporter;
        this.minSdkVersion = minSdkVersion;
        this.dexer = dexer;
        this.executor = WaitableExecutor.useGlobalSharedThreadPool();
        this.cacheHandler =
                new AtlasDexArchiveBuilderCacheHander(variantContext.getProject(),
                        userLevelCache, dexOptions, minSdkVersion, isDebuggable, dexer);
        this.useGradleWorkers = useGradleWorkers;
        this.inBufferSize =
                (inBufferSize == null ? DEFAULT_BUFFER_SIZE_IN_KB : inBufferSize) * 1024;
        this.outBufferSize =
                (outBufferSize == null ? DEFAULT_BUFFER_SIZE_IN_KB : outBufferSize) * 1024;
        this.isDebuggable = variantContext.getBuildType().isDebuggable();

    }

    @NonNull
    @Override
    public String getName() {
        return "dexBuilder";
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
         return ImmutableSet.of(
                QualifiedContent.DefaultContentType.CLASSES,
                ExtendedContentType.CLASSES_ENHANCED);
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getOutputTypes() {
        return ImmutableSet.of(ExtendedContentType.DEX_ARCHIVE);
    }

    @NonNull
    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_WITH_IR_FOR_DEXING;
    }

    @NonNull
    @Override
    public Map<String, Object> getParameterInputs() {
        try {
            Map<String, Object> params = new LinkedHashMap<>(4);
            params.put("optimize", !dexOptions.getAdditionalParameters().contains("--no-optimize"));
            params.put("jumbo", dexOptions.getJumboMode());
            params.put("min-sdk-version", minSdkVersion);
            params.put("dex-builder-tool", dexer.name());

            return params;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void transform(@NonNull TransformInvocation transformInvocation)
        throws IOException, InterruptedException, TransformException {

        AtlasIntermediateStreamHelper atlasIntermediateStreamHelper = new AtlasIntermediateStreamHelper(transformTask);
        atlasIntermediateStreamHelper.replaceProvider(transformInvocation);
        AtlasBuildContext.status = AtlasBuildContext.STATUS.DEXARCHIVE;
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        Preconditions.checkNotNull(outputProvider, "Missing output provider.");
        outputProvider.deleteAll();
        org.apache.commons.io.FileUtils.cleanDirectory(variantContext.getAwbDexAchiveOutputs());


        if (dexer == DexerTool.D8) {
            logger.info("D8 is used to build dex.");
        }

        if (dexOptions.getAdditionalParameters().contains("--no-optimize")) {
            logger.warning(DefaultDexOptions.OPTIMIZE_WARNING);
        }

        logger.verbose("Task is incremental : %b ", transformInvocation.isIncremental());
//
//        if (!transformInvocation.isIncremental()) {
//        }

        Profiler.start("DexArchiveBuilder Transform");

        Profiler.enter("handle maindex classes");
        for (TransformInput input : transformInvocation.getInputs()) {
            for (DirectoryInput dirInput : input.getDirectoryInputs()) {
                logger.verbose("Dir input %s", dirInput.getFile().toString());
                List<File>dirFiles = null;
                try {
                    dirFiles = convertToDexArchive(
                        transformInvocation.getContext(),
                        dirInput,
                        outputProvider,
                        false,
                        NUMBER_OF_BUCKETS);
                } catch (Exception e) {
                    throw new TransformException(e.getCause());
                }
                cacheItems.putAll(dirInput,dirFiles);
            }

            for (JarInput jarInput : input.getJarInputs()) {
                if (jarInput.getFile().getName().equals(PackageAndroidArtifact.INSTANT_RUN_PACKAGES_PREFIX + ".jar")){
                    logger.warning("skip instant run jar:"+jarInput.getFile().getAbsolutePath());
                    continue;
                }

                if (!validJar(jarInput)){
                    continue;
                }

                logger.verbose("Jar input %s", jarInput.getFile().toString());
                List<File> dexArchives = null;
                try {
                    dexArchives = convertJarToDexArchive(transformInvocation.getContext(), jarInput, outputProvider);
                } catch (Exception e) {
                    throw new TransformException(e.getCause());
                }
                cacheItems.putAll(jarInput,dexArchives);
            }
        }
        Profiler.release();


        Profiler.enter("handle awbs classes");
        try {
            processAwbDexArchive(transformInvocation);
        } catch (Exception e) {
            throw new TransformException(e.getCause());
        }
        Profiler.release();

        Profiler.enter("wait task finish");
        // all work items have been submitted, now wait for completion.
        executor.waitForTasksWithQuickFail(false);
        Profiler.release();


        //Profiler.enter("populate cache");
        //if (!cacheItems.isEmpty()) {
        //    try {
        //        cacheHandler.populateCache(cacheItems);
        //    } catch (Exception e) {
        //        throw new TransformException(e.getCause());
        //    }
        //}
        //Profiler.release();

        if (variantContext.getScope().getMainDexListFile().exists()){
            variantContext.getScope().getMainDexListFile().delete();
        }


        Profiler.release();
        logger.warning(Profiler.dump());

    }

    private boolean validJar(JarInput jarInput) {
        if (computerClassCount(jarInput.getFile()) == 0){
            return false;
        }else {
            return true;
        }
    }

    private boolean inMainDex(JarInput jarInput) {

        if (jarInput.getFile().getName().contains(PackageAndroidArtifact.INSTANT_RUN_PACKAGES_PREFIX + "-bootstrap.jar")){
            return true;
        }
        boolean flag = AtlasBuildContext.atlasMainDexHelperMap.get(variantContext.getVariantName()).inMainDex(jarInput);
        return flag;
    }


    private List<File> processJarInput(
            @NonNull Context context,
            boolean isIncremental,
            @NonNull JarInput jarInput,
            TransformOutputProvider transformOutputProvider)
            throws Exception {
                return convertJarToDexArchive(context, jarInput, transformOutputProvider);
    }

    private List<File> processAwbJarInput(
            @NonNull Context context,
            boolean isIncremental,
            @NonNull JarInput jarInput,
            File transformOutputProvider)
            throws Exception {

        return convertAwbJarToDexArchive(context, jarInput, transformOutputProvider);

    }

    private List<File> convertJarToDexArchive(
            @NonNull Context context,
            @NonNull JarInput toConvert,
            @NonNull TransformOutputProvider transformOutputProvider)
            throws Exception {

        File cachedVersion = cacheHandler.getCachedVersionIfPresent(toConvert);
        if (cachedVersion == null) {
            logger.info("AtlasDexArchiveBuilder miss cache:"+toConvert.getFile().getAbsolutePath()+"-> null");
            return convertToDexArchive(context, toConvert, transformOutputProvider, false, 1);
        } else {
            File outputFile = getPreDexJar(transformOutputProvider, toConvert, null);
            logger.info("AtlasDexArchiveBuilder hit cache:"+toConvert.getFile().getAbsolutePath()+"->"+outputFile.getAbsolutePath());
            if (!outputFile.getParentFile().exists()){
                outputFile.getParentFile().mkdirs();
            }
            Files.copy(
                    cachedVersion.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            // no need to try to cache an already cached version.
            return ImmutableList.of();
        }
    }

    private List<File> convertAwbJarToDexArchive(
            @NonNull Context context,
            @NonNull JarInput toConvert,
            @NonNull File transformOutputProvider)
            throws Exception {

        File cachedVersion = cacheHandler.getCachedVersionIfPresent(toConvert);
        if (cachedVersion == null) {
            logger.info("AtlasDexArchiveBuilder miss cache:"+toConvert.getFile().getAbsolutePath()+"-> null");

            return convertAwbToDexArchive(context, toConvert, transformOutputProvider, false,true);
        } else {
            logger.info("AtlasDexArchiveBuilder hit cache:"+toConvert.getFile().getAbsolutePath()+"->"+cachedVersion.getAbsolutePath());
            File outputFile = getAwbPreDexJar(transformOutputProvider, toConvert, null);

            FileUtils.copyFile(cachedVersion,outputFile);

            // no need to try to cache an already cached version.
            return ImmutableList.of();
        }
    }

    public void setTransformTask(TransformTask transformTask) {
        this.transformTask = transformTask;
    }

    public static class DexConversionParameters implements Serializable {
        private final QualifiedContent input;
        private final String output;
        private final int numberOfBuckets;
        private final int buckedId;
        private final int minSdkVersion;
        private final List<String> dexAdditionalParameters;
        private final int inBufferSize;
        private final int outBufferSize;
        private final DexerTool dexer;
        private final boolean isDebuggable;
        private final boolean isIncremental;
        private final boolean awb;

        public DexConversionParameters(
                QualifiedContent input,
                File output,
                int numberOfBuckets,
                int buckedId,
                int minSdkVersion,
                List<String> dexAdditionalParameters,
                int inBufferSize,
                int outBufferSize,
                DexerTool dexer,
                boolean isDebuggable,
                boolean isIncremental,
                boolean awb) {
            this.input = input;
            this.numberOfBuckets = numberOfBuckets;
            this.buckedId = buckedId;
            this.output = output.toURI().toString();
            this.minSdkVersion = minSdkVersion;
            this.dexAdditionalParameters = dexAdditionalParameters;
            this.inBufferSize = inBufferSize;
            this.outBufferSize = outBufferSize;
            this.dexer = dexer;
            this.isDebuggable = isDebuggable;
            this.isIncremental = isIncremental;
            this.awb = awb;
        }

        public boolean belongsToThisBucket(String path) {
            return (Math.abs(path.hashCode()) % numberOfBuckets) == buckedId;
        }

        public boolean isDirectoryBased() {
            return input instanceof DirectoryInput;
        }
    }


    private DexArchiveBuilder getDexArchiveBuilder(
            int minSdkVersion,
            List<String> dexAdditionalParameters,
            int inBufferSize,
            int outBufferSize,
            DexerTool dexer,
            boolean isDebuggable,
            boolean awb,
            OutputStream outStream,
            OutputStream errStream)
            throws IOException {

        DexArchiveBuilder dexArchiveBuilder;
        switch (dexer) {
            case DX:
                boolean optimizedDex = !dexAdditionalParameters.contains("--no-optimize");
                DxContext dxContext = new DxContext(outStream, errStream);
                DexArchiveBuilderConfig config =
                        new DexArchiveBuilderConfig(
                                dxContext,
                                optimizedDex,
                                inBufferSize,
                                minSdkVersion,
                                DexerTool.DX,
                                outBufferSize,
                                AtlasDexArchiveBuilderCacheHander.isJumboModeEnabledForDx());

                dexArchiveBuilder = DexArchiveBuilder.createDxDexBuilder(config);
                break;
            case D8:
                dexArchiveBuilder =
                        new AtlasD8DexArchiveBuilder(minSdkVersion,isDebuggable,AtlasDexArchiveBuilderTransform.this.variantContext.getScope().getMainDexListFile().toPath(),awb);
                break;
            default:
                throw new AssertionError("Unknown dexer type: " + dexer.name());
        }
        return dexArchiveBuilder;
    }

    private List<File> convertToDexArchive(
            @NonNull Context context,
            @NonNull QualifiedContent input,
            @NonNull TransformOutputProvider outputProvider,
            boolean isIncremental,
            int bucketNumber)
            throws Exception {
        long start = System.currentTimeMillis();
        logger.verbose("Dexing {}", input.getFile().getAbsolutePath());
        ImmutableList.Builder<File> dexArchives = ImmutableList.builder();
        for (int bucketId = 0; bucketId < bucketNumber; bucketId++) {
            File preDexOutputFile = getPreDexFile(outputProvider, input, bucketId);
            //if (input.getFile().isDirectory()) {
            //    File cachedVersion = cacheHandler.getCachedVersionIfPresent(input.getFile());
            //    dexArchives.add(preDexOutputFile);
            //    if (cachedVersion != null) {
            //        FileUtils.copyDirectoryContentToDirectory(cachedVersion, preDexOutputFile);
            //        return dexArchives.build();
            //
            //    }
            //}
            if (preDexOutputFile.isDirectory() && preDexOutputFile.exists()) {
                FileUtils.cleanOutputDir(preDexOutputFile);
            }else {
                FileUtils.deleteIfExists(preDexOutputFile);
            }
            AtlasDexArchiveBuilderTransform.DexConversionParameters parameters =
                    new AtlasDexArchiveBuilderTransform.DexConversionParameters(
                            input,
                            preDexOutputFile,
                            bucketNumber,
                            bucketId,
                            minSdkVersion,
                            dexOptions.getAdditionalParameters(),
                            inBufferSize,
                            outBufferSize,
                            dexer,
                            isDebuggable,
                            isIncremental,
                            false);

            if (useGradleWorkers) {
                context.getWorkerExecutor()
                        .submit(
                                DexArchiveBuilderTransform.DexConversionWorkAction.class,
                                configuration -> {
                                    configuration.setIsolationMode(IsolationMode.NONE);
                                    configuration.setParams(parameters);
                                });
            } else {
                executor.execute(
                        () -> {
                            long startConvert = System.currentTimeMillis();
                            ProcessOutputHandler outputHandler =
                                    new ParsingProcessOutputHandler(
                                            new ToolOutputParser(
                                                    new DexParser(), Message.Kind.ERROR, logger),
                                            new ToolOutputParser(new DexParser(), logger),
                                            errorReporter);
                            ProcessOutput output = null;
                            try (Closeable ignored = output = outputHandler.createOutput()) {
                                launchProcessing(
                                        parameters,
                                        output.getStandardOutput(),
                                        output.getErrorOutput());
                            } finally {
                                if (output != null) {
                                    try {
                                        outputHandler.handleOutput(output);
                                    } catch (ProcessException e) {
                                        // ignore this one
                                    }
                                }
                            }
                            long endConvert = System.currentTimeMillis();
                            logger.warning("dex archive time consuming main " + preDexOutputFile.getPath() + " " + String.valueOf(endConvert - startConvert));
                            return null;
                        });
            }
        }
        long end = System.currentTimeMillis();
        logger.warning("dex archive time consuming " + input.getFile().getPath() + " " + String.valueOf(end - start));
        return dexArchives.build();
    }


    private void launchProcessing(
            @NonNull AtlasDexArchiveBuilderTransform.DexConversionParameters dexConversionParameters,
            @NonNull OutputStream outStream,
            @NonNull OutputStream errStream)
            throws IOException, URISyntaxException {
        DexArchiveBuilder dexArchiveBuilder =
                getDexArchiveBuilder(
                        dexConversionParameters.minSdkVersion,
                        dexConversionParameters.dexAdditionalParameters,
                        dexConversionParameters.inBufferSize,
                        dexConversionParameters.outBufferSize,
                        dexConversionParameters.dexer,
                        dexConversionParameters.isDebuggable,
                        dexConversionParameters.awb,
                        outStream,
                        errStream);

        Path inputPath = dexConversionParameters.input.getFile().toPath();
        Predicate<String> bucketFilter = dexConversionParameters::belongsToThisBucket;

        //boolean hasIncrementalInfo =
        //        dexConversionParameters.isDirectoryBased() && dexConversionParameters.isIncremental;
        //Predicate<String> toProcess =
        //        hasIncrementalInfo
        //                ? path -> {
        //            Map<File, Status> changedFiles =
        //                    ((DirectoryInput) dexConversionParameters.input)
        //                            .getChangedFiles();
        //
        //            File resolved = inputPath.resolve(path).toFile();
        //            Status status = changedFiles.get(resolved);
        //            return status == Status.ADDED || status == Status.CHANGED;
        //        }
        //                : path -> true;
        //
        //bucketFilter = bucketFilter.and(toProcess);

        try (ClassFileInput input = ClassFileInputs.fromPath(inputPath)) {
            dexArchiveBuilder.convert(
                    input.entries(bucketFilter),
                    Paths.get(new URI(dexConversionParameters.output)),
                    dexConversionParameters.isDirectoryBased());
        } catch (DexArchiveBuilderException ex) {
            throw new DexArchiveBuilderException("Failed to process " + inputPath.toString(), ex);
        }
    }

    @NonNull
    private static File getPreDexFile(
            @NonNull TransformOutputProvider output,
            @NonNull QualifiedContent qualifiedContent,
            int bucketId) {

        return qualifiedContent.getFile().isDirectory()
                ? getPreDexFolder(output, (DirectoryInput) qualifiedContent)
                : getPreDexJar(output, (JarInput) qualifiedContent, bucketId);
    }

    @NonNull
    private static File getPreDexJar(
            @NonNull TransformOutputProvider output,
            @NonNull JarInput qualifiedContent,
            @Nullable Integer bucketId) {

        return output.getContentLocation(
                qualifiedContent.getName().replace(":","-") + (bucketId == null ? "" : ("-" + bucketId)),
                ImmutableSet.of(ExtendedContentType.DEX_ARCHIVE),
                qualifiedContent.getScopes(),
                Format.JAR);
    }

    @NonNull
    private static File getPreDexFolder(
            @NonNull TransformOutputProvider output, @NonNull DirectoryInput directoryInput) {

        return FileUtils.mkdirs(
                output.getContentLocation(
                        directoryInput.getName(),
                        ImmutableSet.of(ExtendedContentType.DEX_ARCHIVE),
                        directoryInput.getScopes(),
                        Format.DIRECTORY));
    }


    private List<File> convertAwbToDexArchive(
            @NonNull Context context,
            @NonNull QualifiedContent input,
            @NonNull File outputProvider,
            boolean isIncremental,
            boolean awb)
            throws Exception {
        int count = 0;
        if (input.getFile().isFile()) {
             count = computerClassCount(input.getFile());
           
        }else if (input.getFile().isDirectory()){
            count = NUMBER_OF_BUCKETS / 2;
        }
        logger.verbose("Dexing {}", input.getFile().getAbsolutePath());

        ImmutableList.Builder<File> dexArchives = ImmutableList.builder();

        for (int bucketId = 0; bucketId < count; bucketId++) {
            File preDexOutputFile = getAwbPreDexFile(outputProvider, input, bucketId);
            dexArchives.add(preDexOutputFile);

            //if (input.getFile().isDirectory()) {
            //    File cachedVersion = cacheHandler.getCachedVersionIfPresent(input.getFile());
            //    if (cachedVersion != null) {
            //        FileUtils.copyDirectoryContentToDirectory(cachedVersion, preDexOutputFile);
            //        return dexArchives.build();
            //    }
            //}

            if (preDexOutputFile.isDirectory() && preDexOutputFile.exists()) {
                FileUtils.cleanOutputDir(preDexOutputFile);
            }else {
                FileUtils.deleteIfExists(preDexOutputFile);
            }
            AtlasDexArchiveBuilderTransform.DexConversionParameters parameters =
                new AtlasDexArchiveBuilderTransform.DexConversionParameters(
                    input,
                    preDexOutputFile,
                    count,
                    bucketId,
                    minSdkVersion,
                    dexOptions.getAdditionalParameters(),
                    inBufferSize,
                    outBufferSize,
                    dexer,
                    isDebuggable,
                    false,
                    awb);
            executor.execute(
                () -> {
                    long startConvert = System.currentTimeMillis();
                    ProcessOutputHandler outputHandler =
                        new ParsingProcessOutputHandler(
                            new ToolOutputParser(
                                new DexParser(), Message.Kind.ERROR, logger),
                            new ToolOutputParser(new DexParser(), logger),
                            errorReporter);
                    ProcessOutput output = null;
                    try (Closeable ignored = output = outputHandler.createOutput()) {
                        launchProcessing(
                            parameters,
                            output.getStandardOutput(),
                            output.getErrorOutput());
                    } finally {
                        if (output != null) {
                            try {
                                outputHandler.handleOutput(output);
                            } catch (ProcessException e) {
                                // ignore this one
                            }
                        }
                    }
                    long endConvert = System.currentTimeMillis();
                    logger.warning("dex archive time consuming awb" + preDexOutputFile.getPath() + " " + String.valueOf(endConvert - startConvert));
                    return null;
                });
        }

        return dexArchives.build();
    }


    private void processAwbDexArchive(TransformInvocation transformInvocation) throws Exception {
        Collection<AwbTransform> awbTransforms = this.variantContext
            .getAppVariantOutputContext((ApkDataUtils.get(variantOutput)))
            .getAwbTransformMap().values();

        for (AwbTransform awbTransform : awbTransforms) {
            AwbBundle awbBundle = awbTransform.getAwbBundle();

            for (File dir : awbTransform.getInputDirs()) {
                logger.info(awbBundle.getName() + ":" + dir.getAbsolutePath());
                DirectoryInput directoryInput = TransformInputUtils.makeDirectoryInput(dir, variantContext);
                List<File> awbFiles = convertAwbToDexArchive(transformInvocation.getContext(), directoryInput,
                    variantContext.getAwbDexAchiveOutput(awbBundle), transformInvocation.isIncremental(), true);
                cacheItems.putAll(directoryInput, awbFiles);
                //this.executor.execute(new Callable<Void>() {
                //    @Override
                //    public Void call() throws Exception {
                //        List<File> awbFiles = convertAwbToDexArchive(transformInvocation.getContext(), directoryInput,
                //            variantContext.getAwbDexAchiveOutput(awbBundle), transformInvocation.isIncremental(), true);
                //        cacheItems.putAll(directoryInput, awbFiles);
                //        return null;
                //    }
                //});
            }

            for (File jar : awbTransform.getInputFiles()) {
                logger.info(awbBundle.getName() + ":" + jar.getAbsolutePath());
                JarInput jarInput = TransformInputUtils.makeJarInput(jar, variantContext);
                List<File> jarFiles = processAwbJarInput(transformInvocation.getContext(),
                    transformInvocation.isIncremental(), jarInput, variantContext.getAwbDexAchiveOutput(awbBundle));
                cacheItems.putAll(jarInput, jarFiles);
            }

            for (File jar : awbTransform.getInputLibraries()) {
                logger.info(awbBundle.getName() + ":" + jar.getAbsolutePath());
                JarInput jarInput = TransformInputUtils.makeJarInput(jar, variantContext);
                List<File> jarFiles = processAwbJarInput(transformInvocation.getContext(),
                    transformInvocation.isIncremental(), jarInput, variantContext.getAwbDexAchiveOutput(awbBundle));
                cacheItems.putAll(jarInput, jarFiles);
            }
        }
    }

    private File getAwbPreDexFile(File outputProvider, QualifiedContent qualifiedContent, Integer bucketId) {
        return qualifiedContent.getFile().isDirectory()
                ? getAwbPreDexFolder(outputProvider, (DirectoryInput) qualifiedContent, bucketId)
                : getAwbPreDexJar(outputProvider, (JarInput) qualifiedContent, bucketId);
    }

      Map<String,Integer> dexcount = new HashMap<>();


    private File getAwbPreDexJar(
            @NonNull File output,
            @NonNull JarInput qualifiedContent,
            @Nullable Integer bucketId) {
        if (!output.exists()){
            output.mkdirs();
        }
        if (bucketId == null){
            return new File(output, qualifiedContent.getName().replace(":","-") + (bucketId == null ? "" : ("-" + bucketId)) + ".jar");
        }
//        synchronized (object) {
            if (bucketId > 5) {
                bucketId = dexcount.get(qualifiedContent.getName());
            }
            dexcount.put(qualifiedContent.getName(), bucketId++);
//        }

        return new File(output, qualifiedContent.getName().replace(":","-") + (bucketId == null ? "" : ("-" + bucketId)) + ".jar");

    }

    @NonNull
    private static File getAwbPreDexFolder(
            @NonNull File output, @NonNull DirectoryInput directoryInput, int bucketId) {
        return FileUtils.mkdirs(new File(output, directoryInput.getName() + "-" + bucketId));
    }




    private int computerClassCount(File file){
        JarFile jarFile = null;
        int count = 0;
        try {
            jarFile = new JarFile(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Enumeration enumeration= jarFile.entries();
        while (enumeration.hasMoreElements()){
            JarEntry jarEntry = (JarEntry) enumeration.nextElement();
            if (jarEntry.getName().endsWith(".class")){
                count ++;
            }
            if (count > 1){
                return 1;
            }
        }
       return count;
    }

}
