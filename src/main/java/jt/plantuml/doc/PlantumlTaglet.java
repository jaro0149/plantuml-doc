/*
 * Copyright 2020 Jaroslav Tóth
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */
package jt.plantuml.doc;

import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.DocumentationTool;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Taglet;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

/**
 * Javadoc {@link Taglet} used for including UML diagrams in the generated documentation in the form of SVG images.
 * The properties:<br>
 * - Diagrams must be stored in the plantuml-formatted file.<br>
 * - Format of tag: '@plantuml [path-to-diagram]'. The 'path-to-diagram' must be relative to source directory.<br>
 * - Tag can be used within 'package-info.java', 'module-info.java', type, method, and field javadoc comments.<br>
 * - Multiple plantuml tags in the single documentation block are permitted.<br>
 * - Inline tags are not supported.
 */
public final class PlantumlTaglet implements Taglet {
    private JavaFileManager javaFileManager;
    private Elements elementUtils;

    @Override
    public void init(final DocletEnvironment env, final Doclet doclet) {
        this.javaFileManager = env.getJavaFileManager();
        this.elementUtils = env.getElementUtils();
    }

    @Override
    public String getName() {
        return "plantuml";
    }

    @Override
    public boolean isInlineTag() {
        return false;
    }

    @Override
    public Set<Location> getAllowedLocations() {
        return Set.of(Location.MODULE, Location.PACKAGE, Location.TYPE, Location.CONSTRUCTOR,
                Location.FIELD, Location.METHOD);
    }

    @Override
    public String toString(final List<? extends DocTree> tags, final Element element) {
        final String packagePath = getPackagePath(element);
        final String srcDirPath = getSrcDirPath(packagePath, element);

        final StringBuilder outputBuilder = new StringBuilder();
        for (final DocTree tag : tags) {
            if (!(tag instanceof UnknownBlockTagTree)) {
                System.err.println("Unexpected tag type: " + tag);
                continue;
            }
            createDiagrams(packagePath, srcDirPath, (UnknownBlockTagTree) tag)
                    .ifPresent(outputBuilder::append);
        }
        return outputBuilder.toString();
    }

    /**
     * Creation of UML diagrams from stored plantuml file that is described by path in {@link UnknownBlockTagTree}.
     *
     * @param packagePath package path that is used for placing of output image files in the javadoc structure
     * @param srcDirPath  project source directory that should also contain plantuml files
     * @param tag         javadoc tag that contains relative path to the plantuml file under source directory
     * @return created html code with pointers to stored diagrams
     * @see #createImage(String, String, SourceStringReader, int)
     */
    private Optional<String> createDiagrams(final String packagePath, final String srcDirPath,
                                            final UnknownBlockTagTree tag) {
        final Path umlPath = new File(srcDirPath + tag.getContent().toString().trim()).toPath();
        final String plantumlSnippet;
        try {
            plantumlSnippet = Files.readString(umlPath);
        } catch (IOException e) {
            System.err.println("Failed to read plantuml diagram on the path: " + umlPath);
            return Optional.empty();
        }

        final SourceStringReader umlReader = new SourceStringReader(plantumlSnippet, StandardCharsets.UTF_8.name());
        final int numberOfImages = getNumberOfImages(umlReader);
        final List<String> htmlSnippets = new ArrayList<>();
        for (int imageIndex = 0; imageIndex < numberOfImages; imageIndex++) {
            createImage(packagePath, umlPath.getFileName().toString(), umlReader, imageIndex)
                    .ifPresent(htmlSnippets::add);
        }
        if (htmlSnippets.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(String.join("", htmlSnippets));
        }
    }

    /**
     * Creation of single UML diagram from loaded {@link SourceStringReader}. Provided image index is used for selection
     * of correct UML diagram. The following procedure is followed:<br>
     * 1. preparation of output {@link FileObject} under output javadoc structure,<br>
     * 2. writing image file to output stream,<br>
     * 3. creation and returning of html code with img reference to written image file.
     *
     * @param packagePath package path that is used for placing of output image files in the javadoc structure
     * @param umlFilename name of the UML file; it is used for naming of output image file
     * @param umlReader   plantuml file reader
     * @param imageIndex  image index; single plantuml file may contain multiple nested diagrams
     * @return created html code with pointer to stored diagram
     */
    private Optional<String> createImage(final String packagePath, final String umlFilename,
                                         final SourceStringReader umlReader, final int imageIndex) {
        final String outputRelativePath = "doc-files/" + imageIndex + '_' + umlFilename + ".svg";
        final FileObject outputFileObject;
        try {
            outputFileObject = javaFileManager.getFileForOutput(DocumentationTool.Location.DOCUMENTATION_OUTPUT,
                    packagePath, outputRelativePath, null);
        } catch (IOException e) {
            System.err.println("Failed to load output file on the path: " + outputRelativePath);
            return Optional.empty();
        }

        try (OutputStream outputStream = outputFileObject.openOutputStream()) {
            umlReader.generateImage(outputStream, imageIndex, new FileFormatOption(FileFormat.SVG));
        } catch (IOException e) {
            System.err.println("Failed to export plantuml diagram to the file: " + outputRelativePath);
            return Optional.empty();
        }
        return Optional.of("<br><img src=\"" + outputRelativePath + "\" alt=\"diagram " + imageIndex +"\">");
    }

