/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.compiler.android;

import com.google.common.collect.Lists;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.testFramework.UsefulTestCase;
import junit.framework.Assert;
import org.jetbrains.jet.CompileCompilerDependenciesTest;
import org.jetbrains.jet.ConfigurationKind;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.TestJdkKind;
import org.jetbrains.jet.cli.jvm.compiler.CompileEnvironmentUtil;
import org.jetbrains.jet.cli.jvm.compiler.JetCoreEnvironment;
import org.jetbrains.jet.codegen.ClassFileFactory;
import org.jetbrains.jet.codegen.GenerationUtils;
import org.jetbrains.jet.compiler.PathManager;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.psi.JetPsiFactory;
import org.jetbrains.jet.utils.Printer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Natalia.Ukhorskaya
 */

public class CodegenTestsOnAndroidGenerator extends UsefulTestCase {

    private final PathManager pathManager;
    private final String testClassPackage = "org.jetbrains.jet.compiler.android";
    private final String testClassName = "CodegenTestCaseOnAndroid";
    private final String baseTestClassPackage = "org.jetbrains.jet.compiler.android";
    private final String baseTestClassName = "AbstractCodegenTestCaseOnAndroid";
    private final String generatorName = "CodegenTestsOnAndroidGenerator";

    private JetCoreEnvironment environmentWithMockJdk = JetTestUtils.createEnvironmentWithMockJdkAndIdeaAnnotations(myTestRootDisposable, ConfigurationKind.JDK_ONLY);
    private JetCoreEnvironment environmentWithMockJdkAndExternalAnnotations = JetTestUtils.createEnvironmentWithMockJdkAndIdeaAnnotations(myTestRootDisposable, ConfigurationKind.JDK_AND_ANNOTATIONS);
    private JetCoreEnvironment environmentWithFullJdk = JetTestUtils.createEnvironmentWithFullJdk(myTestRootDisposable);
    private JetCoreEnvironment environmentWithFullJdkAndJUnit;
    
    private final Pattern packagePattern = Pattern.compile("package (.*)");

    private final List<String> generatedTestNames = Lists.newArrayList();

    public static void generate(PathManager pathManager) throws Throwable {
        new CodegenTestsOnAndroidGenerator(pathManager).generateOutputFiles();
    }

    private CodegenTestsOnAndroidGenerator(PathManager pathManager) {
        this.pathManager = pathManager;

        File junitJar = new File("libraries/lib/junit-4.9.jar");

        if (!junitJar.exists()) {
            throw new AssertionError();
        }

        environmentWithFullJdkAndJUnit = new JetCoreEnvironment(myTestRootDisposable, CompileCompilerDependenciesTest.compilerConfigurationForTests(
                ConfigurationKind.ALL, TestJdkKind.FULL_JDK, JetTestUtils.getAnnotationsJar(), junitJar));

    }

    private void generateOutputFiles() throws Throwable {
        prepareAndroidModule();
        generateAndSave();
    }

    private void prepareAndroidModule() throws IOException {
        System.out.println("Copying kotlin-runtime.jar in android module...");
        copyKotlinRuntimeJar();

        System.out.println("Check \"libs\" folder in tested android module...");
        File libsFolderInTestedModule = new File(pathManager.getLibsFolderInAndroidTestedModuleTmpFolder());
        if (!libsFolderInTestedModule.exists()) {
            libsFolderInTestedModule.mkdirs();
        }
    }

    private void copyKotlinRuntimeJar() throws IOException {
        File kotlinRuntimeJar = new File(pathManager.getLibsFolderInAndroidTmpFolder() + "/kotlin-runtime.jar");
        File kotlinRuntimeInDist = new File("dist/kotlinc/lib/kotlin-runtime.jar");
        Assert.assertTrue("kotlin-runtime.jar in dist/kotlnc/lib/ doesn't exists. Run dist ant task before generating test for android.",
                          kotlinRuntimeInDist.exists());
        FileUtil.copy(kotlinRuntimeInDist, kotlinRuntimeJar);
    }

