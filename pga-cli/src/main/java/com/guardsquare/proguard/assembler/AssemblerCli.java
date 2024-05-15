/*
 * ProGuard assembler/disassembler for Java bytecode.
 *
 * Copyright (c) 2019-2020 Guardsquare NV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.guardsquare.proguard.assembler;

import com.guardsquare.proguard.disassembler.io.JbcDataEntryWriter;
import com.guardsquare.proguard.assembler.io.JbcReader;
import proguard.classfile.*;
import proguard.classfile.attribute.visitor.AllAttributeVisitor;
import proguard.classfile.editor.ConstantPoolSorter;
import proguard.classfile.util.*;
import proguard.classfile.visitor.*;
import proguard.io.*;
import proguard.preverify.CodePreverifier;
import proguard.util.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main class for the ProGuard Disassembler and Assembler.
 *
 * @author Joachim Vandersmissen
 */
public class AssemblerCli
{
    private static final byte[] JMOD_HEADER            = new byte[] { 'J', 'M', 1, 0 };
    private static final String JMOD_CLASS_FILE_PREFIX = "classes/";
    
    private static String mainClassSaved = null;
    private static String startClassSaved = null;

    private static class MutableObject<T> {
        T v;
        MutableObject(T v) { this.v = v; }
        static <S> MutableObject<S> of(S v) { return new MutableObject<S>(v); }
    }


    public static void main(String[] args) throws IOException
    {
        Runnable printUsageAndExit = () -> {
            System.out.println("ProGuard Assembler: assembles and disassembles Java class files.");
            System.out.println("Usage: (Options must come first)");
            System.out.println("  java proguard.Assembler [--force-unpack-classes-to-root] [--unpack-jars-in-jar] [<classpath>] <input> <output>");
            System.out.println("The input and the output can be .class/.jbc/.jar/.jmod files or directories,");
            System.out.println("where .jbc files contain disassembled Java bytecode.");
            System.out.println("The classpath (with runtime classes and library classes) is only necessary for preverifying assembled code.");
            System.exit(1);
        };

        if (args.length < 2)
        {
            printUsageAndExit.run();
        }
        
        MutableObject<Boolean> forceUnpackClassesToRootW = MutableObject.of(false);
        MutableObject<Boolean> unpackJarsInJarW = MutableObject.of(false);
        MutableObject<String[]> remainingArgs = MutableObject.of(args);
        
        Runnable shift = () -> {
            remainingArgs.v = Arrays.copyOfRange(remainingArgs.v, 1, remainingArgs.v.length);
        };

        Runnable parseOneOpt = () -> {
            if (remainingArgs.v.length == 0) {
                throw new IllegalArgumentException();
            }
            
            if ("--force-unpack-classes-to-root".equals(remainingArgs.v[0])) {
                shift.run();
                forceUnpackClassesToRootW.v = true;
            } else if ("--unpack-jars-in-jar".equals(remainingArgs.v[0])) {
                shift.run();
                unpackJarsInJarW.v = true;
            } else {
                throw new IllegalArgumentException();
            }
        };

        while (true) {
            try {
                parseOneOpt.run();
            } catch (IllegalArgumentException e) {
                break;
            }
        }

        if (remainingArgs.v.length < 2) {
            printUsageAndExit.run();
        }
        if (remainingArgs.v.length > 3) {
            printUsageAndExit.run();
        }

        int index = 0;
        String[] libraryPath    = remainingArgs.v.length >= 3 ?
                                  remainingArgs.v[index++].split(System.getProperty("path.separator")) : null;
        String   inputFileName  = remainingArgs.v[index++];
        String   outputFileName = remainingArgs.v[index++];

        ClassPool libraryClassPool = new ClassPool();
        ClassPool programClassPool = new ClassPool();

        // Read the libraries.
        if (libraryPath != null)
        {
            readLibraries(libraryPath, libraryClassPool);
        }

        // Read the actual input.
        DataEntrySource inputSource =
            source(inputFileName);

        System.out.println("Reading input file ["+inputFileName+"]...");

        readInput(inputSource,
                  libraryClassPool,
                  programClassPool,
                  forceUnpackClassesToRootW.v,
                  unpackJarsInJarW.v);

        // Preverify the program class pool.
        if (libraryPath != null && programClassPool.size() > 0)
        {
            System.out.println("Preverifying assembled class files...");

            preverify(libraryClassPool,
                      programClassPool);
        }

        // Clean up the program classes.
        programClassPool.classesAccept(
            new ConstantPoolSorter());

        // Write out the output.
        System.out.println("Writing output file ["+outputFileName+"]...");

        writeOutput(inputSource,
                    outputFileName,
                    libraryClassPool,
                    programClassPool,
                    forceUnpackClassesToRootW.v,
                    unpackJarsInJarW.v);
    }