    /**
     * Derive absolute path to the directory which contains project source files. The path is computed from
     * the classpath of the java source file that contains provided {@link Element} and provided package path that is
     * afterwards stripped from the classpath.
     *
     * @param parentPath parent package path
     * @param element    java source element
     * @return absolute path to the source directory
     */
    private String getSrcDirPath(final String parentPath, final Element element) {
        final String elementIdentifier = getElementIdentifier(element);
        final String classpath;
        if (parentPath.isEmpty()) {
            classpath = elementIdentifier;
        } else {
            classpath = parentPath + '.' + elementIdentifier;
        }
        final String pathToJavaFile = getPathToJavaFile(classpath).getPath();
        final String pathSuffix = classpath.replace('.', '/') + ".java";
        return removeSuffix(removeSuffix(pathToJavaFile, pathSuffix), "java/");
    }

    /**
     * Derive parent package path of the provided {@link Element}. If provided element is {@link PackageElement},
     * its direct qualified name will be returned. If provided element is {@link ModuleElement}, an empty string will
     * be returned.
     *
     * @param element java source element
     * @return qualified name of the parent package
     */
    private String getPackagePath(final Element element) {
        if (element instanceof ModuleElement) {
            return "";
        } else if (element instanceof PackageElement) {
            return ((PackageElement) element).getQualifiedName().toString();
        } else {
            return elementUtils.getPackageOf(element).getQualifiedName().toString();
        }
    }

    /**
     * Parsing element identifier. Elements that are bundled inside class are always translated into top class name.
     * {@link ModuleElement} is translated into 'module-info' string and {@link PackageElement} is translated into
     * 'package-info' string.
     *
     * @param element java source element
     * @return element identifier
     */
    private static String getElementIdentifier(final Element element) {
        if (element instanceof ModuleElement) {
            return "module-info";
        } else if (element instanceof PackageElement) {
            return "package-info";
        } else if (element instanceof TypeElement) {
            final NestingKind nestingKind = ((TypeElement) element).getNestingKind();
            if (nestingKind.isNested()) {
                return getElementIdentifier(element.getEnclosingElement());
            } else {
                return element.getSimpleName().toString();
            }
        } else if (element instanceof VariableElement) {
            return getElementIdentifier(element.getEnclosingElement());
        } else {
            return element.getSimpleName().toString();
        }
    }

    /**
     * Get path to the file with java class that is identified by classpath.
     *
     * @param classpath class identifier
     * @return {@link URI} to java class
     */
    private URI getPathToJavaFile(final String classpath) {
        final JavaFileObject javaObject;
        try {
            javaObject = javaFileManager.getJavaFileForInput(StandardLocation.SOURCE_PATH, classpath, Kind.SOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JAVA source on the classpath: " + classpath, e);
        }
        return javaObject.toUri();
    }

    /**
     * Remove suffix from provided {@link String}, if there is such suffix. In other case, the original {@link String}
     * object will be returned.
     *
     * @param str    input string
     * @param suffix expected suffix
     * @return modified {@link String}
     */
    private static String removeSuffix(final String str, final String suffix) {
        if (str != null && suffix != null && str.endsWith(suffix)) {
            return str.substring(0, str.length() - suffix.length());
        }
        return str;
    }

    /**
     * Get total number of images that are wrapped in the plantuml file. Single plantuml file can contain multiple
     * blocks and single block can contain multiple pages.
     *
     * @param plantumlReader plantuml reader
     * @return count
     */
    private static int getNumberOfImages(final SourceStringReader plantumlReader) {
        return plantumlReader.getBlocks().stream()
                .map(blockUml -> blockUml.getDiagram().getNbImages())
                .reduce(Integer::sum)
                .orElse(0);
    }
}