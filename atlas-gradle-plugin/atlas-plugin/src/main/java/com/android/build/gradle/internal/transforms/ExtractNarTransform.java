package com.android.build.gradle.internal.transforms;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import org.gradle.api.artifacts.transform.ArtifactTransform;

import static com.android.utils.FileUtils.mkdirs;

/**
 * @author qianfeng
 * @create 2019-07-13
 */

public class ExtractNarTransform extends ArtifactTransform {

    @Override
    public List<File> transform(File input) {
        File outputDir = getOutputDirectory();

        mkdirs(outputDir);

        try (InputStream fis = new BufferedInputStream(new FileInputStream(input));
             ZipInputStream zis = new ZipInputStream(fis)) {
            // loop on the entries of the intermediary package and put them in the final package.
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                try {
                    String name = entry.getName();

                    // do not take directories and non so file.
                    if (entry.isDirectory() || !name.endsWith(".so")) {
                        continue;
                    }

                    Path path = Paths.get(name);
                    String pathName = path.subpath(2, path.getNameCount()).toString();

                    File outputFile = new File(outputDir, pathName.replace('/', File.separatorChar));
                    mkdirs(outputFile.getParentFile());

                    try (OutputStream outputStream =
                                 new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        ByteStreams.copy(zis, outputStream);
                        outputStream.flush();
                    }
                } finally {
                    zis.closeEntry();
                }
            }

        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        return ImmutableList.of(outputDir);
    }
}