    private void generateAndSave() throws Throwable {
        System.out.println("Generating test files...");
        StringBuilder out = new StringBuilder();
        Printer p = new Printer(out);

        p.print(FileUtil.loadFile(new File("injector-generator/copyright.txt")));
        p.println("package " + testClassPackage + ";");
        p.println();
        p.println("import ", baseTestClassPackage, ".", baseTestClassName, ";");
        p.println();
        p.println("/* This class is generated by " + generatorName + ". DO NOT MODIFY MANUALLY */");
        p.println("public class ", testClassName, " extends ", baseTestClassName, " {");
        p.pushIndent();

        File testDataSources = new File("compiler/testData/codegen/");
        generateTestMethodsForDirectory(p, testDataSources);

        p.popIndent();
        p.println("}");

        String testSourceFilePath =
                pathManager.getSrcFolderInAndroidTmpFolder() + "/" + testClassPackage.replace(".", "/") + "/" + testClassName + ".java";
        FileUtil.writeToFile(new File(testSourceFilePath), out.toString());
    }

    private void generateTestMethodsForDirectory(Printer p, File dir) throws IOException {
        File[] files = dir.listFiles();
        Assert.assertNotNull("Folder with testData is empty: " + dir.getAbsolutePath(), files);
        Set<String> excludedFiles = SpecialFiles.getExcludedFiles();
        Set<String> filesCompiledWithoutStdLib = SpecialFiles.getFilesCompiledWithoutStdLib();
        Set<String> filesCompiledWithJUnit = SpecialFiles.getFilesCompiledWithJUnit();
        Set<String> filesCompiledWithExternalAnnotations = SpecialFiles.getFilesCompiledWithExternalAnnotations();
        for (File file : files) {
            if (excludedFiles.contains(file.getName())) {
                continue;
            }
            if (file.isDirectory()) {
                generateTestMethodsForDirectory(p, file);
            }
            else {
                String text = FileUtil.loadFile(file, true);

                if (hasBoxMethod(text)) {
                    String generatedTestName = generateTestName(file.getName());
                    String packageName = file.getPath().replaceAll("\\\\|-|\\.|/", "_");
                    text = changePackage(packageName, text);
                    final ClassFileFactory factory;
                    if (filesCompiledWithoutStdLib.contains(file.getName())) {
                        factory = getFactoryFromText(file.getAbsolutePath(), text, environmentWithMockJdk);
                    }
                    else if (filesCompiledWithJUnit.contains(file.getName())) {
                        factory = getFactoryFromText(file.getAbsolutePath(), text, environmentWithFullJdkAndJUnit);
                    }
                    else if (filesCompiledWithExternalAnnotations.contains(file.getName())) {
                        factory = getFactoryFromText(file.getAbsolutePath(), text, environmentWithMockJdkAndExternalAnnotations);
                    }
                    else {
                        factory = getFactoryFromText(file.getAbsolutePath(), text, environmentWithFullJdk);
                    }

                    generateTestMethod(p, generatedTestName, StringUtil.escapeStringCharacters(file.getPath()));
                    File outputDir = new File(pathManager.getOutputForCompiledFiles());
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
                    Assert.assertTrue("Cannot create directory for compiled files", outputDir.exists());

                    CompileEnvironmentUtil.writeToOutputDirectory(factory, outputDir);
                }
            }
        }
    }

    private ClassFileFactory getFactoryFromText(String filePath, String text, JetCoreEnvironment jetEnvironment) {
        JetFile psiFile = JetPsiFactory.createFile(jetEnvironment.getProject(), text);
        ClassFileFactory factory;
        try {
            factory = GenerationUtils.compileFileGetClassFileFactoryForTest(psiFile);
        }
        catch (Throwable e) {
            throw new RuntimeException("Cannot compile: " + filePath + "\n" + text, e);
        }
        return factory;
    }

    private boolean hasBoxMethod(String text) {
        return text.contains("fun box()");
    }

    private String changePackage(String testName, String text) {
        if (text.contains("package ")) {
            Matcher matcher = packagePattern.matcher(text);
            return matcher.replaceAll("package " + testName);
        }
        else {
            return "package " + testName + ";\n" + text;
        }
    }

    private void generateTestMethod(Printer p, String testName, String namespace) {
        p.println("public void test" + testName + "() throws Exception {");
        p.pushIndent();
        p.println("invokeBoxMethod(\"" + namespace + "\");");
        p.popIndent();
        p.println("}");
        p.println();
    }

    private String generateTestName(String fileName) {
        String result = FileUtil.getNameWithoutExtension(StringUtil.capitalize(fileName));

        int i = 0;
        while (generatedTestNames.contains(result)) {
            result += "_" + i++;
        }
        generatedTestNames.add(result);
        return result;
    }
}