    /**
     * Reads the specified libraries as library classes in the given class pool.
     */
    private static void readLibraries(String[]  libraryPath,
                                      ClassPool libraryClassPool)
    throws IOException
    {
        for (String libraryFileName : libraryPath)
        {
            System.out.println("Reading library file ["+libraryFileName+"]...");

            // Any libraries go into the library class pool.
            DataEntryReader libraryClassReader =
                new ClassReader(true, false, false, false, null,
                new ClassPoolFiller(libraryClassPool));

            DataEntrySource librarySource =
                source(libraryFileName);

            librarySource.pumpDataEntries(reader(libraryClassReader,
                                                 null,
                                                 null,
                                                 true,
                                                 false,
                                                 false));
        }
    }


    /**
     * Reads the specified input as program classes (for .jbc files) and
     * library classes (for .class files) in the given class pools.
     */
    private static void readInput(DataEntrySource inputSource,
                                  ClassPool       libraryClassPool,
                                  ClassPool       programClassPool,
                                  boolean forceUnpackClassesToRoot,
                                  boolean unpackJarsInJar)
    throws IOException
    {
        // Any class files go into the library class pool.
        DataEntryReader classReader =
            new ClassReader(false, false, false, false, new WarningLogger(LogManager.getLogger(AssemblerCli.class)),
            new ClassPoolFiller(libraryClassPool));

        // Any jbc files go into the program class pool.
        DataEntryReader jbcReader =
            new JbcReader(
            new ClassPoolFiller(programClassPool));

        DataEntryReader reader =
            reader(classReader,
                   jbcReader,
                    new DataEntryReader() {
                        @Override
                        public void read(DataEntry dataEntry) throws IOException {
                            if ("META-INF/MANIFEST.MF".equals(dataEntry.getName())) {
                                BufferedReader br = new BufferedReader(new InputStreamReader(dataEntry.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                                br
                                    .lines()
                                    .forEach(line -> {
                                        {
                                            String theMainClass = null;
                                            if (line.startsWith("Main-Class: ")) {
                                                theMainClass = line.substring("Main-Class: ".length());
                                            } else if (line.startsWith("Main-Class:")) {
                                                theMainClass = line.substring("Main-Class:".length());
                                            }

                                            if (theMainClass != null) {
                                                System.out.println("Saved Main-Class: " + theMainClass);
                                                System.out.flush();
                                                mainClassSaved = theMainClass;
                                            }
                                        }

                                        {
                                            String theStartClass = null;

                                            if (line.startsWith("Start-Class: ")) {
                                                theStartClass = line.substring("Start-Class: ".length());
                                            } else if (line.startsWith("Start-Class:")) {
                                                theStartClass = line.substring("Start-Class:".length());
                                            }

                                            if (theStartClass != null) {
                                                System.out.println("Saved Start-Class: " + theStartClass);
                                                System.out.flush();
                                                startClassSaved = theStartClass;
                                            }
                                        }
                                    });
                                // Another fix for incorrect inputStream acquirement in ZipDataEntry
                                if (!(dataEntry instanceof ZipDataEntry)) {
                                    br.close();
                                }
                            }
                        }
                    },
                    true,
                    forceUnpackClassesToRoot,
                    unpackJarsInJar);

        inputSource.pumpDataEntries(reader);
    }


    /**
     * Preverifies the program classes in the given class pools.
     */
    private static void preverify(ClassPool libraryClassPool,
                                  ClassPool programClassPool)
    {
        programClassPool.classesAccept(new ClassReferenceInitializer(programClassPool, libraryClassPool));
        programClassPool.classesAccept(new ClassSuperHierarchyInitializer(programClassPool, libraryClassPool));
        libraryClassPool.classesAccept(new ClassReferenceInitializer(programClassPool, libraryClassPool));
        libraryClassPool.classesAccept(new ClassSuperHierarchyInitializer(programClassPool, libraryClassPool));

        programClassPool.classesAccept(
            new ClassVersionFilter(VersionConstants.CLASS_VERSION_1_6,
                                   new AllMethodVisitor(
            new AllAttributeVisitor(
            new CodePreverifier(false)))));
    }

    /**
     * Rename only once to spoof JbcDataEntryWriter & ClassDataEntryWriter,
     *   guiding them to the correct class fully qualified name,
     *   but still writing to the original (prefixed) output path.
     */
    private static class RenamingOnceSpringBootClassPrefixDataEntry implements DataEntry {

        private DataEntry delegated;

        private AtomicBoolean everReplaced = new AtomicBoolean(false);

        RenamingOnceSpringBootClassPrefixDataEntry(DataEntry delegated) { this.delegated = delegated; }

        @Override
        public String getName() {
            boolean everReplacedSafe = everReplaced.getAndSet(true);
            if (everReplacedSafe) {
                return delegated.getName();
            } else {
                String n = delegated.getName();
                if (n.startsWith("BOOT-INF/classes/")) {
                    n = n.substring("BOOT-INF/classes/".length());
                }
                return n;
            }
        }

        @Override
        public String getOriginalName() {
            return delegated.getOriginalName();
        }

        @Override
        public long getSize() {
            return delegated.getSize();
        }

        @Override
        public boolean isDirectory() {
            return delegated.isDirectory();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegated.getInputStream();
        }

        @Override
        public void closeInputStream() throws IOException {
            delegated.closeInputStream();
        }

        @Override
        public DataEntry getParent() {
            return delegated.getParent();
        }
        
    }

    /**
     * Reads the specified input, replacing .class files by .jbc files and
     * vice versa, and writing them to the specified output.
     */
    private static void writeOutput(DataEntrySource inputSource,
                                    String          outputFileName,
                                    ClassPool       libraryClassPool,
                                    ClassPool       programClassPool,
                                    boolean forceUnpackClassesToRoot,
                                    boolean unpackJarsInJar)
    throws IOException
    {
        DataEntryWriter writer = writer(outputFileName);
        boolean isOutputJar   = outputFileName.endsWith(".jar");
        boolean isOutputJmod  = outputFileName.endsWith(".jmod");

        // Write out class files as jbc files.
        DataEntryWriter classAsJbcWriter =
            new RenamedDataEntryWriter(new ConcatenatingStringFunction(
                                       new SuffixRemovingStringFunction(".class"),
                                       new ConstantStringFunction(".jbc")),
            forceUnpackClassesToRoot ? new JbcDataEntryWriter(libraryClassPool, writer) : new JbcDataEntryWriter(libraryClassPool, writer) {
                @Override
                public OutputStream createOutputStream(DataEntry dataEntry) throws IOException
                {
                    return super.createOutputStream(new RenamingOnceSpringBootClassPrefixDataEntry(dataEntry));
                }
            });

        // Write out jbc files as class files.
        DataEntryWriter jbcAsClassWriter =
            new RenamedDataEntryWriter(new ConcatenatingStringFunction(
                                       new SuffixRemovingStringFunction(".jbc"),
                                       new ConstantStringFunction(".class")),
            forceUnpackClassesToRoot ? new ClassDataEntryWriter(programClassPool, writer) : new ClassDataEntryWriter(programClassPool, writer) {
                @Override
                public OutputStream createOutputStream(DataEntry dataEntry) throws IOException
                {
                    return super.createOutputStream(new RenamingOnceSpringBootClassPrefixDataEntry(dataEntry));
                }
            });

        // Read the input again, writing out disassembled/assembled/preverified
        // files.
        DataEntryReader reader = reader(new IdleRewriter((isOutputJar || isOutputJmod) ? writer : classAsJbcWriter),
            new IdleRewriter((isOutputJar || isOutputJmod) ? jbcAsClassWriter : writer),
            new DataEntryCopier(writer),
            (isOutputJar || isOutputJmod) ? false : true,
            forceUnpackClassesToRoot,
            unpackJarsInJar);

        if (inputSource instanceof DirectorySource) {
            final DataEntryReader downstreamReader = reader;
            // Fix for DirectorySource
            reader = new DataEntryReader() {

                private AtomicBoolean firstAlreadySkipped = new AtomicBoolean(false);

                @Override
                public void read(DataEntry dataEntry) throws IOException {
                    if (!firstAlreadySkipped.getAndSet(true)) {
                        return;
                    } else {
                        downstreamReader.read(dataEntry);
                    }
                }
                
            };
        }

        inputSource.pumpDataEntries(reader);

        writer.close();
    }


    /**
     * Creates a data entry source for the given file name.
     */
    private static DataEntrySource source(String inputFileName)
    {
        boolean isJar   = inputFileName.endsWith(".jar");
        boolean isJmod  = inputFileName.endsWith(".jmod");
        boolean isJbc   = inputFileName.endsWith(".jbc");
        boolean isClass = inputFileName.endsWith(".class");

        File inputFile = new File(inputFileName);

        return isJar  ||
               isJmod ||
               isJbc  ||
               isClass ?
                   new FileSource(inputFile) :
                   new DirectorySource(inputFile);
    }


    /**
     * Creates a data entry reader that unpacks archives if necessary and sends
     * data entries to given relevant readers.
     */
    private static DataEntryReader reader(DataEntryReader classReader,
                                          DataEntryReader jbcReader,
                                          DataEntryReader resourceReader,
                                          boolean isAllowUnpack,
                                          boolean forceUnpackClassesToRoot,
                                          boolean unpackJarsInJar)
    {
        // Handle class files and resource files.
        DataEntryReader reader =
             new FilteredDataEntryReader(new DataEntryNameFilter(new ExtensionMatcher(".class")),
                 classReader,
                 resourceReader);

        // Handle jbc files.
        reader =
            new FilteredDataEntryReader(new DataEntryNameFilter(new ExtensionMatcher(".jbc")),
                 jbcReader,
                 reader);

        // Strip the "classes/" prefix from class/jbc files inside jmod files.
        DataEntryReader prefixStrippingReader =
            new FilteredDataEntryReader(new DataEntryNameFilter(
                                        new OrMatcher(
                                        new ExtensionMatcher(".class"),
                                        new ExtensionMatcher(".jbc"))),
                new PrefixStrippingDataEntryReader(JMOD_CLASS_FILE_PREFIX, reader),
                reader);

        Function<DataEntryReader, DataEntryReader> prefixStrippingReaderForSpringBoot = downstreamReader ->
            new FilteredDataEntryReader(new DataEntryNameFilter(
                                        new OrMatcher(
                                        new ExtensionMatcher(".class"),
                                        new ExtensionMatcher(".jbc"))),
                new PrefixStrippingDataEntryReader("BOOT-INF/classes/", downstreamReader),
                downstreamReader);

        if (isAllowUnpack) {
            // Unpack jmod files.
            reader =
                new FilteredDataEntryReader(new DataEntryNameFilter(new ExtensionMatcher(".jmod")),
                    new JarReader(true, prefixStrippingReader),
                    reader);

            if (unpackJarsInJar) {
                reader =
                    new FilteredDataEntryReader(new DataEntryNameFilter(new ExtensionMatcher(".jar")),
                        new JarReader(false, forceUnpackClassesToRoot ? prefixStrippingReaderForSpringBoot.apply(reader) : reader),
                        reader);
            }

            // Unpack jar files.
            reader =
                new FilteredDataEntryReader(new DataEntryNameFilter(new ExtensionMatcher(".jar")),
                    new JarReader(false, forceUnpackClassesToRoot ? prefixStrippingReaderForSpringBoot.apply(reader) : reader),
                    reader);
        }

        return reader;
    }


    /**
     * Creates a data entry writer for the given file name that creates archives
     * if necessary.
     */
    private static DataEntryWriter writer(String outputFileName)
    {
        boolean isJar   = outputFileName.endsWith(".jar");
        boolean isJmod  = outputFileName.endsWith(".jmod");
        boolean isJbc   = outputFileName.endsWith(".jbc");
        boolean isClass = outputFileName.endsWith(".class");

        File outputFile = new File(outputFileName);

        DataEntryWriter writer =
            isJar   ||
            isJmod  ||
            isJbc   ||
            isClass ?
                new FixedFileWriter(outputFile) :
                new DirectoryWriter(outputFile);

        // Pack jar files.
        if (isJar)
        {
            class MyJarWriter extends JarWriter implements Closeable {
                public MyJarWriter(DataEntryWriter zipEntryWriter) {
                    super(zipEntryWriter);
                }

                @Override
                protected OutputStream createManifestOutputStream(DataEntry manifestEntry)
                        throws IOException {
                    OutputStream outputStream = super.createManifestOutputStream(manifestEntry);
                    if (mainClassSaved != null) {
                        PrintWriter writer = new PrintWriter(outputStream);
                        writer.println("Main-Class: " + mainClassSaved);
                        writer.flush();
                    }
                    if (startClassSaved != null) {
                        PrintWriter writer = new PrintWriter(outputStream);
                        writer.println("Start-Class: " + startClassSaved);
                        writer.flush();
                    }
                    return outputStream;
                }

                @Override
                public void close() throws IOException {
                    super.close();
                }
            }
            writer =
                new MyJarWriter(
                new ZipWriter(
                    new FixedStringMatcher("BOOT-INF/lib/", new NotMatcher(new OrMatcher())),
                    1,
                    false,
                    0,
                    writer
                ));
        }

        // Pack jmod files.
        else if (isJmod)
        {
            writer =
                new JarWriter(
                new ZipWriter(null, 1, false, 0, JMOD_HEADER,
                    writer));

            // Add the "classes/" prefix to class/jbc files inside jmod files.
            writer =
                new FilteredDataEntryWriter(new DataEntryNameFilter(
                                            new OrMatcher(
                                            new ExtensionMatcher(".class"),
                                            new ExtensionMatcher(".jbc"))),
                    new PrefixAddingDataEntryWriter(JMOD_CLASS_FILE_PREFIX, writer),
                    writer);
        }

        return writer;
    }
}
